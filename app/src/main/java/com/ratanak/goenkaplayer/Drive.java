package com.ratanak.goenkaplayer;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Drive support for folders shared as "Anyone with the link".
 * Lists the folder through Drive's public embedded folder page and downloads each MP3
 * into the app's own storage, so playback never depends on the internet once a file is saved.
 */
public final class Drive {

    private Drive() {}

    /** Pulls the folder id out of any Drive folder link (or accepts a bare id). */
    public static String folderId(String link) {
        if (link == null) return null;
        link = link.trim();
        Matcher m = Pattern.compile("folders/([A-Za-z0-9_-]{10,})").matcher(link);
        if (m.find()) return m.group(1);
        m = Pattern.compile("[?&]id=([A-Za-z0-9_-]{10,})").matcher(link);
        if (m.find()) return m.group(1);
        if (link.matches("[A-Za-z0-9_-]{10,}")) return link;
        return null;
    }

    /** Result of listing: names[i] belongs to ids[i]. */
    public static final class Listing {
        public final List<String> names = new ArrayList<>();
        public final List<String> ids = new ArrayList<>();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GoenkaPlayer");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /** Lists the audio files in a public Drive folder. Must run off the main thread. */
    public static Listing list(String folderId) throws Exception {
        HttpURLConnection c = open("https://drive.google.com/embeddedfolderview?id=" + folderId + "#list");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        String html;
        try (InputStream in = c.getInputStream()) { html = readAll(in); }

        Listing out = new Listing();
        Set<String> seen = new HashSet<>();
        // Each entry: <div class="flip-entry" id="entry-FILEID" ...> ... <div class="flip-entry-title">NAME</div>
        Matcher m = Pattern.compile("id=\"entry-([A-Za-z0-9_-]+)\"(.*?)flip-entry-title\">([^<]*)<",
                Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String id = m.group(1);
            String block = m.group(2);
            String name = unescape(m.group(3));
            if (block.contains("/folders/")) continue;          // sub-folder, skip
            String lower = name.toLowerCase();
            boolean audio = lower.endsWith(".mp3") || lower.endsWith(".m4a")
                    || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".aac");
            if (audio && seen.add(id)) {
                out.names.add(name);
                out.ids.add(id);
            }
        }
        if (out.ids.isEmpty() && !html.contains("flip-entry")) {
            throw new Exception("not-shared");
        }
        return out;
    }

    // ---------------- local copies ----------------

    public static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "drive");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File localFile(Context c, String id) {
        return new File(dir(c), id + ".audio");
    }

    public static boolean isSaved(Context c, String id) {
        File f = localFile(c, id);
        return f.exists() && f.length() > 0;
    }

    public interface Progress { void onProgress(int percent); }

    private static final Set<String> running = new HashSet<>();

    /** Downloads one file into app storage (skips if already saved). Must run off the main thread. */
    public static File download(Context c, String id, Progress p) throws Exception {
        File dest = localFile(c, id);
        if (dest.exists() && dest.length() > 0) return dest;
        synchronized (running) {
            while (running.contains(id)) running.wait();
            if (dest.exists() && dest.length() > 0) return dest;
            running.add(id);
        }
        File part = new File(dest.getPath() + ".part");
        try {
            HttpURLConnection conn = open("https://drive.usercontent.google.com/download?id=" + id
                    + "&export=download&confirm=t");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String type = conn.getContentType();
            if (type != null && type.startsWith("text/html")) throw new Exception("not-shared");
            long total = conn.getContentLengthLong();
            long done = 0;
            int lastPct = -1;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0 && p != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) { lastPct = pct; p.onProgress(pct); }
                    }
                }
            }
            if (done == 0) throw new Exception("empty");
            if (!part.renameTo(dest)) throw new Exception("rename");
            return dest;
        } finally {
            if (part.exists()) part.delete();
            synchronized (running) {
                running.remove(id);
                running.notifyAll();
            }
        }
    }

    /** Space used by downloaded Drive files, in MB. */
    public static long savedMb(Context c) {
        long sum = 0;
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) sum += f.length();
        return sum / (1024 * 1024);
    }

    public static void clearSaved(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
