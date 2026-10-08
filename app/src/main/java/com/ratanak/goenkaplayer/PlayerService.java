package com.ratanak.goenkaplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Background player. Runs as a foreground service with a partial wake lock, so playback
 * continues with the screen off and through long silent sections.
 */
public class PlayerService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_PLAY_INDEX = "play_index";
    public static final String ACTION_TOGGLE = "toggle";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_PREV = "prev";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_POS = "pos";

    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    public static volatile PlayerService instance;

    private MediaPlayer mp;
    private boolean prepared;
    private int index = -1;
    private boolean resumeOnFocus;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int tick;
    private KeepAlive keepAlive;

    /** Every second: keep the quiet "keep-alive" sound in step with playback; save position every 5 s. */
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            if (isPlaying()) startKeepAlive(); else stopKeepAlive();
            if (++tick % 5 == 0) savePosition();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * Plays an extremely quiet hiss (about -70 dB, not audible) while a lesson is playing.
     * Some phones freeze a player when its output is pure digital silence for a while;
     * this keeps real audio flowing so playback continues through the 10-minute silences.
     */
    private static final class KeepAlive extends Thread {
        private volatile boolean running = true;

        @Override public void run() {
            int sr = 22050;
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sr)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(min, sr))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
                short[] buf = new short[sr / 10];
                java.util.Random rnd = new java.util.Random();
                while (running) {
                    for (int i = 0; i < buf.length; i++) buf[i] = (short) (rnd.nextInt(25) - 12);
                    track.write(buf, 0, buf.length);
                }
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) {}
                    track.release();
                }
            }
        }

        void finish() { running = false; }
    }

    private void startKeepAlive() {
        if (keepAlive == null) {
            keepAlive = new KeepAlive();
            keepAlive.start();
        }
    }

    private void stopKeepAlive() {
        if (keepAlive != null) {
            keepAlive.finish();
            keepAlive = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        handler.postDelayed(saver, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must call startForeground right away.
        goForeground();
        Library.ensureLoaded(this);
        String action = intent != null ? intent.getAction() : null;
        if (action == null) action = "";
        switch (action) {
            case ACTION_PLAY_INDEX:
                playIndex(intent.getIntExtra(EXTRA_INDEX, 0), intent.getLongExtra(EXTRA_POS, 0));
                break;
            case ACTION_TOGGLE:
                toggle();
                break;
            case ACTION_NEXT:
                next();
                break;
            case ACTION_PREV:
                prev();
                break;
            case ACTION_STOP:
                stopAll();
                return START_NOT_STICKY;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    // ---------- playback ----------

    public void playIndex(int i, long startMs) {
        if (i < 0 || i >= Library.uris.size()) return;
        releasePlayer();
        index = i;
        prepared = false;
        Library.prefs(this).edit().putInt(Library.KEY_INDEX, i).putLong(Library.KEY_POS, startMs).apply();
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        final long seek = startMs;
        mp.setOnPreparedListener(p -> {
            prepared = true;
            if (seek > 0 && seek < p.getDuration()) p.seekTo((int) seek);
            if (requestFocus()) { p.start(); startKeepAlive(); }
            updateNotification();
        });
        mp.setOnCompletionListener(p -> {
            if (index + 1 < Library.uris.size()) {
                playIndex(index + 1, 0);
            } else {
                Library.prefs(this).edit().putLong(Library.KEY_POS, 0).apply();
                updateNotification();
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            prepared = false;
            updateNotification();
            return true;
        });
        try {
            mp.setDataSource(this, Library.uris.get(i));
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
        }
        updateNotification();
    }

    public void toggle() {
        if (mp == null || !prepared) {
            // Nothing loaded yet: resume where we stopped last time.
            int i = Library.prefs(this).getInt(Library.KEY_INDEX, 0);
            long pos = Library.prefs(this).getLong(Library.KEY_POS, 0);
            if (i >= Library.uris.size()) i = 0;
            playIndex(i, pos);
            return;
        }
        if (mp.isPlaying()) {
            mp.pause();
            resumeOnFocus = false;
            savePosition();
        } else if (requestFocus()) {
            mp.start();
        }
        updateNotification();
    }

    public void next() {
        if (index + 1 < Library.uris.size()) playIndex(index + 1, 0);
    }

    public void prev() {
        if (mp != null && prepared && mp.getCurrentPosition() > 5000) {
            mp.seekTo(0);
        } else if (index > 0) {
            playIndex(index - 1, 0);
        }
    }

    public void seekTo(long ms) {
        if (mp != null && prepared) mp.seekTo((int) ms);
        savePosition();
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public long position() {
        try { return (mp != null && prepared) ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public long duration() {
        try { return (mp != null && prepared) ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    public int index() { return index; }

    private void savePosition() {
        if (mp != null && prepared && index >= 0) {
            Library.prefs(this).edit()
                    .putInt(Library.KEY_INDEX, index)
                    .putLong(Library.KEY_POS, position())
                    .apply();
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        prepared = false;
    }

    private void stopAll() {
        stopKeepAlive();
        savePosition();
        releasePlayer();
        abandonFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------- audio focus ----------

    private boolean requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(this)
                    .setWillPauseWhenDucked(false)
                    .build();
        }
        return am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (mp == null || !prepared) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app started playing (or a call): pause.
                if (mp.isPlaying()) mp.pause();
                resumeOnFocus = false;
                savePosition();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (mp.isPlaying()) { mp.pause(); resumeOnFocus = true; }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mp.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mp.setVolume(1f, 1f);
                if (resumeOnFocus) { mp.start(); resumeOnFocus = false; }
                break;
            default:
                break;
        }
        updateNotification();
    }

    // ---------- notification ----------

    private PendingIntent action(String a, int req) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        String title = (index >= 0 && index < Library.names.size()) ? Library.names.get(index) : "Goenka Player";
        boolean playing = isPlaying();
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(playing ? "កំពុងលេង" : "ផ្អាក")
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Prev", action(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing ? "Pause" : "Play", action(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", action(ACTION_NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", action(ACTION_STOP, 4)).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopKeepAlive();
        savePosition();
        releasePlayer();
        abandonFocus();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
