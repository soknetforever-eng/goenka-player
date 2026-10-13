package com.ratanak.goenkaplayer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/** Nightly (2:00 AM) and on-demand updates: new Drive files + new app versions. */
public final class Updater {

    private Updater() {}

    public static final String GITHUB_REPO = "soknetforever-eng/goenka-player";

    public static final String KEY_AUTO = "auto_update";
    public static final String KEY_LAST_CHECK = "last_check";
    public static final String KEY_LAST_MSG = "last_check_msg";
    public static final String KEY_PENDING_APK = "pending_apk_uri";
    public static final String KEY_PENDING_TAG = "pending_apk_tag";
    public static final String KEY_RUNNING = "update_running";

    public static boolean autoEnabled(Context c) {
        return Library.prefs(c).getBoolean(KEY_AUTO, true);
    }

    // ---------------- schedule ----------------

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, UpdateReceiver.class).setAction(UpdateReceiver.ACTION_NIGHTLY);
        return PendingIntent.getBroadcast(c, 21, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Sets (or clears) the alarm for the next 2:00 AM. */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(c);
        am.cancel(pi);
        if (!autoEnabled(c)) return;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 2);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1);
        long at = cal.getTimeInMillis();
        try {
            boolean exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    // ---------------- helpers ----------------

    public static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Exception e) {
            return false;
        }
    }

    public static long currentVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Newer release on GitHub, or null. [0]=tag, [1]=apk url */
    public static String[] findNewerApp(Context c) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest").openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "GoenkaPlayer");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code == 404) throw new Exception("repo-private");
        if (code != 200) throw new Exception("HTTP " + code);
        String body;
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            body = bo.toString("UTF-8");
        }
        JSONObject j = new JSONObject(body);
        String tag = j.optString("tag_name", "");
        long remote = 0;
        int dot = tag.lastIndexOf('.');
        try { remote = Long.parseLong(tag.substring(dot + 1)); } catch (Exception ignored) {}
        if (remote <= currentVersion(c)) return null;
        JSONArray assets = j.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                return new String[]{tag, a.optString("browser_download_url")};
            }
        }
        return null;
    }
}
