package com.example.rspeech;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class AudioService extends Service {

    private static final String CHANNEL_ID = "rspeech_audio_channel";
    private static final int NOTIFICATION_ID = 1;

    public class AudioBinder extends android.os.Binder {
        public AudioNetworkManager getManager() {
            return manager;
        }
    }

    private final AudioBinder binder = new AudioBinder();
    private AudioNetworkManager manager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        manager = new AudioNetworkManager(this, null);
        promoteToForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void promoteToForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("RSpeech en segundo plano (micrófono + audio)")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "RSpeech audio",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantiene micro y parlante activos con pantalla bloqueada");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (manager != null) {
            manager.stop();
        }
        super.onDestroy();
    }
}