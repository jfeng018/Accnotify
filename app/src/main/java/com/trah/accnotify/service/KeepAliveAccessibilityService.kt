package com.trah.accnotify.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import com.trah.accnotify.AccnotifyApp
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeepAliveA11y"

        @Volatile
        var isServiceRunning = false
            private set
    }

    private var aliveView: View? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        isServiceRunning = true

        // Add 1x1 invisible overlay view to help keep the service alive
        addAliveView()

        // Start WebSocket service
        startWebSocketService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Minimal - we only need notification events for keep-alive
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "Accessibility service unbound")
        isServiceRunning = false
        removeAliveView()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "Accessibility service destroyed")
        isServiceRunning = false
        removeAliveView()
        super.onDestroy()
    }

    /**
     * Add a 1x1 pixel invisible overlay view using TYPE_ACCESSIBILITY_OVERLAY.
     * The system considers the service as actively using a window, making it
     * much harder to be killed.
     */
    private fun addAliveView() {
        removeAliveView()
        try {
            val view = View(this)
            val lp = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                gravity = Gravity.START or Gravity.TOP
                width = 1
                height = 1
                packageName = this@KeepAliveAccessibilityService.packageName
            }
            wm.addView(view, lp)
            aliveView = view
            Log.i(TAG, "Alive overlay view added")
        } catch (e: Exception) {
            aliveView = null
            Log.e(TAG, "Failed to add alive overlay view", e)
        }
    }

    private fun removeAliveView() {
        aliveView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove alive overlay view", e)
            }
        }
        aliveView = null
    }

    private fun startWebSocketService() {
        val isRegistered = try {
            AccnotifyApp.getInstance().keyManager.isRegistered
        } catch (_: Exception) {
            false
        }

        if (!isRegistered) {
            Log.i(TAG, "Skip starting WebSocket service: device not registered yet")
            return
        }

        WebSocketService.start(this)
    }
}

