package com.dogukan.bigmatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TapService : AccessibilityService() {
    companion object { @Volatile var active: TapService? = null; private set }
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    override fun onServiceConnected() { active = this }
    override fun onDestroy() { cancelTaps(); active = null; super.onDestroy() }
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
