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
    public static final String ACTION_DOWNLOAD = "download";
    public static final String EXTRA_IDS = "ids";

    private static final java.util.ArrayList<String> queue = new java.util.ArrayList<>();
    private static volatile boolean downloadingNow;

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

        if (intent != null && ACTION_DOWNLOAD.equals(intent.getAction())) {
            java.util.ArrayList<String> ids = intent.getStringArrayListExtra(EXTRA_IDS);
            synchronized (queue) { if (ids != null) queue.addAll(ids); }
            if (!downloadingNow) {
                downloadingNow = true;
                new Thread(() -> {
                    PowerManager.WakeLock dl = ((PowerManager) getSystemService(POWER_SERVICE))
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoenkaPlayer:download");
                    dl.acquire(3 * 60 * 60 * 1000L);
                    while (true) {
                        java.util.ArrayList<String> batch;
                        synchronized (queue) {
                            if (queue.isEmpty()) break;
                            batch = new java.util.ArrayList<>(queue);
                            queue.clear();
                        }
                        downloadChosen(batch);
                    }
                    if (dl.isHeld()) dl.release();
                    downloadingNow = false;
                    progress = "";
                    if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
                }).start();
            }
            return START_NOT_STICKY;
        }
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
            if (!downloadingNow) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
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
        int before = Library.driveCount(this);
        java.util.List<Drive.Playlist> lists = Drive.scanTree(id);
        if (lists.isEmpty()) return "Drive: រកមិនឃើញ MP3";
        Library.saveDriveTree(this, link, lists, false);
        int total = Library.driveCount(this);

        PlayerService ps = PlayerService.instance;
        boolean busy = ps != null && (ps.isPlaying() || ps.isInSilence() || ps.isLoading());
        if (!busy) Library.loadDrive(this);   // otherwise the new list is used next time

        // Only the lists are refreshed. Nothing is downloaded automatically.
        int added = Math.max(0, total - before);
        return "Drive: " + lists.size() + " Playlist • " + total + " File" + (added > 0 ? " (ថ្មី " + added + ")" : "");
    }

    /** Downloads the Drive files the user chose (⬇ buttons). */
    private void downloadChosen(java.util.ArrayList<String> ids) {
        int n = 0, total = ids.size(), failed = 0;
        for (String fid : ids) {
            n++;
            if (Drive.isSaved(this, fid)) continue;
            final int k = n;
            try {
                show("ទាញយក " + k + "/" + total + "…");
                Drive.download(this, fid, pct -> show("ទាញយក " + k + "/" + total + " — " + pct + "%"));
            } catch (Exception e) {
                failed++;
            }
        }
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date());
        getSystemService(NotificationManager.class).notify(4, note(
                failed == 0 ? "ទាញយករួចរាល់ (" + total + " File)" : "ទាញយកបរាជ័យ " + failed + " File", false));
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

        // Install by itself (no "Install" tap on Android 12+), unless music is playing now
        PlayerService ps = PlayerService.instance;
        boolean busy = ps != null && (ps.isPlaying() || ps.isInSilence() || ps.isLoading());
        if (!busy) {
            try {
                show("កំពុងដំឡើងកំណែថ្មី " + tag + "…");
                SelfInstaller.install(this, out);
                return "App: កំពុងដំឡើង " + tag + "…";
            } catch (Exception ignored) {
                // fall back to the tap-to-install notification below
            }
        }

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
