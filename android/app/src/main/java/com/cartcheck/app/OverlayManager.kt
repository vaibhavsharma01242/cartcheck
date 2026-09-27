package com.cartcheck.app

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/** The on-screen card shown over the delivery app. Call only from the main thread. */
class OverlayManager(context: Context) {
    private val themed = ContextThemeWrapper(context, R.style.Theme_CartCheck)
    private val wm = context.getSystemService(WindowManager::class.java)
    @Volatile private var root: View? = null

    var onContinue: (() -> Unit)? = null
    var onRecheck: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    fun showLoading(app: String) {
        val v = ensureView()
        v.text(R.id.badge).text = "CartCheck · $app"
        v.text(R.id.headline).apply { text = "Checking your cart…"; setTextColor(NEUTRAL) }
        listOf(R.id.totals, R.id.items, R.id.suggestion).forEach { v.findViewById<View>(it).visibility = View.GONE }
        v.findViewById<ProgressBar>(R.id.progress).visibility = View.VISIBLE
        v.findViewById<Button>(R.id.btnRecheck).visibility = View.GONE
        v.findViewById<Button>(R.id.btnContinue).text = "Skip"
    }

    fun showResult(app: String, verdict: Verdict) {
        val v = ensureView()
        v.findViewById<ProgressBar>(R.id.progress).visibility = View.GONE
        v.text(R.id.badge).text = buildString {
            append("CartCheck · ").append(app)
            verdict.score?.let { append(" · score ").append(it).append("/100") }
        }
        val (prefix, color) = when (verdict.rating) {
            "good" -> "✅ " to GOOD
            "bad" -> "🛑 " to BAD
            "error" -> "" to NEUTRAL
            else -> "⚠️ " to CAUTION
        }
        v.text(R.id.headline).apply { text = prefix + verdict.headline; setTextColor(color) }
        v.text(R.id.totals).setOrHide(verdict.error ?: verdict.totalsLine())
        v.text(R.id.items).setOrHide(verdict.itemsText())
        v.text(R.id.suggestion).setOrHide(verdict.suggestion.takeIf { it.isNotBlank() }?.let { "💡 $it" })
        v.findViewById<Button>(R.id.btnRecheck).apply {
            visibility = View.VISIBLE
            text = if (verdict.error != null) "Retry" else "Re-check"
        }
        v.findViewById<Button>(R.id.btnContinue).text = if (verdict.rating == "bad") "Order anyway" else "Continue"
    }

    fun dismiss() {
        val v = root ?: return
        root = null
        runCatching { wm.removeView(v) }
    }

    private fun ensureView(): View {
        root?.let { return it }
        val v = LayoutInflater.from(themed).inflate(R.layout.overlay_verdict, null)
        v.findViewById<Button>(R.id.btnContinue).setOnClickListener { dismiss(); onContinue?.invoke() }
        v.findViewById<Button>(R.id.btnRecheck).setOnClickListener { dismiss(); onRecheck?.invoke() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(v, lp)
        root = v
        return v
    }

    private fun View.text(id: Int): TextView = findViewById(id)

    private fun TextView.setOrHide(value: String?) {
        if (value.isNullOrBlank()) visibility = View.GONE
        else { text = value; visibility = View.VISIBLE }
    }

    private companion object {
        val GOOD = 0xFF1B873F.toInt()
        val CAUTION = 0xFFB26A00.toInt()
        val BAD = 0xFFC62828.toInt()
        val NEUTRAL = 0xFF222222.toInt()
    }
}
