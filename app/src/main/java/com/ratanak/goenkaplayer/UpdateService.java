package com.ratanak.goenkaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runs the update: refresh the Google Drive folder (download new files) and look for a new
 * app version. Started at 2:00 AM by UpdateReceiver, or by the "Update now" button.
 */
public class UpdateService extends Service {

    public static final String ACTION_NIGHTLY = "nightly";
    public static final String ACTION_MANUAL = "manual";

    private static final String CHANNEL = "updates";
    private static final int NOTIF_WORK = 2;
    private static final int NOTIF_APP = 3;

    public static volatile boolean running;
    public static volatile String progress = "";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification note(String text, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 30,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Goenka Player — Update")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void show(String text) {
        progress = text;
        getSystemService(NotificationManager.class).notify(NOTIF_WORK, note(text, true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = note("កំពុងឆែក Update…", true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_WORK, n);

        if (running) return START_NOT_STICKY;
        running = true;
        final boolean manual = intent != null && ACTION_MANUAL.equals(intent.getAction());
        new Thread(() -> {
            PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoenkaPlayer:update");
            wl.acquire(2 * 60 * 60 * 1000L);
            String driveMsg = "", appMsg = "";
            try {
                driveMsg = updateDrive(manual);
            } catch (Exception e) {
                driveMsg = "Drive: មានបញ្ហា (" + e.getMessage() + ")";
            }
            try {
                appMsg = updateApp();
            } catch (Exception e) {
                appMsg = "repo-private".equals(e.getMessage())
                        ? "App: មិនអាចឆែកបាន (Repo ជា Private)"
                        : "App: ឆែកមិនបាន (Internet?)";
            }
            String when = new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date());
            String msg = (driveMsg.isEmpty() ? "" : driveMsg + "\n") + appMsg;
            Library.prefs(this).edit()
                    .putString(Updater.KEY_LAST_CHECK, when)
                    .putString(Updater.KEY_LAST_MSG, msg)
                    .apply();
            progress = "";
            running = false;
            if (wl.isHeld()) wl.release();
            Updater.schedule(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }).start();
        return START_NOT_STICKY;
    }

    // ---------------- Google Drive ----------------

    private String updateDrive(boolean manual) throws Exception {
        if (!"drive".equals(Library.prefs(this).getString(Library.KEY_SOURCE, "local"))) return "";
        String link = Library.prefs(this).getString(Library.KEY_DRIVE_LINK, null);
        String id = Drive.folderId(link);
        if (id == null) return "";

        show("កំពុងឆែក Folder Google Drive…");
        Drive.Listing l = Drive.list(id);
        int before = Library.driveCount(this);
        Library.saveDriveListOnly(this, l);
        int added = Math.max(0, l.ids.size() - before);

        PlayerService ps = PlayerService.instance;
        boolean busy = ps != null && (ps.isPlaying() || ps.isInSilence() || ps.isDownloading());
        if (!busy) Library.loadDrive(this);   // otherwise the new list is used next time

        // download files that are not saved yet (nightly: only on Wi-Fi)
        if (!manual && !Updater.onWifi(this)) {
            return "Drive: " + l.ids.size() + " File (File ថ្មី " + added + ") — រង់ចាំ Wi-Fi ដើម្បីទាញយក";
        }
        int got = 0, failed = 0, total = l.ids.size();
        for (int i = 0; i < total; i++) {
            String fid = l.ids.get(i);
            if (Drive.isSaved(this, fid)) continue;
            final int n = i + 1;
            try {
                show("ទាញយក " + n + "/" + total + "…");
                Drive.download(this, fid, pct -> show("ទាញយក " + n + "/" + total + " — " + pct + "%"));
                got++;
            } catch (Exception e) {
                failed++;
            }
        }
        return "Drive: " + total + " File • File ថ្មី " + added + " • បានទាញយក " + got
                + (failed > 0 ? " • បរាជ័យ " + failed : "");
    }

    // ---------------- App update ----------------

    private String updateApp() throws Exception {
        show("កំពុងឆែកកំណែកម្មវិធីថ្មី…");
        String[] found = Updater.findNewerApp(this);
        String cur = Updater.currentVersionName(this);
        if (found == null) return "App: ថ្មីបំផុតហើយ (v" + cur + ")";

        String tag = found[0];
        File out = ApkProvider.file(this);
        File part = new File(out.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(found[1]).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GoenkaPlayer");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        // GitHub sends the file from another address; follow up to 5 hops by hand
        for (int hop = 0; hop < 5; hop++) {
            int code = c.getResponseCode();
            if (code / 100 != 3) break;
            String loc = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(loc).openConnection();
            c.setRequestProperty("User-Agent", "GoenkaPlayer");
        }
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        if (out.exists()) out.delete();
        if (!part.renameTo(out)) throw new Exception("save");

        Library.prefs(this).edit()
                .putString(Updater.KEY_PENDING_TAG, tag)
                .putString(Updater.KEY_PENDING_APK, ApkProvider.uri().toString())
                .apply();

        // notification: tap to install
        PendingIntent install = PendingIntent.getActivity(this, 31, ApkProvider.installIntent(),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Goenka Player កំណែថ្មី " + tag)
                .setContentText("ចុចទីនេះដើម្បីដំឡើង")
                .setContentIntent(install)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_APP, n);
        return "App: មានកំណែថ្មី " + tag + " — ចុចដើម្បីដំឡើង";
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
