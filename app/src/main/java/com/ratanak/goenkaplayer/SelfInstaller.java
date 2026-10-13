package com.ratanak.goenkaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs the downloaded update through PackageInstaller. On Android 12+ an app that
 * updates itself (and was last installed by itself) needs no "Install" tap; the very first
 * time, or on older phones, Android still shows its confirm screen once.
 */
public final class SelfInstaller {

    private SelfInstaller() {}

    public static final String ACTION_RESULT = "com.ratanak.goenkaplayer.INSTALL_RESULT";
    private static final String CHANNEL = "updates";
    private static final int NOTIF = 5;

    /** True while the main screen is visible (set by MainActivity). */
    public static volatile boolean uiVisible;

    /** Must run off the main thread. */
    public static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, Result.class).setAction(ACTION_RESULT);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pend = PendingIntent.getBroadcast(c, 40, i, fl);
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

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

    /** Receives the install result from Android. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (uiVisible) {
                    try { c.startActivity(confirm); return; } catch (Exception ignored) {}
                }
                PendingIntent tap = PendingIntent.getActivity(c, 41, confirm,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                post(c, "Goenka Player កំណែថ្មី", "ចុចទីនេះដើម្បីដំឡើង (តែម្ដងនេះ)", tap);
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Library.prefs(c).edit()
                        .putString(Updater.KEY_LAST_MSG, "App: ដំឡើងមិនបាន (" + (msg == null ? status : msg) + ")")
                        .apply();
                PendingIntent open = PendingIntent.getActivity(c, 42, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE);
                post(c, "Goenka Player", "ដំឡើងកំណែថ្មីមិនបាន — បើក App ដើម្បីសាកម្ដងទៀត", open);
            }
            // STATUS_SUCCESS: Android restarts the app; UpdateReceiver shows "updated".
        }
    }
}
