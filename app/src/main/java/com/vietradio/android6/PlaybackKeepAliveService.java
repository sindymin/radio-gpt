package com.vietradio.android6;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Foreground service that keeps the app alive in the background while a station or recording is playing. */
public class PlaybackKeepAliveService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }

    static final String ACTION_STOP = "com.vietradio.android6.STOP_PLAYBACK";

    /** The user swiped the app away from the recent-apps list: stop playback only. Recording schedules/alarms and a running recording are left alone. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        RadioPlayer.saveLocalResumePosition(this);
        RadioPlayer.stop();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // "Dừng" button on the notification.
            RadioPlayer.saveLocalResumePosition(this);
            RadioPlayer.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        String name = intent == null ? null : intent.getStringExtra("name");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("radio_play", "Đang phát radio", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, PlaybackKeepAliveService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "radio_play") : new Notification.Builder(this);
        b.setContentTitle("Radio Việt Nam")
            .setContentText(name == null || name.isEmpty() ? "Đang phát" : "Đang phát: " + name)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Dừng", stopPi);
        startForeground(42, b.build());
        return START_NOT_STICKY;
    }
}
