package com.vietradio.android6;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.content.res.ColorStateList;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.*;

public class MainActivity extends Activity {
  private LinearLayout list;
  private EditText search;
  private TextView now;
  private AudioManager audio;
  private SharedPreferences prefs;
  private List<Station> stations;
  private Station current;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("radio", 0);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    stations = StationRepository.all();
    if (Build.VERSION.SDK_INT >= 23 &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 20);
    }
    buildUi();
  }

  private void buildUi() {
    // Scroll the complete screen so larger system fonts/display scaling cannot clip controls.
    ScrollView page = new ScrollView(this);
    page.setFillViewport(true);
    page.setClipToPadding(false);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(8), dp(4), dp(8), dp(8));
    page.addView(root, new ScrollView.LayoutParams(-1, -2));

    TextView title = new TextView(this);
    title.setText("📻 RADIO VIỆT NAM");
    title.setTextSize(24);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setPadding(dp(8), dp(10), dp(8), dp(10));
    root.addView(title, new LinearLayout.LayoutParams(-1, -2));

    search = new EditText(this);
    search.setSingleLine(true);
    search.setTextSize(16);
    search.setHint("Tìm kiếm kênh...");
    search.setPadding(dp(12), dp(8), dp(12), dp(8));
    root.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));

    now = new TextView(this);
    now.setText("Chọn một đài để nghe hoặc ghi âm");
    now.setTextSize(16);
    now.setGravity(Gravity.CENTER_VERTICAL);
    now.setPadding(dp(8), dp(10), dp(8), dp(10));
    root.addView(now, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout playback = new LinearLayout(this);
    playback.setOrientation(LinearLayout.HORIZONTAL);
    Button down = btn("−");
    Button play = btn("▶ / ⏸");
    Button stop = btn("■ Dừng");
    Button up = btn("+");
    playback.addView(down, weight(1));
    playback.addView(play, weight(1));
    playback.addView(stop, weight(1));
    playback.addView(up, weight(1));
    root.addView(playback, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    Button rec = btn("Ghi âm");
    Button timer = btn("Hẹn giờ");
    Button schedule = btn("Lịch");
    Button home = btn("Trang chính");
    actions.addView(rec, weight(1));
    actions.addView(timer, weight(1));
    actions.addView(schedule, weight(1));
    actions.addView(home, weight(1));
    root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

    TextView heading = new TextView(this);
    heading.setText("Danh sách kênh radio");
    heading.setTextSize(18);
    heading.setPadding(dp(8), dp(12), dp(8), dp(6));
    root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

    // No nested ScrollView and no fixed-height station rows.
    list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    root.addView(list, new LinearLayout.LayoutParams(-1, -2));
    setContentView(page);
    render(stations);

    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
      public void afterTextChanged(Editable e) {}
    });
    down.setOnClickListener(v -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI));
    up.setOnClickListener(v -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI));
    play.setOnClickListener(v -> { if (current != null) RadioPlayer.toggle(this, current); else toast("Hãy chọn một đài"); });
    stop.setOnClickListener(v -> RadioPlayer.stop());
    rec.setOnClickListener(v -> recordDialog());
    timer.setOnClickListener(v -> scheduleDialog());
    schedule.setOnClickListener(v -> {
      try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://voh.com.vn/"))); }
      catch (Exception e) { toast("Không mở được trang lịch"); }
    });
    home.setOnClickListener(v -> { search.setText(""); filter(""); now.setText("Trang chính • Chọn một đài"); page.smoothScrollTo(0, 0); });
  }

  private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
  private LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, dp(52), w); }

  private Button btn(String text) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(13);
    b.setTextColor(0xFF17212B);
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setPadding(dp(3), dp(4), dp(3), dp(4));
    if (Build.VERSION.SDK_INT >= 21) {
      b.setBackgroundTintList(ColorStateList.valueOf(0xFFE3F2FD));
    }
    return b;
  }

  private void render(List<Station> data) {
    list.removeAllViews();
    for (Station s : data) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(4), dp(3), dp(4), dp(3));

      TextView name = new TextView(this);
      name.setText(s.name);
      name.setTextSize(17);
      name.setGravity(Gravity.CENTER_VERTICAL);
      name.setIncludeFontPadding(true);
      name.setPadding(dp(8), dp(8), dp(4), dp(8));
      row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

      Button fav = btn(prefs.getBoolean("fav_" + s.name, false) ? "★" : "☆");
      Button play = btn("▶");
      row.addView(fav, new LinearLayout.LayoutParams(dp(52), dp(50)));
      row.addView(play, new LinearLayout.LayoutParams(dp(52), dp(50)));

      fav.setOnClickListener(v -> {
        boolean enabled = !prefs.getBoolean("fav_" + s.name, false);
        prefs.edit().putBoolean("fav_" + s.name, enabled).apply();
        fav.setText(enabled ? "★" : "☆");
      });
      View.OnClickListener choose = v -> {
        current = s;
        now.setText("Đã chọn: " + s.name);
        RadioPlayer.play(this, s);
      };
      name.setOnClickListener(choose);
      play.setOnClickListener(choose);
      list.addView(row, new LinearLayout.LayoutParams(-1, -2));
      View separator = new View(this);
      separator.setBackgroundColor(0xFFE0E0E0);
      list.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
    }
  }

  private void filter(String query) {
    List<Station> filtered = new ArrayList<>();
    for (Station s : stations) if (s.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) filtered.add(s);
    render(filtered);
  }

  private void recordDialog() {
    if (current == null) { toast("Hãy chọn đài trước"); return; }
    final String[] options = {"Ghi âm im lặng — không phát ra loa", "Ghi âm và tiếp tục nghe radio"};
    final int[] selected = {0};
    new AlertDialog.Builder(this).setTitle("Ghi âm • " + current.name)
      .setSingleChoiceItems(options, 0, (dialog, which) -> selected[0] = which)
      .setPositiveButton("Bắt đầu", (dialog, which) -> startRecording(current, selected[0] == 0, 0))
      .setNegativeButton("Hủy", null).show();
  }

  private void startRecording(Station station, boolean silent, int minutes) {
    if (station.url == null || station.url.trim().isEmpty()) { toast("Kênh này chưa có URL stream trực tiếp."); return; }
    if (!silent) RadioPlayer.play(this, station);
    Intent intent = new Intent(this, RadioRecordService.class);
    intent.putExtra("url", station.url);
    intent.putExtra("name", station.name);
    intent.putExtra("silent", silent);
    intent.putExtra("durationMinutes", minutes);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    toast(silent ? "Đang ghi âm im lặng, không phát ra loa" : "Đang ghi âm; chế độ nghe được bật");
  }

  private void scheduleDialog() {
    if (current == null) { toast("Hãy chọn đài trước"); return; }
    if (current.url == null || current.url.trim().isEmpty()) { toast("Kênh này chưa có URL stream trực tiếp nên chưa thể hẹn ghi âm."); return; }
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(12), dp(8), dp(12), dp(8));
    TimePicker time = new TimePicker(this);
    time.setIs24HourView(true);
    box.addView(time, new LinearLayout.LayoutParams(-1, -2));
    CheckBox silent = new CheckBox(this);
    silent.setText("Ghi âm im lặng — không phát ra loa");
    silent.setChecked(true);
    box.addView(silent, new LinearLayout.LayoutParams(-1, -2));
    TextView label = new TextView(this);
    label.setText("Thời lượng ghi âm (phút)");
    box.addView(label, new LinearLayout.LayoutParams(-1, -2));
    NumberPicker duration = new NumberPicker(this);
    duration.setMinValue(1);
    duration.setMaxValue(360);
    duration.setValue(60);
    box.addView(duration, new LinearLayout.LayoutParams(-1, -2));
    new AlertDialog.Builder(this).setTitle("Hẹn giờ • " + current.name).setView(box)
      .setPositiveButton("Đặt lịch", (dialog, which) -> {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, time.getCurrentHour());
        c.set(Calendar.MINUTE, time.getCurrentMinute());
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        Intent intent = new Intent(this, RecordAlarmReceiver.class);
        intent.putExtra("url", current.url);
        intent.putExtra("name", current.name);
        intent.putExtra("silent", silent.isChecked());
        intent.putExtra("durationMinutes", duration.getValue());
        int request = (int)(c.getTimeInMillis() / 60000L);
        PendingIntent pi = PendingIntent.getBroadcast(this, request, intent,
          PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        AlarmManager am = (AlarmManager)getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 19) am.setExact(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        else am.set(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        toast("Đã đặt lịch ghi âm " + (silent.isChecked() ? "im lặng" : "có âm thanh"));
      }).setNegativeButton("Hủy", null).show();
  }

  private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
