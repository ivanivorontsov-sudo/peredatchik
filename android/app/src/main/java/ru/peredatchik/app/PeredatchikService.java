package ru.peredatchik.app;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Держит приёмник включённым в фоне. */
public class PeredatchikService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Hub h = Hub.get(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, Hub.CH_STATUS).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Передатчик готов к приёму").setContentText("«" + h.me.name + "»")
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        h.start();
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
