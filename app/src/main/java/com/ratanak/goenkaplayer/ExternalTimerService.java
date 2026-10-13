package com.ratanak.goenkaplayer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;

import java.util.List;

/**
 * Talk/silence timer for OTHER apps (YouTube, Spotify, podcasts…): pauses the app that is
 * playing after N minutes of playback, and presses play again after M minutes of silence,
 * using Android's standard media controls. Works with the screen off.
 */
public class ExternalTimerService extends Service {

    public static final String ACTION_START = "ext_start";
    public static final String ACTION_STOP = "ext_stop";
    public static final String ACTION_RESUME = "ext_resume";

    private static final String CHANNEL = "ext_timer";
    private static final int NOTIF = 7;

    public static volatile ExternalTimerService instance;

    /** Needed only so Android lets us see other apps' media controls. */
    public static class Listener extends NotificationListenerService {}

    // ---------------- helpers usable from the UI ----------------

    public static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    public static Intent accessSettings() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    /** The other app's media session that is playing now (or the most recent one). */
    public static MediaController findController(Context c, String preferPkg) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, Listener.class));
            MediaController first = null;
            for (MediaController mc : list) {
                if (c.getPackageName().equals(mc.getPackageName())) continue;
                if (preferPkg != null && preferPkg.equals(mc.getPackageName())) return mc;
                if (first == null) first = mc;
                if (preferPkg == null && isPlaying(mc)) return mc;
            }
            return preferPkg == null ? first : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPlaying(MediaController mc) {
        PlaybackState st = mc == null ? null : mc.getPlaybackState();
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING
                || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    public static String appName(Context c, String pkg) {
        if (pkg == null) return "?";
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    public static String mediaTitle(MediaController mc) {
        try {
            MediaMetadata md = mc.getMetadata();
            if (md == null) return "";
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- state ----------------

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wl;
    private String pkg;
    private String label = "";
    private volatile boolean inSilence;
    private long talkedMs;
    private long lastTick;
    private long silenceEndsAt;   // elapsedRealtime
    private int noteTick;

    public String label() { return label; }
    public boolean isInSilence() { return inSilence; }
    public long talkRemaining() { return Math.max(0, talkMs() - talkedMs); }
    public long silenceRemaining() { return Math.max(0, silenceEndsAt - SystemClock.elapsedRealtime()); }
    public boolean targetPlaying() { return isPlaying(findController(this, pkg)); }

    private long talkMs() { return Library.talkMinutes(this) * 60_000L; }
    private long silenceMs() { return Library.silenceMinutes(this) * 60_000L; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Timer App ផ្សេង", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        Notification n = note("⏱ Timer App ផ្សេង");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF, n);

        if (ACTION_STOP.equals(a)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(a)) {
            if (instance == null) { stopAll(); return START_NOT_STICKY; }
            endSilence();
            return START_STICKY;
        }
        // start
        MediaController mc = findController(this, null);
        if (mc == null) {
            stopAll();
            return START_NOT_STICKY;
        }
        pkg = mc.getPackageName();
        label = appName(this, pkg);
        talkedMs = 0;
        inSilence = false;
        lastTick = SystemClock.elapsedRealtime();
        instance = this;
        if (wl == null) {
            wl = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoenkaPlayer:extTimer");
            wl.setReferenceCounted(false);
        }
        wl.acquire(12 * 60 * 60 * 1000L);
        if (!isPlaying(mc)) {
            try { mc.getTransportControls().play(); } catch (Exception ignored) {}
        }
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (instance == null) return;
            long now = SystemClock.elapsedRealtime();
            long dt = now - lastTick;
            lastTick = now;
            MediaController mc = findController(ExternalTimerService.this, pkg);
            boolean playing = isPlaying(mc);
            if (!inSilence) {
                if (playing) talkedMs += dt;
                if (talkedMs >= talkMs()) startSilence(mc);
            } else {
                if (playing) {
                    // the user pressed play by hand during the silence: start a new talk period
                    inSilence = false;
                    talkedMs = 0;
                    cancelAlarm();
                } else if (now >= silenceEndsAt) {
                    endSilence();
                }
            }
            if (++noteTick % 5 == 0) updateNote();
            h.postDelayed(this, 1000);
        }
    };

    private void startSilence(MediaController mc) {
        boolean sent = false;
        if (mc != null) {
            try { mc.getTransportControls().pause(); sent = true; } catch (Exception ignored) {}
        }
        if (!sent) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        inSilence = true;
        talkedMs = 0;
        silenceEndsAt = SystemClock.elapsedRealtime() + silenceMs();
        scheduleAlarm(System.currentTimeMillis() + silenceMs());
        updateNote();
    }

    private void endSilence() {
        cancelAlarm();
        inSilence = false;
        talkedMs = 0;
        lastTick = SystemClock.elapsedRealtime();
        MediaController mc = findController(this, pkg);
        boolean sent = false;
        if (mc != null) {
            try { mc.getTransportControls().play(); sent = true; } catch (Exception ignored) {}
        }
        if (!sent) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        updateNote();
    }

    private void mediaKey(int code) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Exception ignored) {}
    }

    // ---------------- alarm (wakes the phone to resume after the silence) ----------------

    private PendingIntent resumeIntent() {
        Intent i = new Intent(this, ExternalTimerService.class).setAction(ACTION_RESUME);
        return PendingIntent.getForegroundService(this, 50, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void scheduleAlarm(long atWallMs) {
        try {
            AlarmManager am = getSystemService(AlarmManager.class);
            PendingIntent show = PendingIntent.getActivity(this, 51, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(atWallMs, show), resumeIntent());
        } catch (Exception ignored) {
            // the 1-second ticker (kept alive by the wake lock) still resumes on time
        }
    }

    private void cancelAlarm() {
        try { getSystemService(AlarmManager.class).cancel(resumeIntent()); } catch (Exception ignored) {}
    }

    // ---------------- notification ----------------

    private Notification note(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 52, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 53,
                new Intent(this, ExternalTimerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("⏱ Timer: " + (label.isEmpty() ? "App ផ្សេង" : label))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "⏹ បញ្ឈប់ Timer", stop).build())
                .build();
    }

    public String statusText() {
        if (inSilence) return "🔇 ស្ងាត់ — ចាក់វិញក្នុង " + Library.time(silenceRemaining());
        return "🔊 ចាក់ — ស្ងាត់ក្នុង " + Library.time(talkRemaining());
    }

    private void updateNote() {
        if (instance == null) return;
        getSystemService(NotificationManager.class).notify(NOTIF, note(statusText()));
    }

    private void stopAll() {
        instance = null;
        h.removeCallbacks(tick);
        cancelAlarm();
        if (wl != null && wl.isHeld()) wl.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacks(tick);
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
