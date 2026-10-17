package com.ratanak.goenkaplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 10;

    // colours
    private static final int BROWN = Color.rgb(0x7A, 0x4A, 0x1F);
    private static final int BROWN_DARK = Color.rgb(0x5C, 0x36, 0x14);
    private static final int CREAM = Color.rgb(0xFB, 0xF6, 0xEE);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(0x2B, 0x22, 0x1A);
    private static final int MUTED = Color.rgb(0x8A, 0x7D, 0x70);
    private static final int HILITE = Color.rgb(0xFC, 0xEB, 0xCF);

    private TextView updateInfo;
    private Button installBtn, updateNowBtn;
    private TextView title, statusPill, posText, durText, sourceLabel, listHeader, driveInfo, talkVal, silVal;
    private SeekBar seek;
    private Button playBtn;
    private View batteryBanner, settingsPanel, driveRow;
    private Button gearBtn, dlAllBtn;
    private LinearLayout listHeaderRow, chips;
    private View chipsScroll;
    private Switch cycleSwitch, loopSwitch;
    private ListView list;
    private PlaylistAdapter adapter;
    private boolean userSeeking;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BROWN_DARK);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        Updater.schedule(this);
        Schedules.arm(this);
        boolean loaded = Library.ensureLoaded(this);
        showList();
        showSettings(!loaded);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button pillButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setBackground(round(bg, 22));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(dp(44));
        b.setStateListAnimator(null);
        return b;
    }

    private Button roundButton(String label, int sizeDp, int bg, int fg, int textSp) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(textSp);
        b.setTextColor(fg);
        b.setBackground(circle(bg));
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setElevation(dp(2));
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(10), dp(12), 0);
        return lp;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);

        // ---- header ----
        LinearLayout header = row();
        header.setBackgroundColor(BROWN);
        header.setPadding(dp(16), dp(12), dp(10), dp(12));
        TextView appName = text("🪷  Goenka Player", 20, Color.WHITE, true);
        header.addView(appName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button ext = pillButton("⏱ App ផ្សេង", BROWN_DARK, Color.WHITE);
        ext.setTextSize(13);
        ext.setOnClickListener(v -> openExternalTimer());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.rightMargin = dp(6);
        header.addView(ext, elp);
        Button gear = pillButton("⚙️  ការកំណត់", BROWN_DARK, Color.WHITE);
        gear.setTextSize(14);
        gearBtn = gear;
        gear.setOnClickListener(v -> showSettings(settingsPanel.getVisibility() != View.VISIBLE));
        header.addView(gear);
        root.addView(header);

        // ---- battery warning ----
        TextView battery = text("⚠️  ចុចទីនេះ ដើម្បីកុំឱ្យទូរស័ព្ទបញ្ឈប់ការលេង (Battery)", 14, TEXT, true);
        battery.setBackground(round(Color.rgb(0xFF, 0xE0, 0xA8), 12));
        battery.setPadding(dp(14), dp(10), dp(14), dp(10));
        battery.setOnClickListener(v -> askBatteryExemption());
        batteryBanner = battery;
        root.addView(battery, cardParams());

        // ---- now playing ----
        LinearLayout now = card();
        title = text("", 18, TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        now.addView(title);

        statusPill = text("", 14, BROWN, true);
        statusPill.setBackground(round(HILITE, 14));
        statusPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(8);
        now.addView(statusPill, pl);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) posText.setText(Library.time(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                PlayerService ps = PlayerService.instance;
                if (ps != null) ps.seekTo(s.getProgress());
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        sl.topMargin = dp(8);
        now.addView(seek, sl);

        LinearLayout times = row();
        posText = text("0:00:00", 13, MUTED, false);
        durText = text("0:00:00", 13, MUTED, false);
        durText.setGravity(Gravity.END);
        times.addView(posText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(durText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        now.addView(times);

        // controls
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, dp(2));
        Button prev = roundButton("⏮", 58, HILITE, BROWN, 22);
        playBtn = roundButton("▶", 82, BROWN, Color.WHITE, 32);
        Button next = roundButton("⏭", 58, HILITE, BROWN, 22);
        prev.setOnClickListener(v -> send(PlayerService.ACTION_PREV));
        playBtn.setOnClickListener(v -> send(PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> send(PlayerService.ACTION_NEXT));
        controls.addView(prev);
        controls.addView(new View(this), new LinearLayout.LayoutParams(dp(28), 1));
        controls.addView(playBtn);
        controls.addView(new View(this), new LinearLayout.LayoutParams(dp(28), 1));
        controls.addView(next);
        now.addView(controls);
        root.addView(now, cardParams());

        // ---- settings (collapsible) ----
        LinearLayout set = card();

        set.addView(text("ប្រភព MP3", 15, BROWN, true));
        LinearLayout srcRow = row();
        srcRow.setPadding(0, dp(8), 0, dp(4));
        Button local = pillButton("📁 ក្នុងទូរស័ព្ទ", HILITE, BROWN);
        Button drive = pillButton("☁️ Google Drive", HILITE, BROWN);
        local.setOnClickListener(v -> chooseLocalFolder());
        drive.setOnClickListener(v -> askDriveLink());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half.setMargins(0, 0, dp(6), 0);
        srcRow.addView(local, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half2.setMargins(dp(6), 0, 0, 0);
        srcRow.addView(drive, half2);
        set.addView(srcRow);
        sourceLabel = text("", 13, MUTED, false);
        set.addView(sourceLabel);

        LinearLayout dr = row();
        dr.setPadding(0, dp(4), 0, 0);
        driveInfo = text("", 13, MUTED, false);
        dr.addView(driveInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refreshBtn = pillButton("🔄", HILITE, BROWN);
        refreshBtn.setOnClickListener(v -> {
            String link = Library.prefs(this).getString(Library.KEY_DRIVE_LINK, null);
            if (link != null) loadDriveFolder(link);
        });
        dr.addView(refreshBtn, new LinearLayout.LayoutParams(dp(52), dp(40)));
        Button clear = pillButton("🗑", HILITE, BROWN);
        clear.setOnClickListener(v -> confirmClearDrive());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(52), dp(40));
        cl.leftMargin = dp(6);
        dr.addView(clear, cl);
        driveRow = dr;
        set.addView(dr);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(0xEE, 0xE4, 0xD6));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        ll.setMargins(0, dp(12), 0, dp(10));
        set.addView(line, ll);

        cycleSwitch = new Switch(this);
        cycleSwitch.setText("⏱  ចាក់ ហើយ ស្ងាត់ (Timer)");
        cycleSwitch.setTextSize(15);
        cycleSwitch.setTextColor(TEXT);
        cycleSwitch.setChecked(Library.prefs(this).getBoolean(Library.KEY_CYCLE, true));
        cycleSwitch.setOnCheckedChangeListener((b, on) -> {
            Library.prefs(this).edit().putBoolean(Library.KEY_CYCLE, on).apply();
            updateSettingsText();
        });
        set.addView(cycleSwitch);

        talkVal = text("", 20, TEXT, true);
        silVal = text("", 20, TEXT, true);
        set.addView(stepper("ចាក់", Library.KEY_TALK_MIN, talkVal));
        set.addView(stepper("ស្ងាត់", Library.KEY_SIL_MIN, silVal));

        LinearLayout presets = row();
        presets.setPadding(0, dp(8), 0, dp(4));
        presets.addView(text("រហ័ស:", 13, MUTED, false));
        int[][] choices = {{10, 10}, {15, 10}, {20, 15}, {30, 30}};
        for (int[] p : choices) {
            Button b = pillButton(p[0] + "/" + p[1], HILITE, BROWN);
            b.setTextSize(13);
            b.setOnClickListener(v -> {
                Library.prefs(this).edit()
                        .putInt(Library.KEY_TALK_MIN, p[0])
                        .putInt(Library.KEY_SIL_MIN, p[1])
                        .apply();
                cycleSwitch.setChecked(true);
                updateSettingsText();
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(38), 1f);
            bl.leftMargin = dp(6);
            presets.addView(b, bl);
        }
        set.addView(presets);

        loopSwitch = new Switch(this);
        loopSwitch.setText("🔁  Loop — ចប់ File ចុងក្រោយ ចាប់ផ្ដើមម្ដងទៀត");
        loopSwitch.setTextSize(15);
        loopSwitch.setTextColor(TEXT);
        loopSwitch.setChecked(Library.loop(this));
        loopSwitch.setOnCheckedChangeListener((b, on) ->
                Library.prefs(this).edit().putBoolean(Library.KEY_LOOP, on).apply());
        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lsp.topMargin = dp(8);
        set.addView(loopSwitch, lsp);

        // ---- schedules ----
        View lineS = new View(this);
        lineS.setBackgroundColor(Color.rgb(0xEE, 0xE4, 0xD6));
        LinearLayout.LayoutParams lls = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lls.setMargins(0, dp(12), 0, dp(10));
        set.addView(lineS, lls);
        set.addView(text("⏰ កាលវិភាគ (ចាក់តាមម៉ោង)", 15, BROWN, true));
        TextView schedHint = text("គ្មានកាលវិភាគ = ចាក់ធម្មតារហូតដល់អ្នកបញ្ឈប់", 13, MUTED, false);
        set.addView(schedHint);
        schedBox = new LinearLayout(this);
        schedBox.setOrientation(LinearLayout.VERTICAL);
        set.addView(schedBox);
        Button addSched = pillButton("＋  បន្ថែមកាលវិភាគ", BROWN, Color.WHITE);
        addSched.setOnClickListener(v -> editSchedule(null));
        LinearLayout.LayoutParams asb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        asb.topMargin = dp(8);
        set.addView(addSched, asb);
        rebuildSchedules();

        // ---- updates ----
        View line2 = new View(this);
        line2.setBackgroundColor(Color.rgb(0xEE, 0xE4, 0xD6));
        LinearLayout.LayoutParams ll2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        ll2.setMargins(0, dp(12), 0, dp(10));
        set.addView(line2, ll2);
        set.addView(text("🔄 Update", 15, BROWN, true));

        Switch autoSwitch = new Switch(this);
        autoSwitch.setText("ឆែក Update ស្វ័យប្រវត្តិ រៀងរាល់ម៉ោង 2:00 យប់");
        autoSwitch.setTextSize(15);
        autoSwitch.setTextColor(TEXT);
        autoSwitch.setChecked(Updater.autoEnabled(this));
        autoSwitch.setOnCheckedChangeListener((b, on) -> {
            Library.prefs(this).edit().putBoolean(Updater.KEY_AUTO, on).apply();
            Updater.schedule(this);
        });
        LinearLayout.LayoutParams asp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asp.topMargin = dp(6);
        set.addView(autoSwitch, asp);

        updateNowBtn = pillButton("🔄  Update ឥឡូវ", BROWN, Color.WHITE);
        updateNowBtn.setOnClickListener(v -> {
            if (UpdateService.running) return;
            startForegroundService(new Intent(this, UpdateService.class).setAction(UpdateService.ACTION_MANUAL));
            Toast.makeText(this, "កំពុងឆែក Update…", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams ub = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        ub.topMargin = dp(8);
        set.addView(updateNowBtn, ub);

        installBtn = pillButton("", Color.rgb(0x2E, 0x7D, 0x32), Color.WHITE);
        installBtn.setOnClickListener(v -> installUpdate());
        installBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams ib = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        ib.topMargin = dp(8);
        set.addView(installBtn, ib);

        updateInfo = text("", 13, MUTED, false);
        updateInfo.setPadding(0, dp(6), 0, 0);
        set.addView(updateInfo);
        // settings scroll inside the free space (so nothing is cut off on small screens)
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(0, 0, 0, dp(16));
        pad.addView(set, cardParams());
        scroll.addView(pad);
        settingsPanel = scroll;
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- playlist ----
        // ---- Drive playlists (one per folder) ----
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = row();
        chips.setPadding(dp(12), dp(10), dp(12), 0);
        hs.addView(chips);
        chipsScroll = hs;
        root.addView(hs);

        listHeaderRow = row();
        listHeaderRow.setPadding(dp(20), dp(10), dp(12), dp(4));
        listHeader = text("បញ្ជីវគ្គ", 15, BROWN, true);
        listHeaderRow.addView(listHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dlAllBtn = pillButton("⬇ ទាញយកទាំងអស់", HILITE, BROWN);
        dlAllBtn.setTextSize(13);
        dlAllBtn.setOnClickListener(v -> confirmDownloadAll());
        listHeaderRow.addView(dlAllBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        root.addView(listHeaderRow);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(4));
        list.setPadding(dp(10), 0, dp(10), dp(10));
        list.setClipToPadding(false);
        adapter = new PlaylistAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent i = new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_PLAY_INDEX)
                    .putExtra(PlayerService.EXTRA_INDEX, position)
                    .putExtra(PlayerService.EXTRA_POS, 0L);
            startForegroundService(i);
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateSettingsText();
    }

    /** "ចាក់   (–)   15 នាទី   (+)" — tap the number to type it. */
    private View stepper(String label, String key, TextView value) {
        LinearLayout r = row();
        r.setPadding(0, dp(8), 0, 0);
        TextView l = text(label, 16, TEXT, false);
        r.addView(l, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button minus = roundButton("–", 44, HILITE, BROWN, 22);
        Button plus = roundButton("+", 44, HILITE, BROWN, 22);
        minus.setOnClickListener(v -> changeMinutes(key, -1));
        plus.setOnClickListener(v -> changeMinutes(key, +1));
        minus.setOnLongClickListener(v -> { changeMinutes(key, -5); return true; });
        plus.setOnLongClickListener(v -> { changeMinutes(key, +5); return true; });
        value.setGravity(Gravity.CENTER);
        value.setOnClickListener(v -> typeMinutes(key));
        r.addView(minus);
        r.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(plus);
        return r;
    }

    private int minutes(String key) {
        return Library.KEY_TALK_MIN.equals(key) ? Library.talkMinutes(this) : Library.silenceMinutes(this);
    }

    private void changeMinutes(String key, int delta) {
        int m = Math.max(1, Math.min(600, minutes(key) + delta));
        Library.prefs(this).edit().putInt(key, m).apply();
        updateSettingsText();
    }

    private void typeMinutes(String key) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(minutes(key)));
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("ចំនួននាទី")
                .setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int m = Integer.parseInt(e.getText().toString().trim());
                        if (m >= 1 && m <= 600) {
                            Library.prefs(this).edit().putInt(key, m).apply();
                            updateSettingsText();
                        }
                    } catch (NumberFormatException ignored) {}
                })
                .setNegativeButton("បោះបង់", null)
                .show();
    }

    /** Settings and the lesson list share the lower part of the screen. */
    private void showSettings(boolean show) {
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        listHeaderRow.setVisibility(show ? View.GONE : View.VISIBLE);
        chipsScroll.setVisibility(show || !isDriveSource() || Library.playlistNames(this).size() < 2 ? View.GONE : View.VISIBLE);
        gearBtn.setText(show ? "✖  បិទ" : "⚙️  ការកំណត់");
        if (show) updateSettingsText();
    }

    private void updateSettingsText() {
        talkVal.setText(Library.talkMinutes(this) + " នាទី");
        silVal.setText(Library.silenceMinutes(this) + " នាទី");
        boolean drive = "drive".equals(Library.prefs(this).getString(Library.KEY_SOURCE, "local"));
        String label = Library.prefs(this).getString(Library.KEY_SOURCE_LABEL, null);
        sourceLabel.setText(label == null ? "មិនទាន់ជ្រើស" : "កំពុងប្រើ: " + label);
        driveRow.setVisibility(drive ? View.VISIBLE : View.GONE);
        if (drive) driveInfo.setText("បានទាញយកទុក: " + Drive.savedMb(this) + " MB");
    }

    // ------------------------------------------------------------------ playlist

    private final class PlaylistAdapter extends BaseAdapter {
        @Override public int getCount() { return Library.names.size(); }
        @Override public Object getItem(int i) { return Library.names.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout r;
            if (convert == null) {
                r = row();
                r.setPadding(dp(10), dp(10), dp(10), dp(10));
                TextView num = text("", 14, BROWN, true);
                num.setGravity(Gravity.CENTER);
                r.addView(num, new LinearLayout.LayoutParams(dp(34), dp(34)));
                TextView name = text("", 15, TEXT, false);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(12), 0, dp(8), 0);
                r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView badge = text("", 15, BROWN, true);
                badge.setGravity(Gravity.CENTER);
                badge.setMinWidth(dp(48));
                badge.setPadding(dp(6), dp(8), dp(6), dp(8));
                badge.setOnClickListener(bv -> onBadge((Integer) bv.getTag()));
                r.addView(badge);
                r.setTag(new TextView[]{num, name, badge});
            } else {
                r = (LinearLayout) convert;
            }
            TextView[] v = (TextView[]) r.getTag();
            TextView num = v[0], name = v[1], badge = v[2];

            PlayerService ps = PlayerService.instance;
            int current = ps != null ? ps.index() : Library.prefs(MainActivity.this).getInt(Library.KEY_INDEX, -1);
            boolean isCur = i == current;
            num.setText(isCur ? "▶" : String.valueOf(i + 1));
            num.setBackground(circle(isCur ? BROWN : HILITE));
            num.setTextColor(isCur ? Color.WHITE : BROWN);
            name.setText(Library.names.get(i));
            name.setTypeface(isCur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            r.setBackground(round(isCur ? HILITE : CARD, 12));
            Uri u = Library.uris.get(i);
            badge.setTag(i);
            if (Library.isDrive(u)) {
                String id = Library.driveId(u);
                Integer pct = Drive.progress.get(id);
                if (Drive.isSaved(MainActivity.this, id)) { badge.setText("✓"); badge.setBackground(null); }
                else if (pct != null) { badge.setText(pct + "%"); badge.setBackground(null); }
                else { badge.setText("⬇"); badge.setBackground(round(HILITE, 10)); }
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            return r;
        }
    }

    private void showList() {
        adapter.notifyDataSetChanged();
        listHeader.setText("បញ្ជីវគ្គ (" + Library.names.size() + ")");
        dlAllBtn.setVisibility(isDriveSource() && !Library.names.isEmpty() ? View.VISIBLE : View.GONE);
        rebuildChips();
        int last = Library.prefs(this).getInt(Library.KEY_INDEX, -1);
        if (last >= 0 && last < Library.names.size()) {
            title.setText(Library.names.get(last));
            long pos = Library.prefs(this).getLong(Library.KEY_POS, 0);
            statusPill.setText("ចុច ▶ ដើម្បីលេងបន្តពី " + Library.time(pos));
        } else if (!Library.names.isEmpty()) {
            title.setText("ចុចលើវគ្គណាមួយខាងក្រោម");
            statusPill.setText("រួចរាល់");
        } else {
            title.setText("សូមជ្រើស MP3 នៅក្នុង ⚙️ ការកំណត់");
            statusPill.setText("មិនទាន់មាន File");
        }
        updateSettingsText();
    }

    // ------------------------------------------------------------------ sources

    private boolean isDriveSource() {
        return "drive".equals(Library.prefs(this).getString(Library.KEY_SOURCE, "local"));
    }

    /** Playlist buttons, one per Drive folder. */
    private void rebuildChips() {
        chips.removeAllViews();
        java.util.List<String> names = Library.playlistNames(this);
        boolean show = isDriveSource() && names.size() > 1 && settingsPanel.getVisibility() != View.VISIBLE;
        chipsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        int sel = Library.selectedPlaylist(this);
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            boolean on = i == sel;
            Button b = pillButton("📂 " + names.get(i) + "  (" + Library.playlistSize(this, i) + ")",
                    on ? BROWN : HILITE, on ? Color.WHITE : BROWN);
            b.setTextSize(14);
            b.setOnClickListener(v -> {
                if (idx == Library.selectedPlaylist(this)) return;
                stopPlayer();
                Library.selectPlaylist(this, idx);
                lastListIndex = -2;
                showList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            lp.rightMargin = dp(8);
            chips.addView(b, lp);
        }
    }

    private void startDownloads(java.util.ArrayList<String> ids) {
        if (ids.isEmpty()) return;
        for (String id : ids) if (!Drive.progress.containsKey(id)) Drive.progress.put(id, 0);
        startForegroundService(new Intent(this, UpdateService.class)
                .setAction(UpdateService.ACTION_DOWNLOAD)
                .putStringArrayListExtra(UpdateService.EXTRA_IDS, ids));
        adapter.notifyDataSetChanged();
    }

    /** ⬇ = download this lesson, ✓ = offer to delete the saved copy. */
    private void onBadge(int i) {
        if (i < 0 || i >= Library.uris.size()) return;
        Uri u = Library.uris.get(i);
        if (!Library.isDrive(u)) return;
        String id = Library.driveId(u);
        if (Drive.isSaved(this, id)) {
            new AlertDialog.Builder(this)
                    .setTitle("លុប File ដែលបានទាញយក?")
                    .setMessage(Library.names.get(i) + "\n\nវានឹងចាក់អនឡាញវិញ។")
                    .setPositiveButton("លុប", (d, w) -> {
                        PlayerService ps = PlayerService.instance;
                        if (ps != null && ps.index() == i && (ps.isPlaying() || ps.isInSilence())) {
                            Toast.makeText(this, "កំពុងចាក់ File នេះ — សូមផ្អាកសិន", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Drive.deleteSaved(this, id);
                        adapter.notifyDataSetChanged();
                        updateSettingsText();
                    })
                    .setNegativeButton("បោះបង់", null)
                    .show();
        } else if (!Drive.progress.containsKey(id)) {
            java.util.ArrayList<String> one = new java.util.ArrayList<>();
            one.add(id);
            startDownloads(one);
            Toast.makeText(this, "កំពុងទាញយក…", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDownloadAll() {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (Uri u : Library.uris) {
            if (Library.isDrive(u)) {
                String id = Library.driveId(u);
                if (!Drive.isSaved(this, id) && !Drive.progress.containsKey(id)) ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, "បានទាញយកទាំងអស់រួចហើយ", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ទាញយក " + ids.size() + " File?")
                .setMessage("File នីមួយៗអាចធំប្រហែល 100 MB។ ប្រសិនបើប្រើ Data ទូរស័ព្ទ វាអាចអស់ Data ច្រើន។"
                        + (Updater.onWifi(this) ? "" : "\n\n⚠️ ឥឡូវមិនមែន Wi-Fi ទេ។"))
                .setPositiveButton("ទាញយក", (d, w) -> startDownloads(ids))
                .setNegativeButton("បោះបង់", null)
                .show();
    }

    // ------------------------------------------------------------------ timer for other apps

    private void openExternalTimer() {
        if (!ExternalTimerService.hasAccess(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("⏱ Timer សម្រាប់ App ផ្សេង")
                    .setMessage("Timer នេះនឹង Pause/Play App ផ្សេង (YouTube, Spotify, Podcast…) តាមនាទីចាក់/ស្ងាត់ ទោះបិទអេក្រង់ក៏ដោយ។\n\n"
                            + "ត្រូវអនុញ្ញាតម្ដង: ចុច \"បើក Settings\" → ជ្រើស Goenka Player → បើក (Allow)។")
                    .setPositiveButton("បើក Settings", (d, w) -> {
                        try { startActivity(ExternalTimerService.accessSettings()); }
                        catch (Exception e) { Toast.makeText(this, "បើក Settings មិនបាន", Toast.LENGTH_SHORT).show(); }
                    })
                    .setNegativeButton("បោះបង់", null)
                    .show();
            return;
        }
        String mins = "ចាក់ " + Library.talkMinutes(this) + " នាទី • ស្ងាត់ " + Library.silenceMinutes(this) + " នាទី";
        ExternalTimerService ex = ExternalTimerService.instance;
        if (ex != null) {
            new AlertDialog.Builder(this)
                    .setTitle("⏱ Timer: " + ex.label())
                    .setMessage(ex.statusText() + "\n" + mins)
                    .setPositiveButton("⏹ បញ្ឈប់ Timer", (d, w) -> startService(
                            new Intent(this, ExternalTimerService.class).setAction(ExternalTimerService.ACTION_STOP)))
                    .setNegativeButton("បិទ", null)
                    .show();
            return;
        }
        android.media.session.MediaController mc = ExternalTimerService.findController(this, null);
        if (mc == null) {
            new AlertDialog.Builder(this)
                    .setTitle("⏱ Timer សម្រាប់ App ផ្សេង")
                    .setMessage("រកមិនឃើញ App ណាកំពុងចាក់ទេ។\n\nសូមបើកវីដេអូ/សម្លេងក្នុង YouTube, Spotify ឬ Podcast សិន រួចត្រឡប់មកចុចម្ដងទៀត។")
                    .setPositiveButton("យល់ព្រម", null)
                    .show();
            return;
        }
        String app = ExternalTimerService.appName(this, mc.getPackageName());
        String t = ExternalTimerService.mediaTitle(mc);
        String state = ExternalTimerService.isPlaying(mc) ? "កំពុងចាក់" : "ផ្អាក";
        new AlertDialog.Builder(this)
                .setTitle("⏱ " + app + " — " + state)
                .setMessage((t.isEmpty() ? "" : "🎵 " + t + "\n\n") + mins
                        + "\n\n(ប្ដូរនាទីបាននៅ ⚙️ ការកំណត់)\nក្រោយចុចចាប់ផ្ដើម អាចបិទអេក្រង់បាន។")
                .setPositiveButton("▶ ចាប់ផ្ដើម", (d, w) -> {
                    stopPlayer();
                    Intent i = new Intent(this, ExternalTimerService.class).setAction(ExternalTimerService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                    Toast.makeText(this, "⏱ Timer ចាប់ផ្ដើមសម្រាប់ " + app, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("បោះបង់", null)
                .show();
    }

    // ------------------------------------------------------------------ schedules

    private LinearLayout schedBox;

    private void rebuildSchedules() {
        if (schedBox == null) return;
        schedBox.removeAllViews();
        String active = Schedules.activeId(this);
        for (Schedules.Item it : Schedules.all(this)) {
            LinearLayout r = row();
            r.setBackground(round(Color.rgb(0xF7, 0xF0, 0xE6), 12));
            r.setPadding(dp(12), dp(8), dp(6), dp(8));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = text((it.id.equals(active) ? "▶ " : "") + it.timeText() + "   •   " + it.daysText(), 15, TEXT, true);
            String cyc = it.cycle ? "ចាក់ " + it.talk + " / ស្ងាត់ " + it.sil + " នាទី" : "ចាក់ជាប់ (គ្មានស្ងាត់)";
            String rs = it.resume ? (it.lastFile.isEmpty() ? "  •  ⏯ បន្ត" : "  •  ⏯ បន្តពី " + Library.time(it.lastPos)) : "";
            TextView t2 = text("🎵 " + (it.fileName.isEmpty() ? "?" : it.fileName) + "\n" + cyc + rs, 13, MUTED, false);
            col.addView(t1);
            col.addView(t2);
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(it.on);
            sw.setOnCheckedChangeListener((b, on) -> {
                it.on = on;
                Schedules.put(this, it);
            });
            r.addView(sw);
            r.setOnClickListener(v -> editSchedule(it));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            schedBox.addView(r, lp);
        }
    }

    private void editSchedule(Schedules.Item existing) {
        if (Library.uris.isEmpty()) {
            Toast.makeText(this, "សូមជ្រើស Folder ឬ Drive ដែលមាន MP3 សិន", Toast.LENGTH_LONG).show();
            return;
        }
        final Schedules.Item it = existing != null ? existing : new Schedules.Item();
        if (existing == null) {
            it.cycle = Library.prefs(this).getBoolean(Library.KEY_CYCLE, true);
            it.talk = Library.talkMinutes(this);
            it.sil = Library.silenceMinutes(this);
            int cur = Math.max(0, Math.min(Library.prefs(this).getInt(Library.KEY_INDEX, 0), Library.uris.size() - 1));
            pickFileInto(it, cur);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        // start / end
        Button startBtn = pillButton("", BROWN_DARK, Color.WHITE);
        Button endBtn = pillButton("", BROWN_DARK, Color.WHITE);
        Switch forever = new Switch(this);
        Runnable showTimes = () -> {
            startBtn.setText("ចាប់ផ្ដើម  " + Schedules.hhmm(it.start));
            endBtn.setText(it.end < 0 ? "បិទ  —" : "បិទ  " + Schedules.hhmm(it.end));
            endBtn.setEnabled(it.end >= 0);
            endBtn.setAlpha(it.end >= 0 ? 1f : 0.4f);
        };
        startBtn.setOnClickListener(v -> new android.app.TimePickerDialog(this, (tp, h, m) -> {
            it.start = h * 60 + m;
            showTimes.run();
        }, it.start / 60, it.start % 60, true).show());
        endBtn.setOnClickListener(v -> {
            int e = it.end < 0 ? (it.start + 60) % 1440 : it.end;
            new android.app.TimePickerDialog(this, (tp, h, m) -> {
                it.end = h * 60 + m;
                showTimes.run();
            }, e / 60, e % 60, true).show();
        });
        LinearLayout times = row();
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(44), 1f);
        half.rightMargin = dp(6);
        times.addView(startBtn, half);
        times.addView(endBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        box.addView(times);

        forever.setText("♾  ចាក់រហូត (គ្មានម៉ោងបិទ)");
        forever.setTextColor(TEXT);
        forever.setChecked(it.end < 0);
        forever.setOnCheckedChangeListener((b, on) -> {
            it.end = on ? -1 : (it.start + 60) % 1440;
            showTimes.run();
        });
        box.addView(forever);
        showTimes.run();

        // days
        box.addView(text("ថ្ងៃ: (មិនជ្រើស = រៀងរាល់ថ្ងៃ)", 14, MUTED, false));
        LinearLayout daysRow = row();
        for (int d = 0; d < 7; d++) {
            final int bit = d;
            Button db = new Button(this);
            db.setAllCaps(false);
            db.setTextSize(13);
            db.setMinWidth(0);
            db.setMinHeight(0);
            db.setPadding(0, 0, 0, 0);
            db.setStateListAnimator(null);
            db.setText(Schedules.DAY_SHORT[d]);
            Runnable paint = () -> {
                boolean on = (it.days & (1 << bit)) != 0;
                db.setBackground(circle(on ? BROWN : Color.rgb(0xE8, 0xDF, 0xD2)));
                db.setTextColor(on ? Color.WHITE : TEXT);
            };
            paint.run();
            db.setOnClickListener(v -> {
                it.days ^= (1 << bit);
                paint.run();
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(38), dp(38));
            dl.rightMargin = dp(4);
            daysRow.addView(db, dl);
        }
        box.addView(daysRow);

        // file
        box.addView(text("File ចាប់ផ្ដើម:", 14, MUTED, false));
        Button fileBtn = pillButton("🎵 " + it.fileName, Color.rgb(0xF7, 0xF0, 0xE6), TEXT);
        fileBtn.setTextSize(14);
        fileBtn.setOnClickListener(v -> {
            String[] items = Library.names.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("ជ្រើស File (ពី " + (isDriveSource() ? "Playlist បច្ចុប្បន្ន" : "Folder") + ")")
                    .setItems(items, (d, w) -> {
                        pickFileInto(it, w);
                        it.lastFile = "";
                        it.lastPos = 0;
                        fileBtn.setText("🎵 " + it.fileName);
                    })
                    .show();
        });
        box.addView(fileBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        box.addView(text("ចប់ File នោះ វាចាក់ File បន្ទាប់បន្ត", 12, MUTED, false));
        Switch resumeSw = new Switch(this);
        resumeSw.setText("⏯ ចាក់បន្តពីនាទីដែលបានឈប់លើកមុន");
        resumeSw.setTextColor(TEXT);
        resumeSw.setChecked(it.resume);
        box.addView(resumeSw);
        if (it.resume && !it.lastFile.isEmpty()) {
            String where = it.fileName;
            for (int k = 0; k < Library.uris.size(); k++)
                if (Library.uris.get(k).toString().equals(it.lastFile)) where = Library.names.get(k);
            box.addView(text("   លើកក្រោយបន្តពី: " + where + " • " + Library.time(it.lastPos), 12, MUTED, false));
        }

        // talk / silence
        Switch cyc = new Switch(this);
        cyc.setText("🔇 ចាក់/ស្ងាត់");
        cyc.setTextColor(TEXT);
        cyc.setChecked(it.cycle);
        box.addView(cyc);
        LinearLayout mins = row();
        EditText talkIn = new EditText(this);
        talkIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        talkIn.setText(String.valueOf(it.talk));
        EditText silIn = new EditText(this);
        silIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        silIn.setText(String.valueOf(it.sil));
        mins.addView(text("ចាក់ ", 14, TEXT, false));
        mins.addView(talkIn, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        mins.addView(text(" ស្ងាត់ ", 14, TEXT, false));
        mins.addView(silIn, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        mins.addView(text(" នាទី", 14, TEXT, false));
        box.addView(mins);
        cyc.setOnCheckedChangeListener((b, on) -> mins.setAlpha(on ? 1f : 0.4f));
        mins.setAlpha(it.cycle ? 1f : 0.4f);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "⏰ កាលវិភាគថ្មី" : "⏰ កែកាលវិភាគ")
                .setView(sv)
                .setPositiveButton("រក្សាទុក", (d, w) -> {
                    it.cycle = cyc.isChecked();
                    it.resume = resumeSw.isChecked();
                    try { it.talk = Math.max(1, Math.min(600, Integer.parseInt(talkIn.getText().toString().trim()))); } catch (Exception ignored) {}
                    try { it.sil = Math.max(1, Math.min(600, Integer.parseInt(silIn.getText().toString().trim()))); } catch (Exception ignored) {}
                    it.on = true;
                    Schedules.put(this, it);
                    rebuildSchedules();
                    Toast.makeText(this, "✅ រក្សាទុក: " + it.timeText() + " • " + it.daysText(), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("បោះបង់", null);
        if (existing != null) {
            b.setNeutralButton("🗑 លុប", (d, w) -> {
                Schedules.remove(this, it.id);
                rebuildSchedules();
            });
        }
        b.show();
    }

    private void pickFileInto(Schedules.Item it, int i) {
        if (i < 0 || i >= Library.uris.size()) return;
        it.file = Library.uris.get(i).toString();
        it.fileName = Library.names.get(i);
        if (isDriveSource()) {
            it.source = "drive";
            java.util.List<String> pl = Library.playlistNames(this);
            int sel = Library.selectedPlaylist(this);
            it.playlist = sel < pl.size() ? pl.get(sel) : "";
        } else {
            it.source = "local";
            it.tree = Library.prefs(this).getString(Library.KEY_TREE, "");
        }
    }

    private void stopPlayer() {
        if (PlayerService.instance != null) {
            startService(new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_STOP));
        }
    }

    private void chooseLocalFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            stopPlayer();
            String folderName = "📁 Folder ក្នុងទូរស័ព្ទ";
            try {
                String id = DocumentsContract.getTreeDocumentId(tree);
                int c = id.lastIndexOf(':');
                if (c >= 0 && c + 1 < id.length()) folderName = "📁 " + id.substring(c + 1);
            } catch (Exception ignored) {}
            Library.prefs(this).edit()
                    .putString(Library.KEY_SOURCE, "local")
                    .putString(Library.KEY_SOURCE_LABEL, folderName)
                    .putString(Library.KEY_TREE, tree.toString())
                    .putInt(Library.KEY_INDEX, 0)
                    .putLong(Library.KEY_POS, 0)
                    .apply();
            if (Library.load(this, tree)) {
                showList();
                showSettings(false);
                Toast.makeText(this, Library.names.size() + " File", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void askDriveLink() {
        EditText e = new EditText(this);
        e.setHint("https://drive.google.com/drive/folders/...");
        e.setSingleLine(true);
        e.setText(Library.prefs(this).getString(Library.KEY_DRIVE_LINK, ""));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("☁️ Link Folder Google Drive")
                .setMessage("បិទភ្ជាប់ (Paste) Link Folder។\nFolder ត្រូវ Share ជា \"Anyone with the link\"។\n"
                        + "File នីមួយៗនឹងទាញយកទុកក្នុងទូរស័ព្ទមុនពេលចាក់ (តែម្ដងប៉ុណ្ណោះ)។")
                .setView(box)
                .setPositiveButton("បើក", (d, w) -> loadDriveFolder(e.getText().toString()))
                .setNegativeButton("បោះបង់", null)
                .show();
    }

    private void loadDriveFolder(String link) {
        String id = Drive.folderId(link);
        if (id == null) {
            Toast.makeText(this, "Link មិនត្រឹមត្រូវ", Toast.LENGTH_LONG).show();
            return;
        }
        statusPill.setText("☁️ កំពុងអាន Folder…");
        new Thread(() -> {
            try {
                java.util.List<Drive.Playlist> lists = Drive.scanTree(id);
                int files = 0;
                for (Drive.Playlist p : lists) files += p.ids.size();
                final int nFiles = files;
                runOnUiThread(() -> {
                    if (lists.isEmpty()) {
                        statusPill.setText("រកមិនឃើញ MP3");
                        Toast.makeText(this, "រកមិនឃើញ MP3 ក្នុង Folder នេះទេ", Toast.LENGTH_LONG).show();
                        return;
                    }
                    stopPlayer();
                    String old = Library.prefs(this).getString(Library.KEY_DRIVE_LINK, "");
                    boolean newLink = !link.trim().equals(old) || !isDriveSource();
                    Library.saveDriveTree(this, link.trim(), lists, newLink);
                    Library.loadDrive(this);
                    showSettings(false);
                    showList();
                    Toast.makeText(this, lists.size() + " Playlist • " + nFiles + " File", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    String msg = "not-shared".equals(ex.getMessage())
                            ? "Folder មិនទាន់ Share ជា \"Anyone with the link\""
                            : "អាន Folder មិនបាន — សូមពិនិត្យ Internet";
                    statusPill.setText("⚠️ " + msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void confirmClearDrive() {
        new AlertDialog.Builder(this)
                .setTitle("លុប File ដែលបានទាញយក?")
                .setMessage("File នឹងត្រូវទាញយកម្ដងទៀតពេលចាក់។ (" + Drive.savedMb(this) + " MB)")
                .setPositiveButton("លុប", (d, w) -> {
                    stopPlayer();
                    Drive.clearSaved(this);
                    updateSettingsText();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("បោះបង់", null)
                .show();
    }

    // ------------------------------------------------------------------ actions

    private void send(String action) {
        if (Library.uris.isEmpty()) {
            Toast.makeText(this, "សូមជ្រើស MP3 នៅក្នុង ⚙️ ការកំណត់", Toast.LENGTH_SHORT).show();
            showSettings(true);
            return;
        }
        startForegroundService(new Intent(this, PlayerService.class).setAction(action));
    }

    private void askBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ------------------------------------------------------------------ refresh

    private int lastListIndex = -2;
    private int tickCount;

    private void installUpdate() {
        String url = Library.prefs(this).getString(Updater.KEY_PENDING_APK, null);
        if (url == null || !url.startsWith("http")) url = "https://github.com/" + Updater.GITHUB_REPO + "/releases/latest";
        Toast.makeText(this, "ទាញយកក្នុង Browser រួចបើក File GoenkaPlayer.apk ដើម្បីដំឡើង", Toast.LENGTH_LONG).show();
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "បើក Browser មិនបាន", Toast.LENGTH_SHORT).show(); }
    }

    private void refreshUpdateInfo() {
        String ver = "កំណែ v" + Updater.currentVersionName(this);
        if (UpdateService.running) {
            updateInfo.setText(ver + "\n⏳ " + UpdateService.progress);
            updateNowBtn.setText("⏳  កំពុង Update…");
        } else {
            String when = Library.prefs(this).getString(Updater.KEY_LAST_CHECK, null);
            String msg = Library.prefs(this).getString(Updater.KEY_LAST_MSG, "");
            updateInfo.setText(ver + (when == null ? "" : "  •  ឆែកចុងក្រោយ: " + when + "\n" + msg));
            updateNowBtn.setText("🔄  Update ឥឡូវ");
        }
        // pending install?
        String tag = Library.prefs(this).getString(Updater.KEY_PENDING_TAG, null);
        long tagNum = -1;
        if (tag != null) {
            try { tagNum = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1)); } catch (Exception ignored) {}
        }
        if (tagNum > Updater.currentVersion(this)) {
            installBtn.setText("⬇️  ទាញយកកំណែថ្មី " + tag);
            installBtn.setVisibility(View.VISIBLE);
        } else {
            installBtn.setVisibility(View.GONE);
            if (tag != null) {
                Library.prefs(this).edit().remove(Updater.KEY_PENDING_TAG).remove(Updater.KEY_PENDING_APK).apply();
            }
        }
    }

    private int uiTick;

    private void refresh() {
        if (++uiTick % 2 == 0) {
            refreshUpdateInfo();
            if (!UpdateService.running && Library.uris.isEmpty() == false) {
                listHeader.setText("បញ្ជីវគ្គ (" + Library.names.size() + ")");
            }
        }
        if (PlayerService.instance == null && uiTick % 6 == 0) adapter.notifyDataSetChanged();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        batteryBanner.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);

        ExternalTimerService ex = ExternalTimerService.instance;
        if (ex != null && PlayerService.instance == null) {
            title.setText("⏱ Timer: " + ex.label());
            statusPill.setText(ex.statusText());
            playBtn.setText("▶");
            return;
        }

        PlayerService ps = PlayerService.instance;
        if (ps == null) {
            playBtn.setText("▶");
            return;
        }
        int i = ps.index();
        if (i >= 0 && i < Library.names.size()) title.setText(Library.names.get(i));
        // redraw the list when the lesson changes, and every few seconds for download ticks
        if (i != lastListIndex || (++tickCount % 6 == 0)) {
            lastListIndex = i;
            adapter.notifyDataSetChanged();
            if (settingsPanel.getVisibility() == View.VISIBLE) updateSettingsText();
        }

        boolean playing = ps.isPlaying();
        playBtn.setText(playing ? "⏸" : "▶");

        if (ps.isDownloading()) {
            statusPill.setText("☁️ កំពុងទាញយក " + ps.downloadPercent() + "%");
        } else if (ps.lastError() != null) {
            statusPill.setText("⚠️ " + ps.lastError());
        } else if (ps.isLoading()) {
            statusPill.setText("☁️ កំពុងផ្ទុកពីអនឡាញ…");
        } else if (ps.isInSilence()) {
            statusPill.setText("🔇 ស្ងាត់ — នៅសល់ " + Library.time(ps.silenceRemaining()));
        } else if (playing && ps.cycleEnabled()) {
            statusPill.setText("🔊 កំពុងចាក់" + (ps.isStreaming() ? " (អនឡាញ)" : "") + " — ស្ងាត់ក្នុង " + Library.time(ps.talkRemaining()));
        } else if (playing) {
            statusPill.setText("🔊 កំពុងចាក់" + (ps.isStreaming() ? " (អនឡាញ)" : ""));
        } else {
            statusPill.setText("⏸ ផ្អាក");
        }

        if (Schedules.overrideOn(this)) {
            Schedules.Item si = Schedules.active(this);
            if (si != null) statusPill.setText("⏰ " + (si.end < 0 ? "" : "→" + Schedules.hhmm(si.end) + "  ") + statusPill.getText());
        }
        if (tickCount % 10 == 0) rebuildSchedules();

        long dur = ps.duration();
        long pos = ps.position();
        if (dur > 0) {
            seek.setMax((int) dur);
            durText.setText(Library.time(dur));
            if (!userSeeking) {
                seek.setProgress((int) pos);
                posText.setText(Library.time(pos));
            }
        }
    }
}
