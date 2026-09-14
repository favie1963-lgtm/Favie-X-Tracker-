package com.favie.tracker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class AccessibilityMonitorService : AccessibilityService() {
    private val TAG = "AccessibilityMonitor"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName.toString()
                val className = event.className.toString()
                Log.d(TAG, "App changed: $packageName")
                
                // Notify overlay service about app change
                sendBroadcast(Intent(ACTION_APP_CHANGED).apply {
                    putExtra("package", packageName)
                    putExtra("class", className)
                })
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "View clicked")
                sendBroadcast(Intent(ACTION_VIEW_CLICKED))
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                Log.d(TAG, "View scrolled")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
    }

    companion object {
        const val ACTION_APP_CHANGED = "com.favie.tracker.ACTION_APP_CHANGED"
        const val ACTION_VIEW_CLICKED = "com.favie.tracker.ACTION_VIEW_CLICKED"
    }
}
