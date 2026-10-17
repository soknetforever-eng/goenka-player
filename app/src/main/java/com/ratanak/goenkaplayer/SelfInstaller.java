package com.ratanak.goenkaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

/** Small notification helper (the app no longer installs updates by itself). */
public final class SelfInstaller {

    private SelfInstaller() {}

    private static final String CHANNEL = "updates";
    private static final int NOTIF = 5;

    static void post(Context c, String title, String text, PendingIntent tap) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        nm.notify(NOTIF, b.build());
    }
}
