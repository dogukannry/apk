package com.dogukan.bigmatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TapService : AccessibilityService() {
    companion object { @Volatile var active: TapService? = null; private set }
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var statusView: TextView? = null
    private var windowManager: WindowManager? = null

    override fun onServiceConnected() {
        active = this
        handler.post {
            try {
                val manager = getSystemService(WINDOW_SERVICE) as WindowManager
                val dp = resources.displayMetrics.density
                val label = TextView(this).apply {
                    text = "Big Match • Hazır"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding((12*dp).toInt(),(7*dp).toInt(),(12*dp).toInt(),(7*dp).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.argb(210, 25, 31, 44))
                        cornerRadius = 20*dp
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (58*dp).toInt() }
                manager.addView(label,params)
                statusView = label
                windowManager = manager
            } catch (_: Exception) { }
        }
    }

    fun updateStatus(status: String) {
        handler.post { statusView?.text = "Big Match • $status" }
    }
    override fun onDestroy() {
        cancelTaps()
        try { statusView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        statusView = null
        active = null
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { cancelTaps() }

    fun cancelTaps() { generation++ }

    fun tapPairs(pairs: List<Match>, onFinished: () -> Unit) {
        cancelTaps()
        val token = generation
        val points = pairs.flatMap { listOf(it.first, it.second) }
        funTap(points, 0, token, onFinished)
    }

    private fun funTap(points: List<Card>, index: Int, token: Int, finished: () -> Unit) {
        if (token != generation) return
        if (index == points.size) { finished(); return }
        val p = points[index]
        val path = Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 55)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ funTap(points, index+1, token, finished) }, if (index % 2 == 0) 210 else 480)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) { cancelTaps() }
        }, handler)
    }
}
