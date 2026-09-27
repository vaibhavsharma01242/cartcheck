package com.cartcheck.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var inputMaxCal: EditText
    private lateinit var inputMaxSugar: EditText
    private lateinit var inputNotes: EditText
    private lateinit var inputUrl: EditText
    private lateinit var inputToken: EditText

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                toast("Monitoring started. Open Swiggy, Zomato or Blinkit.")
                status.postDelayed({ refreshStatus() }, 800)
            } else {
                toast("Screen capture permission was denied")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestScreenCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.statusText)
        inputMaxCal = findViewById(R.id.inputMaxCal)
        inputMaxSugar = findViewById(R.id.inputMaxSugar)
        inputNotes = findViewById(R.id.inputNotes)
        inputUrl = findViewById(R.id.inputUrl)
        inputToken = findViewById(R.id.inputToken)

        inputMaxCal.setText(prefs.maxCalories.toString())
        inputMaxSugar.setText(prefs.maxSugarG.toString())
        inputNotes.setText(prefs.goalNotes)
        inputUrl.setText(prefs.backendUrl)
        inputToken.setText(prefs.appToken)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings(); toast("Saved") }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testBackend() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            status.postDelayed({ refreshStatus() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun startMonitoring() {
        saveSettings()
        when {
            CaptureService.isRunning -> toast("Already running")
            !Settings.canDrawOverlays(this) -> toast("Step 1: allow display over other apps")
            !hasUsageAccess() -> toast("Step 2: allow usage access for CartCheck")
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Forces "entire screen" so the user can't accidentally share only one app.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun testBackend() {
        saveSettings()
        val sample = """
            Your cart
            Coca-Cola Original Taste Soft Drink 750 ml   x1   ₹40
            Chicken Biryani (Full)   x1   ₹349
            Gulab Jamun (2 pcs)   x2   ₹198
            Bill Details
            Item Total ₹587
            Delivery Fee ₹30
            Platform fee ₹10
            To Pay ₹627
        """.trimIndent()
        status.text = "Testing backend…"
        lifecycleScope.launch {
            val verdict = ApiClient.analyze(prefs, "Swiggy", sample)
            refreshStatus()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(verdict.headline)
                .setMessage(verdict.toDisplayText())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun saveSettings() {
        prefs.maxCalories = inputMaxCal.text.toString().toIntOrNull() ?: 0
        prefs.maxSugarG = inputMaxSugar.text.toString().toIntOrNull() ?: 0
        prefs.goalNotes = inputNotes.text.toString().trim()
        prefs.backendUrl = inputUrl.text.toString().trim().ifEmpty { Prefs.DEFAULT_URL }
        prefs.appToken = inputToken.text.toString().trim()
    }

    private fun refreshStatus() {
        val running = CaptureService.isRunning
        status.text = buildString {
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} Display over other apps")
            appendLine("${mark(hasUsageAccess())} Usage access")
            append("${mark(running)} Monitoring ${if (running) "ON" else "OFF"}")
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "⬜"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
