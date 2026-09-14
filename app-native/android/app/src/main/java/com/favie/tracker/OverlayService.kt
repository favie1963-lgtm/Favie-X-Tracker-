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
import android.widget.Button
import android.widget.LinearLayout
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
    private val objectTracker = ObjectTracker()
    private var selectedObjectId: String? = null

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
        } else if (intent?.action == ACTION_SELECT_OBJECT) {
            val objectId = intent.getStringExtra("objectId")
            if (objectId != null) {
                selectedObjectId = objectId
                objectTracker.selectObject(objectId)
                overlayView?.selectObject(objectId)
            }
        } else if (intent?.action == ACTION_DESELECT_OBJECT) {
            selectedObjectId = null
            objectTracker.deselectObject()
            overlayView?.deselectObject()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        overlayView = OverlayView(this, objectTracker).apply {
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
            width = 500
            height = 400
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
                    ACTION_UPDATE_DETECTIONS -> {
                        val appPackage = intent.getStringExtra("package") ?: ""
                        val detections = intent.getParcelableArrayListExtra("detections") ?: emptyList<DetectedObject>()
                        
                        currentAppPackage = appPackage
                        objectTracker.updateDetections(detections as List<DetectedObject>)
                        overlayView?.updateTracking(objectTracker)
                    }
                }
            }
        }.also {
            val filter = IntentFilter(ACTION_UPDATE_DETECTIONS)
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
        const val ACTION_SELECT_OBJECT = "com.favie.tracker.ACTION_SELECT_OBJECT"
        const val ACTION_DESELECT_OBJECT = "com.favie.tracker.ACTION_DESELECT_OBJECT"
        const val ACTION_UPDATE_DETECTIONS = "com.favie.tracker.ACTION_UPDATE_DETECTIONS"
    }
}

class OverlayView(context: Context, private val tracker: ObjectTracker) : LinearLayout(context) {
    private val titleText: TextView
    private val dataText: TextView
    private val stopButton: Button
    private var selectedObjectId: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#CC000000"))
        setPadding(12, 12, 12, 12)

        titleText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.GREEN)
            text = "Favie Tracker"
            textStyle = android.graphics.Typeface.BOLD
        }
        addView(titleText)

        dataText = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.CYAN)
            text = "Ready to track..."
            setPadding(0, 8, 0, 8)
        }
        addView(dataText)

        stopButton = Button(context).apply {
            text = "Stop Tracking"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC333333"))
            textSize = 9f
            setOnClickListener {
                val intent = Intent(OverlayService.ACTION_STOP)
                context.sendBroadcast(intent)
            }
        }
        addView(stopButton)
    }

    fun selectObject(objectId: String) {
        selectedObjectId = objectId
        updateTracking(tracker)
    }

    fun deselectObject() {
        selectedObjectId = null
        updateTracking(tracker)
    }

    fun updateTracking(tracker: ObjectTracker) {
        if (selectedObjectId != null) {
            val data = tracker.getObjectData(selectedObjectId!!) ?: return
            val speed = data["speed"].toString()
            val direction = data["direction"].toString()
            val x = data["currentX"].toString().take(5)
            val y = data["currentY"].toString().take(5)

            dataText.text = """${data["direction"]}
                |Speed: $speed px/s
                |Pos: ($x, $y)
                |Dir: $direction""".trimMargin()
        } else {
            dataText.text = """Objects: ${tracker.getActiveObjects().size}
                |Tap object to track""".trimMargin()
        }
    }
}
