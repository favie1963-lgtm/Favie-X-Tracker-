package com.favie.xtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Keeps the process alive while a screen capture is active.
 *
 * Android 14 (API 34) refuses to start a MediaProjection unless a foreground
 * service of type `mediaProjection` is already running, so the plugin starts
 * this service before it touches the projection and stops it on teardown. The
 * service owns no capture logic itself; its only job is to hold the process at
 * foreground priority so the VirtualDisplay keeps producing frames while the
 * app is backgrounded.
 */
public class ScreenCaptureService extends Service {

    public static final String EXTRA_STOP = "com.favie.xtracker.STOP";

    /**
     * Broadcast sent once {@link #onStartCommand} has called
     * {@code startForeground}. The capture plugin waits for this before creating
     * the projection, because Android 14+ rejects a projection that is started
     * while no foreground service of type mediaProjection is running yet.
     */
    public static final String ACTION_READY = "com.favie.xtracker.CAPTURE_READY";

    private static final String CHANNEL_ID = "favie-screen-capture";
    private static final int NOTIFICATION_ID = 4201;

    private static volatile boolean running = false;

    /** Whether the service has reached the foreground on this process. */
    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_STOP, false)) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.capture_notification_text))
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        running = true;
        // The plugin must not create the projection until this point, so it waits
        // on this broadcast instead of assuming startForegroundService has taken
        // effect by the time it returns.
        sendBroadcast(new Intent(ACTION_READY).setPackage(getPackageName()));

        // Restarted after being killed, the capture state is gone, so a fresh
        // start from the UI is the only way back. Do not resurrect the service.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.capture_channel_description));
        manager.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}