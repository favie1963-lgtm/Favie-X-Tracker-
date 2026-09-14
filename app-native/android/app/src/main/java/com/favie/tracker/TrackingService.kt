package com.favie.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class TrackingService : Service() {
    private val TAG = "TrackingService"
    private var handler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false
    private val scope = CoroutineScope(Dispatchers.Default)
    private var captureInterval = 500L // milliseconds

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        if (intent?.action == ACTION_START_CAPTURE) {
            captureInterval = intent.getLongExtra("interval", 500L)
            startCapture()
        } else if (intent?.action == ACTION_STOP_CAPTURE) {
            stopCapture()
        }
        
        startForeground(2, createServiceNotification())
        return START_STICKY
    }

    private fun startCapture() {
        if (isCapturing) return
        isCapturing = true
        
        Log.d(TAG, "Starting capture loop")
        scope.launch {
            while (isCapturing) {
                try {
                    captureFrame()
                    delay(captureInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture error: ${e.message}")
                }
            }
        }
    }

    private fun stopCapture() {
        isCapturing = false
        imageReader?.close()
        imageReader = null
        Log.d(TAG, "Capture stopped")
    }

    private suspend fun captureFrame() {
        return withContext(Dispatchers.Default) {
            try {
                val display = displayManager?.getDisplay(0)
                if (display != null) {
                    val width = display.width
                    val height = display.height

                    if (imageReader == null) {
                        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    }

                    // Simulate frame capture (actual screen capture requires higher permissions)
                    val bitmap = createDummyFrame(width, height)
                    saveBitmap(bitmap)
                    
                    // Notify overlay of activity
                    val intent = Intent(ACTION_FRAME_CAPTURED)
                    sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in captureFrame: ${e.message}")
            }
        }
    }

    private fun createDummyFrame(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        return bitmap
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val cacheDir = cacheDir
        val fileName = "frame_${System.currentTimeMillis()}.jpg"
        val file = File(cacheDir, fileName)

        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            // Keep only last 10 frames
            cacheDir.listFiles()?.filter { it.name.startsWith("frame_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(10)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Favie Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Favie X Tracker")
            .setContentText("Screen tracking service running...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "favie_tracking_service"
        const val ACTION_START_CAPTURE = "com.favie.tracker.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.favie.tracker.STOP_CAPTURE"
        const val ACTION_FRAME_CAPTURED = "com.favie.tracker.FRAME_CAPTURED"
    }
}
