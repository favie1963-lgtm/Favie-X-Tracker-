package com.favie.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import android.content.BroadcastReceiver

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var receiver: BroadcastReceiver? = null
    private var currentAppPackage: String = ""
    private var currentAppClass: String = ""
    private var isTracking = false

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            showOverlay()
            registerBroadcastReceiver()
            isTracking = true
        } else if (intent?.action == ACTION_STOP) {
            hideOverlay()
            if (receiver != null) {
                unregisterReceiver(receiver)
            }
            isTracking = false
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        overlayView = OverlayView(this).apply {
            setOnTouchListener(object : View.OnTouchListener {
                private var lastX = 0f
                private var lastY = 0f
                private var initialX = 0
                private var initialY = 0

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.rawX
                            lastY = event.rawY
                            initialX = params?.x ?: 0
                            initialY = params?.y ?: 0
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = (event.rawX - lastX).toInt()
                            val deltaY = (event.rawY - lastY).toInt()
                            params?.x = initialX + deltaX
                            params?.y = initialY + deltaY
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                    }
                    return true
                }
            })
        }

        params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = 400
            height = 300
            x = 0
            y = 0
        }

        windowManager?.addView(overlayView, params)
    }

    private fun hideOverlay() {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
        }
    }

    private fun registerBroadcastReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AccessibilityMonitorService.ACTION_APP_CHANGED -> {
                        currentAppPackage = intent.getStringExtra("package") ?: ""
                        currentAppClass = intent.getStringExtra("class") ?: ""
                        overlayView?.updateAppInfo(currentAppPackage, currentAppClass)
                    }
                }
            }
        }.also {
            val filter = IntentFilter(AccessibilityMonitorService.ACTION_APP_CHANGED)
            registerReceiver(it, filter, Context.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Favie Tracker",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Favie X Tracker")
            .setContentText("Tracking active - Tap to open")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "favie_tracker_channel"
        const val ACTION_START = "com.favie.tracker.ACTION_START"
        const val ACTION_STOP = "com.favie.tracker.ACTION_STOP"
    }
}

class OverlayView(context: Context) : FrameLayout(context) {
    private val textView: TextView
    private var appPackage = ""
    private var appClass = ""
    private var detectionCount = 0

    init {
        setBackgroundColor(Color.parseColor("#99000000"))
        
        textView = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.GREEN)
            text = "Favie Tracker\nReady"
            setPadding(16, 16, 16, 16)
        }
        addView(textView)
    }

    fun updateAppInfo(packageName: String, className: String) {
        appPackage = packageName
        appClass = className
        updateUI()
    }

    fun updateDetections(count: Int) {
        detectionCount = count
        updateUI()
    }

    private fun updateUI() {
        textView.text = """Favie Tracker
            |App: $appPackage
            |Class: ${appClass.substringAfterLast(".")}
            |Detections: $detectionCount
            |Status: ACTIVE""".trimMargin()
    }
}
