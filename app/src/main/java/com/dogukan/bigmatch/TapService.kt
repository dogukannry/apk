package com.dogukan.bigmatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class TapService : AccessibilityService() {
    companion object { @Volatile var active: TapService? = null; private set }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var actionButton: Button? = null

    override fun onServiceConnected() {
        active = this
        main.post {
            try {
                val dp = resources.displayMetrics.density
                val root = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10*dp).toInt(),(5*dp).toInt(),(6*dp).toInt(),(5*dp).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.argb(232, 23, 31, 48))
                        cornerRadius = 32*dp
                    }
                }
                val status = TextView(this).apply {
                    text = "Önce ekran yakalamayı başlat"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    maxLines = 2
                }
                val action = Button(this).apply {
                    text = "OKU"
                    textSize = 12f
                    isAllCaps = false
                    backgroundTintList = ColorStateList.valueOf(Color.rgb(38, 113, 243))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        val capture = CaptureService.active
                        if (capture == null) updateUI("Önce yakalamayı başlat", "OKU")
                        else capture.onOverlayAction()
                    }
                    setOnLongClickListener {
                        CaptureService.active?.stopSelf()
                        updateUI("Yakalama durduruldu", "OKU")
                        true
                    }
                }
                root.addView(status, LinearLayout.LayoutParams((155*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
                root.addView(action, LinearLayout.LayoutParams((90*dp).toInt(), (48*dp).toInt()))
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (84*dp).toInt()
                }
                val manager = getSystemService(WINDOW_SERVICE) as WindowManager
                manager.addView(root, params)
                panel = root
                statusText = status
                actionButton = action
                windowManager = manager
                CaptureService.active?.updatePanel()
            } catch (_: Exception) { }
        }
    }

    fun updateUI(status: String, action: String = "OKU") {
        main.post {
            statusText?.text = status
            actionButton?.text = action
            actionButton?.isEnabled = action != "BEKLE"
        }
    }

    override fun onDestroy() {
        cancelTaps()
        try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        panel = null
        active = null
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { cancelTaps() }

    fun cancelTaps() { generation++ }

    fun tapPairs(pairs: List<Match>, onFinished: () -> Unit) {
        main.post {
            cancelTaps()
            val token = generation
            val points = pairs.flatMap { listOf(it.first, it.second) }
            tapNext(points, 0, token, onFinished)
        }
    }

    private fun tapNext(points: List<Card>, index: Int, token: Int, finished: () -> Unit) {
        if (token != generation) return
        if (index == points.size) { finished(); return }
        val p = points[index]
        val path = Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                main.postDelayed({ tapNext(points, index+1, token, finished) },
                    if (index % 2 == 0) 250 else 550)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) { cancelTaps() }
        }, main)
    }
}
