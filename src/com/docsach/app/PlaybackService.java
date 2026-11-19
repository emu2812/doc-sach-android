package com.docsach.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service whose only job is to keep this app's process at
 * foreground priority while the user listens to a book, so Android does
 * not kill the WebView (and its audio) when the screen turns off or the
 * user switches to another app. A partial wake lock keeps the CPU awake
 * enough to keep streaming the next chunk. The service shows one quiet
 * ongoing notification; tapping it returns to the app.
 */
public class PlaybackService extends Service {

    private static final String CHANNEL_ID = "docsach_playback";
    private static final int NOTIFICATION_ID = 41;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Nghe sách chạy nền", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Báo ứng dụng đang chạy nền để nghe sách");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        // The channel-id Builder constructor only exists on API 26+;
        // older devices (minSdk 24) use the plain constructor.
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_speaker)
                .setContentTitle("Đọc Sách Bằng Giọng Nói")
                .setContentText("Đang chạy nền — nghe sách tiếp khi tắt màn hình")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "docsach:playback");
            wakeLock.setReferenceCounted(false);
            // Bounded acquire (12 h) so a forgotten service can never
            // hold the CPU forever; onDestroy releases it earlier.
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
