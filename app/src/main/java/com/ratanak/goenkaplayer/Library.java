package com.ratanak.goenkaplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared playlist + saved state (used by both the screen and the background player). */
public final class Library {

    public static final String PREFS = "goenka_player";
    public static final String KEY_TREE = "tree_uri";
    public static final String KEY_INDEX = "last_index";
    public static final String KEY_POS = "last_pos";
    public static final String KEY_CYCLE = "cycle_10_10";
    public static final String KEY_TALK_MIN = "talk_minutes";
    public static final String KEY_SIL_MIN = "silence_minutes";
    public static final String KEY_LOOP = "loop_folder";
    public static final String KEY_SOURCE = "source";          // "local" or "drive"
    public static final String KEY_DRIVE_LINK = "drive_link";
    public static final String KEY_DRIVE_LIST = "drive_list";  // JSON [[name,id],...]
    public static final String KEY_SOURCE_LABEL = "source_label";

    public static int talkMinutes(Context c) { return Math.max(1, prefs(c).getInt(KEY_TALK_MIN, 10)); }
    public static int silenceMinutes(Context c) { return Math.max(1, prefs(c).getInt(KEY_SIL_MIN, 10)); }
    public static boolean loop(Context c) { return prefs(c).getBoolean(KEY_LOOP, true); }

    public static final List<Uri> uris = new ArrayList<>();
    public static final List<String> names = new ArrayList<>();

    private Library() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isDrive(Uri u) { return u != null && "gdrive".equals(u.getScheme()); }

    public static String driveId(Uri u) { return u.getSchemeSpecificPart(); }
    /** Saved Drive playlists: [{"n":name,"id":folderId,"f":[[file,id],...]}, ...] */
    public static final String KEY_DRIVE_TREE = "drive_tree";
    public static final String KEY_PLAYLIST = "drive_playlist";

    private static org.json.JSONArray tree(Context c) {
        try {
            String t = prefs(c).getString(KEY_DRIVE_TREE, null);
            if (t != null) return new org.json.JSONArray(t);
            // older versions stored a single list
            String old = prefs(c).getString(KEY_DRIVE_LIST, null);
            org.json.JSONArray a = new org.json.JSONArray();
            if (old != null) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", "Google Drive");
                o.put("id", "");
                o.put("f", new org.json.JSONArray(old));
                a.put(o);
            }
            return a;
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    public static List<String> playlistNames(Context c) {
        List<String> out = new ArrayList<>();
        org.json.JSONArray a = tree(c);
        for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i).optString("n"));
        return out;
    }

    public static int playlistSize(Context c, int i) {
        org.json.JSONObject o = tree(c).optJSONObject(i);
        return o == null ? 0 : o.optJSONArray("f").length();
    }

    public static int selectedPlaylist(Context c) {
        int n = tree(c).length();
        int s = prefs(c).getInt(KEY_PLAYLIST, 0);
        return (s >= 0 && s < n) ? s : 0;
    }

    /** Fills the lesson list from the selected Drive playlist (no internet needed). */
    public static boolean loadDrive(Context c) {
        org.json.JSONArray a = tree(c);
        if (a.length() == 0) return false;
        List<String[]> items = new ArrayList<>();
        try {
            org.json.JSONArray f = a.getJSONObject(selectedPlaylist(c)).getJSONArray("f");
            for (int i = 0; i < f.length(); i++) {
                org.json.JSONArray e = f.getJSONArray(i);
                items.add(new String[]{e.getString(0), e.getString(1)});
            }
        } catch (Exception e) {
            return false;
        }
        sortByLesson(items);
        uris.clear();
        names.clear();
        for (String[] it : items) {
            names.add(it[0]);
            uris.add(Uri.parse("gdrive:" + it[1]));
        }
        return true;
    }

    /** Saves the scanned Drive folders. newLink = true when a different link was entered. */
    public static void saveDriveTree(Context c, String link, List<Drive.Playlist> lists, boolean newLink) {
        String selectedName = null;
        List<String> oldNames = playlistNames(c);
        int oldSel = selectedPlaylist(c);
        if (!newLink && oldSel < oldNames.size()) selectedName = oldNames.get(oldSel);

        org.json.JSONArray a = new org.json.JSONArray();
        int sel = 0;
        try {
            for (int i = 0; i < lists.size(); i++) {
                Drive.Playlist p = lists.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", p.name);
                o.put("id", p.id);
                org.json.JSONArray f = new org.json.JSONArray();
                for (int k = 0; k < p.ids.size(); k++) {
                    org.json.JSONArray e = new org.json.JSONArray();
                    e.put(p.names.get(k));
                    e.put(p.ids.get(k));
                    f.put(e);
                }
                o.put("f", f);
                a.put(o);
                if (p.name.equals(selectedName)) sel = i;
            }
        } catch (Exception ignored) {
        }
        SharedPreferences.Editor ed = prefs(c).edit()
                .putString(KEY_SOURCE, "drive")
                .putString(KEY_DRIVE_LINK, link)
                .putString(KEY_DRIVE_TREE, a.toString())
                .remove(KEY_DRIVE_LIST)
                .putString(KEY_SOURCE_LABEL, "☁️ Google Drive (" + lists.size() + " Playlist)")
                .putInt(KEY_PLAYLIST, sel);
        if (newLink) ed.putInt(KEY_INDEX, 0).putLong(KEY_POS, 0);
        ed.apply();
    }

    public static void selectPlaylist(Context c, int i) {
        prefs(c).edit().putInt(KEY_PLAYLIST, i).putInt(KEY_INDEX, 0).putLong(KEY_POS, 0).apply();
        loadDrive(c);
    }

    public static int driveCount(Context c) {
        int sum = 0;
        org.json.JSONArray a = tree(c);
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONArray f = a.optJSONObject(i).optJSONArray("f");
            if (f != null) sum += f.length();
        }
        return sum;
    }

    private static void sortByLesson(List<String[]> items) {
        Collections.sort(items, (a, b) -> {
            long na = lessonNumber(a[0]), nb = lessonNumber(b[0]);
            if (na != nb) return Long.compare(na, nb);
            return a[0].compareToIgnoreCase(b[0]);
        });
    }

    /** Loads the playlist from the saved source if it is not loaded yet. */
    public static boolean ensureLoaded(Context c) {
        if (!uris.isEmpty()) return true;
        if ("drive".equals(prefs(c).getString(KEY_SOURCE, "local"))) return loadDrive(c);
        String t = prefs(c).getString(KEY_TREE, null);
        if (t == null) return false;
        return load(c, Uri.parse(t));
    }

    /** Reads every audio file inside the chosen folder, sorted by lesson number. */
    public static boolean load(Context c, Uri tree) {
        List<String[]> items = new ArrayList<>(); // [name, docId]
        try {
            String treeId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
            String[] cols = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cur = c.getContentResolver().query(children, cols, null, null, null)) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String id = cur.getString(0);
                        String name = cur.getString(1);
                        String mime = cur.getString(2);
                        if (name == null) continue;
                        String lower = name.toLowerCase();
                        boolean audio = (mime != null && mime.startsWith("audio/"))
                                || lower.endsWith(".mp3") || lower.endsWith(".m4a")
                                || lower.endsWith(".wav") || lower.endsWith(".ogg");
                        if (audio) items.add(new String[]{name, id});
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        Collections.sort(items, (a, b) -> {
            long na = lessonNumber(a[0]), nb = lessonNumber(b[0]);
            if (na != nb) return Long.compare(na, nb);
            return a[0].compareToIgnoreCase(b[0]);
        });
        uris.clear();
        names.clear();
        for (String[] it : items) {
            names.add(it[0]);
            uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, it[1]));
        }
        return true;
    }

    /** First number in the name; Khmer digits (០-៩) count too. */
    public static long lessonNumber(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            int d = -1;
            if (ch >= '0' && ch <= '9') d = ch - '0';
            else if (ch >= '០' && ch <= '៩') d = ch - '០';
            if (d >= 0) {
                if (sb.length() < 9) sb.append(d);
            } else if (sb.length() > 0) {
                break;
            }
        }
        return sb.length() == 0 ? Long.MAX_VALUE : Long.parseLong(sb.toString());
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
