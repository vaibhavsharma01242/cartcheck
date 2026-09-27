package com.cartcheck.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Foreground service that:
 * 1. mirrors the screen into an ImageReader (MediaProjection),
 * 2. every few seconds, if Swiggy/Zomato/Blinkit is in front, grabs a frame,
 * 3. runs on-device OCR, and if it looks like a cart, asks the backend for a verdict,
 * 4. shows the verdict as an overlay card.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.cartcheck.app.STOP"

        private const val TAG = "CartCheck"
        private const val CHANNEL_ID = "cartcheck_capture"
        private const val NOTIF_ID = 42
        private const val POLL_MS = 2_500L
        private const val SUPPRESS_AFTER_CONTINUE_MS = 3 * 60_000L
        private const val SUPPRESS_NOT_CART_MS = 30_000L
        private const val MAX_CAPTURE_WIDTH = 1080

        /** Package name -> display name. Add Zepto, Instamart etc. here. */
        val TARGET_APPS = mapOf(
            "in.swiggy.android" to "Swiggy",
            "com.application.zomato" to "Zomato",
            "com.grofers.customerapp" to "Blinkit",
        )

        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val suppressUntil = ConcurrentHashMap<String, Long>()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var overlay: OverlayManager
    @Volatile private var lastAnalyzedPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayManager(this).apply {
            onContinue = {
                lastAnalyzedPkg?.let { suppressUntil[it] = System.currentTimeMillis() + SUPPRESS_AFTER_CONTINUE_MS }
            }
            onRecheck = { lastAnalyzedPkg?.let { suppressUntil.remove(it) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+: the service must already be foreground (mediaProjection type)
        // before getMediaProjection() is called.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (projection != null) return START_NOT_STICKY // already capturing

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be registered before createVirtualDisplay() on Android 14+.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        startCapture(mp)
        isRunning = true
        scope.launch { pollLoop() }
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val metrics = resources.displayMetrics
        var (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
        if (w > MAX_CAPTURE_WIDTH) { // downscale: faster OCR, less memory
            h = h * MAX_CAPTURE_WIDTH / w
            w = MAX_CAPTURE_WIDTH
        }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "CartCheckCapture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            delay(POLL_MS)
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
        }
    }

    private suspend fun tick() {
        if (overlay.isShowing) return
        val pkg = ForegroundApp.current(this) ?: return
        val appName = TARGET_APPS[pkg] ?: return
        if (System.currentTimeMillis() < (suppressUntil[pkg] ?: 0L)) return

        // null = screen hasn't changed since the last frame we looked at
        val bitmap = grabBitmap() ?: return
        val text = try { recognize(bitmap) } finally { bitmap.recycle() }
        if (!CartDetector.looksLikeCart(text)) return

        Log.d(TAG, "Cart detected in $appName, analysing")
        lastAnalyzedPkg = pkg
        withContext(Dispatchers.Main) { overlay.showLoading(appName) }

        val verdict = ApiClient.analyze(Prefs(this), appName, text)

        withContext(Dispatchers.Main) {
            if (!overlay.isShowing) return@withContext // user tapped Skip while loading
            if (verdict.isNotCart) {
                overlay.dismiss()
                suppressUntil[pkg] = System.currentTimeMillis() + SUPPRESS_NOT_CART_MS
            } else {
                overlay.showResult(appName, verdict)
            }
        }
    }

    private fun grabBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) return padded
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            return cropped
        } finally {
            image.close()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cart monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags,
        )
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("CartCheck is watching your carts")
            .setContentText("Swiggy · Zomato · Blinkit")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        overlay.dismiss()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.let { p -> projection = null; p.stop() }
        recognizer.close()
        super.onDestroy()
    }
}
