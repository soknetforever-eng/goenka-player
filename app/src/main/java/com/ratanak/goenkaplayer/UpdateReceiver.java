package com.ratanak.goenkaplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 2:00 AM alarm, and re-arming the alarm after the phone restarts or the app is updated. */
public class UpdateReceiver extends BroadcastReceiver {

    public static final String ACTION_NIGHTLY = "com.ratanak.goenkaplayer.NIGHTLY_UPDATE";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (ACTION_NIGHTLY.equals(a)) {
            try {
                c.startForegroundService(new Intent(c, UpdateService.class)
                        .setAction(UpdateService.ACTION_NIGHTLY));
            } catch (Exception ignored) {
            }
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Library.prefs(c).edit().remove(Updater.KEY_PENDING_TAG).remove(Updater.KEY_PENDING_APK)
                    .putString(Updater.KEY_LAST_MSG, "App: បាន Update ទៅ v" + Updater.currentVersionName(c) + " ហើយ")
                    .apply();
            ApkProvider.file(c).delete();
            android.app.PendingIntent open = android.app.PendingIntent.getActivity(c, 43,
                    new Intent(c, MainActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE);
            SelfInstaller.post(c, "Goenka Player", "✅ បាន Update ទៅ v" + Updater.currentVersionName(c) + " ហើយ", open);
        }
        Updater.schedule(c);   // always arm the next 2:00 AM
        Schedules.arm(c);      // and the next timed play
    }
}
