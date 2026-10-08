package com.ratanak.goenkaplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 10;

    private TextView title, timeText, hint;
    private SeekBar seek;
    private Button playBtn, batteryBtn;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private boolean userSeeking;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        if (Library.ensureLoaded(this)) {
            showList();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        Button choose = button("📁  ជ្រើសរើស Folder MP3");
        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FOLDER);
        });
        root.addView(choose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        batteryBtn = button("មិនឱ្យទូរស័ព្ទបញ្ឈប់ការលេង (Battery)");
        batteryBtn.setTextSize(14);
        batteryBtn.setOnClickListener(v -> askBatteryExemption());
        root.addView(batteryBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = new TextView(this);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        title.setText("សូមជ្រើសរើស Folder");
        root.addView(title);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeText.setText(Library.time(p) + " / " + Library.time(s.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                PlayerService ps = PlayerService.instance;
                if (ps != null) ps.seekTo(s.getProgress());
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        timeText = new TextView(this);
        timeText.setGravity(Gravity.CENTER);
        timeText.setTextSize(16);
        timeText.setText("0:00:00 / 0:00:00");
        root.addView(timeText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button prev = button("⏮");
        playBtn = button("▶");
        Button next = button("⏭");
        prev.setOnClickListener(v -> send(PlayerService.ACTION_PREV));
        playBtn.setOnClickListener(v -> send(PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> send(PlayerService.ACTION_NEXT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
        row.addView(prev, lp);
        row.addView(playBtn, new LinearLayout.LayoutParams(0, dp(64), 1.4f));
        row.addView(next, new LinearLayout.LayoutParams(0, dp(64), 1f));
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hint = new TextView(this);
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(13);
        hint.setPadding(0, dp(8), 0, dp(4));
        hint.setText("ចុចលើវគ្គណាមួយដើម្បីលេង");
        root.addView(hint);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent i = new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_PLAY_INDEX)
                    .putExtra(PlayerService.EXTRA_INDEX, position)
                    .putExtra(PlayerService.EXTRA_POS, 0L);
            startForegroundService(i);
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void send(String action) {
        if (Library.uris.isEmpty()) {
            Toast.makeText(this, "សូមជ្រើសរើស Folder មុន", Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            Library.prefs(this).edit()
                    .putString(Library.KEY_TREE, tree.toString())
                    .putInt(Library.KEY_INDEX, 0)
                    .putLong(Library.KEY_POS, 0)
                    .apply();
            if (Library.load(this, tree)) {
                showList();
                Toast.makeText(this, Library.names.size() + " files", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showList() {
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, Library.names);
        list.setAdapter(adapter);
        int last = Library.prefs(this).getInt(Library.KEY_INDEX, -1);
        if (last >= 0 && last < Library.names.size()) {
            title.setText(Library.names.get(last));
            long pos = Library.prefs(this).getLong(Library.KEY_POS, 0);
            timeText.setText("ចុច ▶ ដើម្បីលេងបន្តពី " + Library.time(pos));
            list.setItemChecked(last, true);
        } else {
            title.setText("ចុចលើវគ្គណាមួយ");
        }
    }

    private void refresh() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        batteryBtn.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);

        PlayerService ps = PlayerService.instance;
        if (ps == null) {
            playBtn.setText("▶");
            return;
        }
        int i = ps.index();
        if (i >= 0 && i < Library.names.size()) {
            title.setText(Library.names.get(i));
            if (list.getCheckedItemPosition() != i) list.setItemChecked(i, true);
        }
        playBtn.setText(ps.isPlaying() ? "⏸" : "▶");
        long dur = ps.duration();
        long pos = ps.position();
        if (dur > 0) {
            seek.setMax((int) dur);
            if (!userSeeking) {
                seek.setProgress((int) pos);
                timeText.setText(Library.time(pos) + " / " + Library.time(dur));
            }
        }
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
}
