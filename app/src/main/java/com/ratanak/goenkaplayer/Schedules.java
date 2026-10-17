package com.ratanak.goenkaplayer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Timed plays: "at 6:00 on Mon–Fri play lesson X until 7:00", each with its own talk/silence.
 * Without a schedule the player works as before (plays on until you stop it).
 */
public final class Schedules {

    private Schedules() {}

    public static final String KEY_LIST = "schedules";
    public static final String KEY_ACTIVE = "sched_active";      // id of the schedule playing now
    static final String KEY_OVR_CYCLE = "sched_cycle";
    static final String KEY_OVR_TALK = "sched_talk";
    static final String KEY_OVR_SIL = "sched_sil";
    public static final String ACTION_FIRE = "com.ratanak.goenkaplayer.SCHEDULE";
    public static final String EXTRA_FROM_SCHEDULE = "from_schedule";

    private static final long WINDOW = 2 * 60 * 1000L;
    private static final String CHANNEL = "schedule";

    public static final String[] DAY_SHORT = {"ច", "អ", "ព", "ព្រ", "សុ", "ស", "អា"};   // Mon..Sun

    /** One timed play. days: bit 0 = Monday … bit 6 = Sunday. end = -1 means no stop time. */
    public static final class Item {
        public String id = Long.toString(System.currentTimeMillis(), 36);
        public boolean on = true;
        public int start = 6 * 60;
        public int end = 7 * 60;
        public int days = 0;              // 0 = every day
        public String source = "drive";   // "drive" or "local"
        public String playlist = "";      // Drive playlist name
        public String tree = "";          // local folder
        public String file = "";          // uri of the file to start with
        public String fileName = "";
        public boolean cycle = true;
        public int talk = 10;
        public int sil = 10;
        public boolean resume = true;     // next time, continue where it stopped
        public String lastFile = "";
        public long lastPos = 0;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("on", on); o.put("s", start); o.put("e", end); o.put("d", days);
            o.put("src", source); o.put("pl", playlist); o.put("tr", tree); o.put("f", file);
            o.put("fn", fileName); o.put("cy", cycle); o.put("t", talk); o.put("si", sil);
            o.put("rs", resume); o.put("lf", lastFile); o.put("lp", lastPos);
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.id = o.optString("id", it.id);
            it.on = o.optBoolean("on", true);
            it.start = o.optInt("s", it.start);
            it.end = o.optInt("e", it.end);
            it.days = o.optInt("d", 0);
            it.source = o.optString("src", "drive");
            it.playlist = o.optString("pl", "");
            it.tree = o.optString("tr", "");
            it.file = o.optString("f", "");
            it.fileName = o.optString("fn", "");
            it.cycle = o.optBoolean("cy", true);
            it.talk = Math.max(1, o.optInt("t", 10));
            it.sil = Math.max(1, o.optInt("si", 10));
            it.resume = o.optBoolean("rs", true);
            it.lastFile = o.optString("lf", "");
            it.lastPos = o.optLong("lp", 0);
            return it;
        }

        public String timeText() {
            return hhmm(start) + " → " + (end < 0 ? "ចាក់រហូត" : hhmm(end));
        }

        public String daysText() {
            if (days == 0 || days == 0x7F) return "រៀងរាល់ថ្ងៃ";
            if (days == 0x1F) return "ច័ន្ទ–សុក្រ";
            if (days == 0x60) return "សៅរ៍–អាទិត្យ";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if ((days & (1 << i)) != 0) {
                    if (b.length() > 0) b.append(" ");
                    b.append(DAY_SHORT[i]);
                }
            }
            return b.length() == 0 ? "គ្មានថ្ងៃ" : b.toString();
        }
    }

    public static String hhmm(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // ---------------- storage ----------------

    public static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Library.prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveAll(Context c, List<Item> list) {
        JSONArray a = new JSONArray();
        try { for (Item it : list) a.put(it.toJson()); } catch (Exception ignored) {}
        Library.prefs(c).edit().putString(KEY_LIST, a.toString()).apply();
        arm(c);
    }

    public static void put(Context c, Item item) {
        List<Item> list = all(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(item.id)) { list.set(i, item); found = true; }
        }
        if (!found) list.add(item);
        saveAll(c, list);
    }

    public static void remove(Context c, String id) {
        List<Item> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        if (id.equals(activeId(c))) clearActive(c);
        saveAll(c, list);
    }

    // ---------------- the schedule playing now ----------------

    public static String activeId(Context c) { return Library.prefs(c).getString(KEY_ACTIVE, ""); }

    public static boolean overrideOn(Context c) { return !activeId(c).isEmpty(); }

    public static void clearActive(Context c) {
        Library.prefs(c).edit().remove(KEY_ACTIVE).apply();
    }

    public static Item active(Context c) {
        String id = activeId(c);
        if (id.isEmpty()) return null;
        for (Item it : all(c)) if (it.id.equals(id)) return it;
        return null;
    }

    // ---------------- alarm ----------------

    private static PendingIntent fireIntent(Context c) {
        Intent i = new Intent(c, Receiver.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 60, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Sets one alarm for the next start or stop of any schedule. */
    public static void arm(Context c) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Item it : all(c)) {
            if (!it.on) continue;
            for (long[] ev : events(it, now)) {
                if (ev[0] > now + WINDOW && ev[0] < next) next = ev[0];
            }
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = fireIntent(c);
        if (next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        try {
            PendingIntent show = PendingIntent.getActivity(c, 61, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(next, show), pi);
        } catch (Exception e) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi); } catch (Exception ignored) {}
        }
    }

    /** Next start/stop times around now: {time, 1 = start / 0 = stop}. */
    private static List<long[]> events(Item it, long now) {
        List<long[]> out = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.add(Calendar.DAY_OF_MONTH, -1);
        for (int d = 0; d < 9; d++) {
            int bit = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0 … Sunday = 6
            int days = it.days == 0 ? 0x7F : it.days;   // nothing picked = every day
            if ((days & (1 << bit)) != 0) {
                Calendar s = (Calendar) cal.clone();
                s.set(Calendar.HOUR_OF_DAY, it.start / 60);
                s.set(Calendar.MINUTE, it.start % 60);
                s.set(Calendar.SECOND, 0);
                s.set(Calendar.MILLISECOND, 0);
                long st = s.getTimeInMillis();
                out.add(new long[]{st, 1});
                if (it.end >= 0) {
                    int dur = ((it.end - it.start) % 1440 + 1440) % 1440;
                    if (dur == 0) dur = 1440;
                    out.add(new long[]{st + dur * 60_000L, 0});
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    // ---------------- start / stop ----------------

    /** Loads the schedule's list and starts its file. Returns an error text, or null when OK. */
    public static String start(Context c, Item it) {
        if ("drive".equals(it.source)) {
            List<String> names = Library.playlistNames(c);
            int sel = names.indexOf(it.playlist);
            if (sel < 0) sel = Library.selectedPlaylist(c);
            Library.prefs(c).edit().putString(Library.KEY_SOURCE, "drive").putInt(Library.KEY_PLAYLIST, sel).apply();
            Library.loadDrive(c);
        } else {
            if (it.tree.isEmpty()) return "រកមិនឃើញ Folder";
            Library.prefs(c).edit().putString(Library.KEY_SOURCE, "local").putString(Library.KEY_TREE, it.tree).apply();
            Library.load(c, Uri.parse(it.tree));
        }
        if (Library.uris.isEmpty()) return "បញ្ជីទទេ";
        int idx = 0;
        long pos = 0;
        boolean found = false;
        if (it.resume && !it.lastFile.isEmpty()) {
            for (int i = 0; i < Library.uris.size(); i++) {
                if (Library.uris.get(i).toString().equals(it.lastFile)) {
                    idx = i;
                    pos = Math.max(0, it.lastPos - 5000);   // replay the last 5 seconds
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            for (int i = 0; i < Library.uris.size(); i++) {
                if (Library.uris.get(i).toString().equals(it.file)) { idx = i; break; }
            }
        }
        Library.prefs(c).edit()
                .putString(KEY_ACTIVE, it.id)
                .putBoolean(KEY_OVR_CYCLE, it.cycle)
                .putInt(KEY_OVR_TALK, it.talk)
                .putInt(KEY_OVR_SIL, it.sil)
                .apply();
        Intent i = new Intent(c, PlayerService.class)
                .setAction(PlayerService.ACTION_PLAY_INDEX)
                .putExtra(PlayerService.EXTRA_INDEX, idx)
                .putExtra(PlayerService.EXTRA_POS, pos)
                .putExtra(EXTRA_FROM_SCHEDULE, true);
        c.startForegroundService(i);
        return null;
    }

    /** Remembers where the running schedule is now, so it can continue there next time. */
    public static void saveProgress(Context c) {
        try {
            Item a = active(c);
            PlayerService ps = PlayerService.instance;
            if (a == null || !a.resume || ps == null) return;
            int i = ps.index();
            if (i < 0 || i >= Library.uris.size()) return;
            a.lastFile = Library.uris.get(i).toString();
            a.lastPos = Math.max(0, ps.position());
            List<Item> list = all(c);
            for (int k = 0; k < list.size(); k++) if (list.get(k).id.equals(a.id)) list.set(k, a);
            JSONArray arr = new JSONArray();
            for (Item x : list) arr.put(x.toJson());
            Library.prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    static void stop(Context c) {
        saveProgress(c);
        clearActive(c);
        if (PlayerService.instance != null) {
            c.startService(new Intent(c, PlayerService.class).setAction(PlayerService.ACTION_STOP));
        }
    }

    static void note(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "កាលវិភាគ", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(c, 62, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        nm.notify(8, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("⏰ កាលវិភាគ")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    /** Fires at a schedule's start or stop time. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            long now = System.currentTimeMillis();
            List<Item> list = all(c);
            String active = activeId(c);
            // stops first, so a stop and a start at the same minute hand over cleanly
            for (Item it : list) {
                if (!it.on || !it.id.equals(active)) continue;
                for (long[] ev : events(it, now)) {
                    if (ev[1] == 0 && Math.abs(ev[0] - now) <= WINDOW) {
                        stop(c);
                        note(c, "⏹ បានបិទតាមម៉ោង " + hhmm(it.end));
                        active = "";
                        break;
                    }
                }
            }
            for (Item it : list) {
                if (!it.on) continue;
                boolean due = false;
                for (long[] ev : events(it, now)) {
                    if (ev[1] == 1 && Math.abs(ev[0] - now) <= WINDOW) { due = true; break; }
                }
                if (!due) continue;
                try {
                    String err = start(c, it);
                    if (err != null) note(c, "⚠️ ចាប់ផ្ដើមមិនបាន: " + err);
                } catch (Exception e) {
                    note(c, "⚠️ ចាប់ផ្ដើមមិនបាន");
                }
                break;   // only one schedule can play at a time
            }
            arm(c);
        }
    }
}
