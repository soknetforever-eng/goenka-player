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
        Updater.schedule(c);   // always arm the next 2:00 AM
    }
}
