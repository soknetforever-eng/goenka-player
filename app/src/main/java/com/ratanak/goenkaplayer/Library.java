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

    public static final List<Uri> uris = new ArrayList<>();
    public static final List<String> names = new ArrayList<>();

    private Library() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Loads the playlist from the saved folder if it is not loaded yet. */
    public static boolean ensureLoaded(Context c) {
        if (!uris.isEmpty()) return true;
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
