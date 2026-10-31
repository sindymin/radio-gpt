package com.vietradio.android6;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import java.io.File;
import java.text.SimpleDateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private LinearLayout list;
  private AutoCompleteTextView search;
  private ArrayAdapter<String> searchSuggestions;
  private TextView now;
  private AudioManager audio;
  private SharedPreferences prefs;
  private List<Station> stations;
  private Station current;
  private Intent pendingScheduleIntent;
  private CheckBox playableOnly;
  private Button checkStreamsButton;
  private Button checkScheduleLinksButton;
  private Button allStationsButton;
  private Button favoritesButton;
  private LinearLayout selectedStationPanel;
  private TextView selectedStationName;
  private TextView recordingElapsed;
  private Button recordButton;
  private final Handler recordingHandler = new Handler(Looper.getMainLooper());
  private final Runnable recordingTicker = new Runnable() { public void run() { updateRecordingUi(); recordingHandler.postDelayed(this, 1000); } };
  private EqualizerIndicator selectedEqualizer;
  private TextView selectedVolumePercent;
  private TextView selectedStationStatus;
  private TextView selectedStationScheduleStatus;
  private TextView selectedProgramView;
  // Always-visible clock (top-right): style 0 = digital 24h, 1 = digital AM/PM, 2 = analog.
  private TextView titleView;
  private LinearLayout clockHolder;
  private TextView clockDigital;
  private TextView clockSolarDate;
  private TextView clockLunarDate;
  private int clockDateKey = -1;
  private AnalogClockView clockAnalog;
  private final SimpleDateFormat clockFmt24 = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
  private final SimpleDateFormat clockFmtAmPm = new SimpleDateFormat("hh:mm:ss a", Locale.US);
  private final Runnable clockTicker = new Runnable() {
    @Override public void run() { updateClock(); recordingHandler.postDelayed(this, 1000); }
  };
  private final java.util.HashMap<String, java.util.List<String>> programCache = new java.util.HashMap<>();
  private final java.util.HashMap<String, Long> programCacheTime = new java.util.HashMap<>();
  private final java.util.HashSet<String> programLoading = new java.util.HashSet<>();
  private boolean programTickerRunning;
  private final java.util.HashMap<String, Integer> programEmptyCount = new java.util.HashMap<>();
  private final ExecutorService programExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ProgramFetch"); t.setDaemon(true); return t; });
  private final Runnable programTicker = new Runnable() {
    @Override public void run() {
      refreshSelectedProgram();
      if (current != null) recordingHandler.postDelayed(this, 30000); else programTickerRunning = false;
    }
  };
  private Button selectedFavoriteButton;
  private Button selectedPlayButton;
  private final java.util.ArrayList<android.widget.SeekBar> boostSeeks = new java.util.ArrayList<>();
  private final java.util.ArrayList<TextView> boostValues = new java.util.ArrayList<>();
  private final Runnable saveVolumeRunnable = new Runnable() { @Override public void run() { persistVolume(); updateSelectedVolumePercent(); updateDockVolume(); } };
  private boolean showingFavorites = false;
  private final Map<String, Integer> streamStatus = new HashMap<>(); // -1=unknown, 1=responding, 0=unavailable
  private final Map<String, Integer> scheduleLinkStatus = new HashMap<>(); // 0=not checked, 1=has schedule, 2=no schedule today, -1=failed
  // Low-priority workers and limited concurrency keep older Android 6 devices responsive.
  private final ExecutorService checker = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "RadioStatusCheck");
    thread.setPriority(Thread.MIN_PRIORITY);
    return thread;
  });
  private final ExecutorService scheduleChecker = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "RadioScheduleCheck");
    thread.setPriority(Thread.MIN_PRIORITY);
    return thread;
  });
  private int scheduleChecksPending = 0;
  private int checksPending = 0;
  private LinearLayout localPlayerDock;
  private View mainContentRoot;
  private AlertDialog recordedFilesDialog;
  private TextView dockFileName, dockElapsed, dockRemaining, dockVolume;
  private Button dockPlayPause;
  private SeekBar dockSeek;
  private EqualizerIndicator dockEqualizer;
  private File dockFile;
  private long pendingResumeMs;
  // Recording durations (computed in the background, cached per file name + size)
  private final java.util.HashMap<String, Long> fileDurations = new java.util.HashMap<>();
  private final java.util.HashSet<String> durationInFlight = new java.util.HashSet<>();
  private final ExecutorService durationExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "FileDuration"); t.setDaemon(true); return t; });
  private final Runnable durationListRefresh = new Runnable() { @Override public void run() { refreshRecordedListMarks(); } };
  private JSONObject editingSchedule;
  private AlertDialog scheduleListDialog;
  // Automatic checks at app start: first every station's stream link, then every station's schedule link.
  private boolean autoChecked;
  private boolean autoChainSchedule;
  private boolean silentScheduleCheck;
  private Runnable recordedListRefresh;
  private long pendingResumeSince;
  private long lastResumeSave;
  private String dockOwnerName;
  private String lastRecordedChannelQuery = "";
  private String lastRecordedDateQuery = "";
  private final Handler dockHandler = new Handler(Looper.getMainLooper());
  private boolean dockSeeking = false;
  private final Runnable dockTicker = new Runnable() { public void run() { updateDockPlayback(); dockHandler.postDelayed(this, 500); } };

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    prefs = getSharedPreferences("radio", 0);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    stations = StationRepository.merge(StationRepository.all(), StationApi.loadCached(this));
    if (Build.VERSION.SDK_INT >= 23 &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 20);
    }
    loadCheckResults();
    RecordingSchedules.rearmAll(this);
    buildUi();
    processScheduleRecordingIntent(getIntent());
    RadioPlayer.setStateListener((stationName, isPlaying) -> {
      // A successful real playback is stronger evidence than the separate HTTP probe.
      if (isPlaying && stationName != null) streamStatus.put(stationName, 1);
      if (list != null) filter(search == null ? "" : search.getText().toString());
      updateSelectedStationPanel();
    });
    // Refresh in the background. If the network/API is unavailable, the bundled
    // list plus the last successful cached response remain available offline.
    refreshStationListFromApi();
    // Fallback: if the station-list refresh is slow or fails, still run the checks shortly after launch.
    recordingHandler.postDelayed(this::autoCheckOnce, 8000);
  }

  // ---- Remember each station's last link-check results across launches ----
  private void loadCheckResults() {
    if (stations == null) return;
    for (Station st : stations) {
      if (!streamStatus.containsKey(st.name) && prefs.contains("chk_stream_" + st.name))
        streamStatus.put(st.name, prefs.getInt("chk_stream_" + st.name, -1));
      if (!scheduleLinkStatus.containsKey(st.name) && prefs.contains("chk_sched_" + st.name))
        scheduleLinkStatus.put(st.name, prefs.getInt("chk_sched_" + st.name, 0));
    }
  }
  /** Saves finished results. If nothing at all succeeded (probably offline) the old saved results are kept. */
  private void saveCheckResults(boolean streams) {
    if (stations == null) return;
    boolean anyOk = false;
    for (Station st : stations) {
      Integer v = streams ? streamStatus.get(st.name) : scheduleLinkStatus.get(st.name);
      if (v != null && (streams ? v == 1 : (v == 1 || v == 2))) { anyOk = true; break; }
    }
    if (!anyOk) return;
    SharedPreferences.Editor e = prefs.edit();
    for (Station st : stations) {
      if (streams) {
        Integer v = streamStatus.get(st.name);
        if (v != null && v != -1) e.putInt("chk_stream_" + st.name, v);
      } else {
        Integer v = scheduleLinkStatus.get(st.name);
        if (v != null && v != 0) e.putInt("chk_sched_" + st.name, v);
      }
    }
    e.apply();
  }

  /** Runs once per launch: check all stream links, then (when that finishes) all schedule links. No popups. */
  private void autoCheckOnce() {
    if (autoChecked || stations == null || stations.isEmpty()) return;
    autoChecked = true;
    autoChainSchedule = true;
    silentScheduleCheck = true;
    checkAllStreams();
    if (checksPending <= 0) { autoChainSchedule = false; checkAllScheduleLinks(); }
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    processScheduleRecordingIntent(intent);
  }

  private void processScheduleRecordingIntent(Intent intent) {
    if (intent == null || !intent.getBooleanExtra("schedule_record_prefill", false)) return;
    pendingScheduleIntent = intent;
    scheduleDialog();
    intent.removeExtra("schedule_record_prefill");
  }

  private void refreshStationListFromApi() {
    checker.execute(() -> {
      List<Station> remote = StationApi.fetch();
      if (remote == null || remote.isEmpty()) { runOnUiThread(this::autoCheckOnce); return; }
      StationApi.saveCache(this, remote);
      List<Station> merged = StationRepository.merge(StationRepository.all(), remote);
      runOnUiThread(() -> {
        String selectedName = current == null ? null : current.name;
        stations = merged;
        loadCheckResults();
        refreshSearchSuggestions();
        if (selectedName != null) {
          for (Station station : stations) {
            if (station.name.equals(selectedName)) { current = station; break; }
          }
        }
        filter(search == null ? "" : search.getText().toString());
        autoCheckOnce();
      });
    });
  }

  private void buildUi() {
    // The list scrolls; the selected-station card, volume and recording controls stay fixed at the bottom.
    LinearLayout screen = new LinearLayout(this);
    screen.setOrientation(LinearLayout.VERTICAL);

    ScrollView page = new ScrollView(this);
    page.setFillViewport(false);
    page.setClipToPadding(false);
    LinearLayout root = new LinearLayout(this);
    mainContentRoot = root;
    root.setOrientation(LinearLayout.VERTICAL);
    root.setFocusableInTouchMode(true);
    root.setPadding(dp(8), dp(4), dp(8), dp(4));
    page.addView(root, new ScrollView.LayoutParams(-1, -2));
    screen.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
    localPlayerDock = buildLocalPlayerDock();
    localPlayerDock.setVisibility(View.GONE); // Mỗi lần mở app, khung phát file luôn ẩn.
    screen.addView(localPlayerDock, new LinearLayout.LayoutParams(-1, -2));

    TextView title = new TextView(this);
    title.setText("📻 RADIO VIỆT NAM");
    title.setTextSize(24);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setPadding(dp(8), dp(8), dp(8), dp(8));
    title.setTextSize(20);
    titleView = title;
    root.addView(title, new LinearLayout.LayoutParams(-1, -2));

    search = new AutoCompleteTextView(this);
    search.setSingleLine(true);
    search.setTextSize(16);
    search.setHint("Tìm kiếm kênh...");
    search.setPadding(dp(12), dp(6), dp(12), dp(6));
    search.setShowSoftInputOnFocus(true);
    search.setThreshold(1);
    search.setDropDownHeight(dp(240));
    search.setDropDownWidth(-1);
    search.setCompletionHint("Chọn kênh để mở");
    searchSuggestions = new StationSuggestionAdapter(stationNames());
    search.setAdapter(searchSuggestions);
    root.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
    search.setOnItemClickListener((parent, view, position, id) -> {
      Object selected = parent.getItemAtPosition(position);
      String stationName = String.valueOf(selected);
      for (Station station : stations) {
        if (station.name.equals(stationName)) {
          current = station;
          now.setText("Đang mở: " + station.name);
          updateSelectedStationPanel();
          startStation(station);
          break;
        }
      }
      search.dismissDropDown();
      search.clearFocus();
      InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    });

    now = new TextView(this);
    now.setText("Chọn một đài để nghe hoặc ghi âm");
    now.setTextSize(14);
    now.setGravity(Gravity.CENTER_VERTICAL);
    now.setPadding(dp(8), dp(6), dp(8), dp(6));
    root.addView(now, new LinearLayout.LayoutParams(-1, -2));

    playableOnly = new CheckBox(this);
    playableOnly.setText("Chỉ hiện kênh máy chủ đang phản hồi");
    playableOnly.setTextSize(14);
    playableOnly.setPadding(dp(6), dp(2), dp(6), dp(2));
    root.addView(playableOnly, new LinearLayout.LayoutParams(-1, -2));
    playableOnly.setOnCheckedChangeListener((button, checked) -> filter(search.getText().toString()));

    // Menu trang chính: các tiện ích được ẩn cho đến khi người dùng mở menu.
    Button menuButton = btn("☰ Menu");
    menuButton.setTextSize(16);
    root.addView(menuButton, new LinearLayout.LayoutParams(-1, dp(46)));

    LinearLayout menuPanel = new LinearLayout(this);
    menuPanel.setOrientation(LinearLayout.VERTICAL);
    menuPanel.setPadding(dp(4), dp(4), dp(4), dp(6));
    menuPanel.setVisibility(View.GONE);
    root.addView(menuPanel, new LinearLayout.LayoutParams(-1, -2));

    checkStreamsButton = btn("Kiểm tra trạng thái các đài");
    menuPanel.addView(checkStreamsButton, new LinearLayout.LayoutParams(-1, dp(44)));
    checkStreamsButton.setOnClickListener(v -> checkAllStreams());

    checkScheduleLinksButton = btn("Kiểm tra link lịch tất cả các kênh");
    menuPanel.addView(checkScheduleLinksButton, new LinearLayout.LayoutParams(-1, dp(44)));
    checkScheduleLinksButton.setOnClickListener(v -> checkAllScheduleLinks());

    Button scheduleAll = btn("Lịch phát sóng");
    menuPanel.addView(scheduleAll, new LinearLayout.LayoutParams(-1, dp(44)));
    scheduleAll.setOnClickListener(v -> startActivity(new Intent(this, RadioScheduleActivity.class)));

    Button timer = btn("◷ Hẹn giờ ghi âm");
    menuPanel.addView(timer, new LinearLayout.LayoutParams(-1, dp(44)));
    timer.setOnClickListener(v -> scheduleDialog());

    Button scheduleList = btn("▤ Danh sách lịch hẹn ghi âm");
    menuPanel.addView(scheduleList, new LinearLayout.LayoutParams(-1, dp(44)));
    scheduleList.setOnClickListener(v -> showRecordingScheduleListDialog());

    Button clockStyleButton = btn("🕒 Kiểu đồng hồ");
    menuPanel.addView(clockStyleButton, new LinearLayout.LayoutParams(-1, dp(44)));
    clockStyleButton.setOnClickListener(v -> showClockStyleDialog());

    Button recordings = btn("▣ Danh sách các file đã ghi âm");
    menuPanel.addView(recordings, new LinearLayout.LayoutParams(-1, dp(44)));
    recordings.setOnClickListener(v -> showRecordedFiles());

    menuButton.setOnClickListener(v -> {
      boolean showMenu = menuPanel.getVisibility() != View.VISIBLE;
      menuPanel.setVisibility(showMenu ? View.VISIBLE : View.GONE);
      menuButton.setText(showMenu ? "✕ Đóng menu" : "☰ Menu");
    });

    TextView heading = new TextView(this);
    heading.setText("Danh sách kênh radio");
    heading.setTextSize(18);
    heading.setPadding(dp(8), dp(8), dp(8), dp(4));
    root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout stationFilters = new LinearLayout(this);
    stationFilters.setOrientation(LinearLayout.HORIZONTAL);
    allStationsButton = btn("✓ Tất cả kênh");
    favoritesButton = btn("★ Kênh yêu thích");
    stationFilters.addView(allStationsButton, weight(1));
    stationFilters.addView(favoritesButton, weight(1));
    root.addView(stationFilters, new LinearLayout.LayoutParams(-1, -2));
    allStationsButton.setOnClickListener(v -> {
      showingFavorites = false;
      updateStationFilterButtons();
      filter(search.getText().toString());
    });
    favoritesButton.setOnClickListener(v -> {
      showingFavorites = true;
      updateStationFilterButtons();
      filter(search.getText().toString());
    });

    list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    root.addView(list, new LinearLayout.LayoutParams(-1, -2));

    // Selected station card is hidden at startup and appears after a station is selected.
    selectedStationPanel = new LinearLayout(this);
    selectedStationPanel.setOrientation(LinearLayout.VERTICAL);
    selectedStationPanel.setPadding(dp(8), dp(4), dp(8), dp(4));
    selectedStationPanel.setBackgroundColor(0xFFD6E7FB);
    LinearLayout selectedTitleRow = new LinearLayout(this);
    selectedTitleRow.setOrientation(LinearLayout.HORIZONTAL);
    selectedTitleRow.setGravity(Gravity.CENTER_VERTICAL);
    selectedStationName = new TextView(this);
    selectedStationName.setTextSize(16);
    selectedStationName.setTypeface(null, android.graphics.Typeface.BOLD);
    selectedStationName.setPadding(dp(4), dp(2), dp(4), dp(2));
    selectedTitleRow.addView(selectedStationName, new LinearLayout.LayoutParams(0, -2, 1));
    Button closeSelectedPanel = btn("✕");
    closeSelectedPanel.setMinWidth(dp(42));
    closeSelectedPanel.setContentDescription("Đóng khung kênh đang phát");
    selectedTitleRow.addView(closeSelectedPanel, new LinearLayout.LayoutParams(dp(44), dp(40)));
    closeSelectedPanel.setOnClickListener(v -> {
      // Closing the currently playing station panel also stops radio playback.
      // Clear current first so the playback-state callback cannot reopen the panel.
      current = null;
      RadioPlayer.stop();
      now.setText("Đã dừng radio");
      selectedStationPanel.setVisibility(View.GONE);
    });
    recordingElapsed = new TextView(this);
    recordingElapsed.setText("00:00");
    recordingElapsed.setTextSize(13);
    recordingElapsed.setTypeface(null, android.graphics.Typeface.BOLD);
    recordingElapsed.setTextColor(0xFFB71C1C);
    recordingElapsed.setGravity(Gravity.CENTER);
    recordingElapsed.setPadding(dp(4), 0, dp(4), 0);
    recordingElapsed.setVisibility(View.GONE);
    selectedTitleRow.addView(recordingElapsed, new LinearLayout.LayoutParams(-2, dp(30)));
    TextView selectedVolumeIcon = new TextView(this);
    selectedVolumeIcon.setText("🔊");
    selectedVolumeIcon.setTextSize(14);
    selectedVolumeIcon.setGravity(Gravity.CENTER);
    selectedVolumeIcon.setContentDescription("Biểu tượng âm lượng");
    selectedTitleRow.addView(selectedVolumeIcon, new LinearLayout.LayoutParams(dp(24), dp(30)));
    selectedVolumePercent = new TextView(this);
    selectedVolumePercent.setTextSize(12);
    selectedVolumePercent.setTypeface(null, android.graphics.Typeface.BOLD);
    selectedVolumePercent.setGravity(Gravity.CENTER);
    selectedVolumePercent.setPadding(dp(4), 0, dp(5), 0);
    selectedVolumePercent.setMinWidth(dp(42));
    selectedVolumePercent.setContentDescription("Phần trăm âm lượng");
    selectedTitleRow.addView(selectedVolumePercent, new LinearLayout.LayoutParams(-2, dp(30)));
    selectedEqualizer = new EqualizerIndicator(this);
    selectedEqualizer.setContentDescription("Bộ hiển thị âm thanh khi radio đang phát");
    selectedTitleRow.addView(selectedEqualizer, new LinearLayout.LayoutParams(dp(38), dp(30)));
    selectedStationPanel.addView(selectedTitleRow, new LinearLayout.LayoutParams(-1, -2));
    selectedStationStatus = new TextView(this);
    selectedStationStatus.setTextSize(12);
    selectedStationStatus.setPadding(dp(4), 0, dp(4), dp(1));
    selectedStationPanel.addView(selectedStationStatus, new LinearLayout.LayoutParams(-1, -2));
    selectedStationScheduleStatus = new TextView(this);
    selectedStationScheduleStatus.setTextSize(12);
    selectedStationScheduleStatus.setPadding(dp(4), 0, dp(4), dp(2));
    selectedStationPanel.addView(selectedStationScheduleStatus, new LinearLayout.LayoutParams(-1, -2));
    // "Now airing" box: only visible when the current station has a schedule and a programme is on air.
    selectedProgramView = new TextView(this);
    selectedProgramView.setTextSize(14);
    selectedProgramView.setPadding(dp(10), dp(6), dp(10), dp(6));
    selectedProgramView.setBackgroundColor(0xFFBED6F3);
    selectedProgramView.setVisibility(View.GONE);
    LinearLayout.LayoutParams programLp = new LinearLayout.LayoutParams(-1, -2);
    programLp.setMargins(dp(4), dp(2), dp(4), dp(4));
    selectedStationPanel.addView(selectedProgramView, programLp);
    LinearLayout selectedControls = new LinearLayout(this);
    selectedControls.setOrientation(LinearLayout.HORIZONTAL);
    selectedFavoriteButton = btn("☆ Yêu thích");
    selectedPlayButton = btn("▶ Phát");
    Button selectedScheduleButton = btn("Lịch");
    recordButton = btn("● Ghi âm");
    Button rec = recordButton;
    selectedControls.addView(selectedFavoriteButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedControls.addView(selectedPlayButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedControls.addView(selectedScheduleButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedControls.addView(rec, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedStationPanel.addView(selectedControls, new LinearLayout.LayoutParams(-1, -2));

    // Put volume controls inside the selected/playing station card.
    LinearLayout volumeBar = new LinearLayout(this);
    volumeBar.setOrientation(LinearLayout.HORIZONTAL);
    volumeBar.setGravity(Gravity.CENTER_VERTICAL);
    volumeBar.setPadding(dp(2), dp(1), dp(2), dp(1));
    Button down = btn("− Giảm âm lượng");
    Button up = btn("Tăng âm lượng +");
    volumeBar.addView(down, new LinearLayout.LayoutParams(0, dp(42), 1));
    volumeBar.addView(up, new LinearLayout.LayoutParams(0, dp(42), 1));
    selectedStationPanel.addView(volumeBar, new LinearLayout.LayoutParams(-1, dp(44)));

    selectedStationPanel.addView(createBoostBar(), new LinearLayout.LayoutParams(-1, -2));

    selectedStationPanel.setVisibility(View.GONE);
    screen.addView(selectedStationPanel, new LinearLayout.LayoutParams(-1, -2));

    selectedFavoriteButton.setOnClickListener(v -> {
      if (current == null) return;
      boolean enabled = !prefs.getBoolean("fav_" + current.name, false);
      prefs.edit().putBoolean("fav_" + current.name, enabled).apply();
      updateSelectedStationPanel();
      updateStationFilterButtons();
      if (showingFavorites && !enabled) filter(search.getText().toString());
      else filter(search.getText().toString());
    });
    selectedPlayButton.setOnClickListener(v -> {
      if (current == null) { toast("Hãy chọn một đài"); return; }
      if (!RadioPlayer.isCurrentStation(current.name)) applyStationAudio(current);
      RadioPlayer.toggle(MainActivity.this, current);
      updateSelectedStationPanel();
    });
    selectedScheduleButton.setOnClickListener(v -> {
      if (current == null) { toast("Hãy chọn một đài"); return; }
      Intent intent = new Intent(MainActivity.this, RadioScheduleActivity.class);
      intent.putExtra("stationName", current.name);
      startActivity(intent);
    });

    clockDigital = new TextView(this);
    clockDigital.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
    clockDigital.setTextSize(15);
    clockDigital.setTextColor(0xFF222222);
    clockDigital.setGravity(Gravity.CENTER);
    clockAnalog = new AnalogClockView(this);
    clockHolder = new LinearLayout(this);
    clockHolder.setOrientation(LinearLayout.VERTICAL);
    clockHolder.setGravity(Gravity.CENTER);
    clockHolder.setPadding(dp(6), dp(4), dp(6), dp(4));
    android.graphics.drawable.GradientDrawable clockBg = new android.graphics.drawable.GradientDrawable();
    clockBg.setColor(0xF2FFFFFF);
    clockBg.setCornerRadius(dp(8));
    clockBg.setStroke(dp(1), 0xFFBDBDBD);
    clockHolder.setBackground(clockBg);
    clockHolder.addView(clockDigital, new LinearLayout.LayoutParams(-2, -2));
    clockHolder.addView(clockAnalog, new LinearLayout.LayoutParams(-2, -2));
    clockSolarDate = new TextView(this);
    clockSolarDate.setTextSize(11);
    clockSolarDate.setTextColor(0xFF222222);
    clockSolarDate.setGravity(Gravity.CENTER);
    clockHolder.addView(clockSolarDate, new LinearLayout.LayoutParams(-2, -2));
    clockLunarDate = new TextView(this);
    clockLunarDate.setTextSize(11);
    clockLunarDate.setTextColor(0xFF8A2B0B);
    clockLunarDate.setGravity(Gravity.CENTER);
    clockHolder.addView(clockLunarDate, new LinearLayout.LayoutParams(-2, -2));
    Button exitButton = btn("Tắt app");
    exitButton.setTextSize(11);
    exitButton.setMinHeight(0);
    exitButton.setMinimumHeight(0);
    exitButton.setPadding(dp(8), dp(2), dp(8), dp(2));
    exitButton.setContentDescription("Tắt app. Ứng dụng sẽ chạy ngầm");
    exitButton.setOnClickListener(v -> {
      // Close the screen but keep the app running in the background (playback, recordings and schedules continue).
      toast("Ứng dụng đang chạy ngầm. Mở lại bằng biểu tượng app hoặc thông báo.");
      moveTaskToBack(true);
    });
    LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-1, dp(28));
    exitLp.topMargin = dp(3);
    clockHolder.addView(exitButton, exitLp);
    clockHolder.setContentDescription("Đồng hồ. Chạm để đổi kiểu đồng hồ");
    clockHolder.setOnClickListener(v -> showClockStyleDialog());
    FrameLayout frame = new FrameLayout(this);
    frame.addView(screen, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
    clockLp.setMargins(0, dp(6), dp(8), 0);
    frame.addView(clockHolder, clockLp);
    applyClockStyle();
    setContentView(frame);
    root.requestFocus();
    search.clearFocus();
    render(stations);
    updateSelectedStationPanel();

    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
      public void afterTextChanged(Editable e) {}
    });
    down.setOnClickListener(v -> {
      audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
      persistVolume();
      updateSelectedVolumePercent();
    });
    up.setOnClickListener(v -> {
      audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
      persistVolume();
      updateSelectedVolumePercent();
    });
    rec.setOnClickListener(v -> { if (isRecording()) stopRecording(); else recordDialog(); });
    recordingHandler.post(recordingTicker);
  }

  // ---- Per-station volume / boost memory, boost sliders, button tints ----
  @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
    if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
      // Hardware volume keys: remember the resulting level for the current station shortly afterwards.
      recordingHandler.removeCallbacks(saveVolumeRunnable);
      recordingHandler.postDelayed(saveVolumeRunnable, 500);
    }
    return super.onKeyDown(keyCode, event);
  }
  private boolean audioOwnerIsFile() {
    return dockFile != null && localPlayerDock != null && localPlayerDock.getVisibility() == View.VISIBLE
      && RadioPlayer.hasPlayer() && (current == null || !RadioPlayer.isCurrentStation(current.name));
  }
  /** Station whose saved volume/boost applies now: the station of the recorded file being played, else the current station. */
  private String audioOwnerName() {
    if (audioOwnerIsFile()) return dockOwnerName;
    return current == null ? null : current.name;
  }
  private void persistVolume() {
    String n = audioOwnerName();
    if (audio == null || n == null) return;
    prefs.edit().putInt("stvol_" + n, audio.getStreamVolume(AudioManager.STREAM_MUSIC)).apply();
  }
  private void persistBoost(int mb) {
    String n = audioOwnerName();
    if (n != null) prefs.edit().putInt("stboost_" + n, mb).apply();
    else if (audioOwnerIsFile()) prefs.edit().putInt("fileboost", mb).apply();
  }
  /** Recording file names are "<station name, sanitized>_yyyyMMdd_HHmmss.ext": find the station they came from. */
  private Station stationForFile(File f) {
    if (f == null || stations == null) return null;
    String name = f.getName();
    Station best = null; int bestLen = -1;
    for (Station st : stations) {
      String safe = st.name.replaceAll("[^a-zA-Z0-9À-ỹ _-]", "_");
      if (name.startsWith(safe + "_") && safe.length() > bestLen) { best = st; bestLen = safe.length(); }
    }
    return best;
  }
  private static String foldText(String t) {
    if (t == null) return "";
    String n = java.text.Normalizer.normalize(t.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "").replace('đ', 'd').replace('_', ' ');
  }
  /** Restore the volume and boost remembered for this station (defaults: unchanged volume, default boost). */
  private void applyStationAudio(Station s) {
    if (s == null) return;
    int vol = prefs.getInt("stvol_" + s.name, -1);
    if (vol >= 0 && audio != null) {
      try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(vol, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0); } catch (Exception ignored) {}
    }
    RadioPlayer.setBoostMb(this, prefs.getInt("stboost_" + s.name, RadioPlayer.defaultBoostMb()));
    syncBoostSliders(); updateSelectedVolumePercent(); updateDockVolume();
  }
  private void startStation(Station s) {
    saveResumePosition();
    applyStationAudio(s);
    RadioPlayer.play(this, s);
  }
  private void syncBoostSliders() {
    int db = RadioPlayer.getBoostMb(this) / 100;
    for (int i = 0; i < boostSeeks.size(); i++) {
      boostSeeks.get(i).setProgress(db);
      boostValues.get(i).setText("+" + db + " dB");
    }
  }
  private LinearLayout createBoostBar() {
    LinearLayout bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(6), dp(1), dp(6), dp(1));
    TextView label = new TextView(this);
    label.setText("Khuếch đại");
    label.setTextSize(12);
    bar.addView(label, new LinearLayout.LayoutParams(-2, -2));
    final TextView value = new TextView(this);
    value.setTextSize(12);
    value.setTypeface(null, android.graphics.Typeface.BOLD);
    value.setGravity(Gravity.CENTER);
    value.setMinWidth(dp(60));
    final android.widget.SeekBar seek = new android.widget.SeekBar(this);
    seek.setMax(40);
    int db = RadioPlayer.getBoostMb(this) / 100;
    seek.setProgress(db);
    value.setText("+" + db + " dB");
    seek.setContentDescription("Thanh trượt mức khuếch đại âm thanh");
    seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(android.widget.SeekBar b, int progress, boolean fromUser) {
        value.setText("+" + progress + " dB");
        if (fromUser) {
          RadioPlayer.setBoostMb(MainActivity.this, progress * 100);
          persistBoost(progress * 100);
          syncBoostSliders();
        }
      }
      @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
      @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
    });
    boostSeeks.add(seek); boostValues.add(value);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
    lp.setMargins(dp(6), 0, dp(6), 0);
    bar.addView(seek, lp);
    bar.addView(value, new LinearLayout.LayoutParams(-2, -2));
    return bar;
  }
  /** Keeps the normal button shape/style; color == null restores the default look (same as Yêu thích / Lịch). */
  private void tintButton(Button b, Integer color) {
    android.graphics.drawable.Drawable d = b.getBackground();
    if (d == null) return;
    d = d.mutate();
    if (color == null) d.clearColorFilter();
    else d.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP);
    b.invalidate();
  }

  /** Shows the programme airing now (with start/end time) for the current station; hidden if there is no schedule. */
  private void refreshSelectedProgram() {
    if (selectedProgramView == null) return;
    if (current == null) { selectedProgramView.setVisibility(View.GONE); return; }
    final String name = current.name;
    java.util.List<String> entries = programCache.get(name);
    Long fetchedAt = programCacheTime.get(name);
    long now = System.currentTimeMillis();
    // Empty results are retried with a growing delay (45 s, 90 s, 3 min, then 5 min) since the schedule server is flaky.
    int emptyTries = programEmptyCount.containsKey(name) ? programEmptyCount.get(name) : 0;
    long retryDelay = emptyTries <= 0 ? 45000L : (emptyTries == 1 ? 90000L : (emptyTries == 2 ? 180000L : 300000L));
    boolean stale = entries == null || fetchedAt == null
      || (entries.isEmpty() ? now - fetchedAt > retryDelay : !sameDayMillis(fetchedAt, now));
    if (stale && !programLoading.contains(name)) {
      programLoading.add(name);
      programExecutor.execute(() -> {
        final java.util.List<String> fetched = ScheduleFetcher.fetchEntries(name);
        runOnUiThread(() -> {
          programLoading.remove(name);
          if (fetched.isEmpty()) programEmptyCount.put(name, (programEmptyCount.containsKey(name) ? programEmptyCount.get(name) : 0) + 1);
          else programEmptyCount.remove(name);
          programCache.put(name, fetched);
          programCacheTime.put(name, System.currentTimeMillis());
          refreshSelectedProgram();
        });
      });
    }
    ScheduleFetcher.Program p = ScheduleFetcher.currentProgram(entries);
    if (p == null) { selectedProgramView.setVisibility(View.GONE); return; }
    String range = ScheduleFetcher.formatMinute(p.startMin) + " – " + ScheduleFetcher.formatMinute(p.endMin);
    android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("Chương trình đang phát\n");
    sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), 0, sb.length(), 0);
    int titleStart = sb.length();
    sb.append(p.title.isEmpty() ? "(không rõ tên chương trình)" : p.title);
    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), titleStart, sb.length(), 0);
    sb.append("\nBắt đầu ").append(ScheduleFetcher.formatMinute(p.startMin)).append(" • Kết thúc ").append(ScheduleFetcher.formatMinute(p.endMin));
    selectedProgramView.setText(sb);
    selectedProgramView.setContentDescription("Chương trình đang phát " + p.title + ", " + range);
    selectedProgramView.setVisibility(View.VISIBLE);
  }
  private static boolean sameDayMillis(long a, long b) {
    Calendar x = Calendar.getInstance(); x.setTimeInMillis(a);
    Calendar y = Calendar.getInstance(); y.setTimeInMillis(b);
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
  }

  private void updateSelectedStationPanel() {
    if (selectedStationPanel == null) return;
    if (current == null) {
      selectedStationPanel.setVisibility(View.GONE);
      return;
    }
    if (!programTickerRunning) { programTickerRunning = true; recordingHandler.postDelayed(programTicker, 30000); }
    refreshSelectedProgram();
    selectedStationPanel.setVisibility(View.VISIBLE);
    selectedStationName.setText(current.name);
    updateSelectedVolumePercent();
    if (selectedEqualizer != null) selectedEqualizer.setActive(RadioPlayer.isPlayingStation(current.name));
    String playbackState;
    if (RadioPlayer.isBufferingStation(current.name)) playbackState = "Đang tải dữ liệu radio…";
    else if (RadioPlayer.isPlayingStation(current.name)) playbackState = "Đang phát radio";
    else if (RadioPlayer.isCurrentStation(current.name)) playbackState = "Đang tạm ngưng";
    else playbackState = "Đã chọn kênh";
    selectedStationStatus.setText(playbackState + " • " + statusText(current));
    selectedStationScheduleStatus.setText(scheduleLinkStatusText(current));
    selectedFavoriteButton.setText(prefs.getBoolean("fav_" + current.name, false) ? "★ Đã yêu thích" : "☆ Yêu thích");
    tintButton(selectedPlayButton, RadioPlayer.isPlayingStation(current.name) ? Integer.valueOf(0xFFA8DDB4) : null);
    if (RadioPlayer.isBufferingStation(current.name)) {
      selectedPlayButton.setText("⏳ Đang tải…");
      selectedPlayButton.setContentDescription("Đang tải radio; chạm để tạm ngưng");
    } else if (RadioPlayer.isPlayingStation(current.name)) {
      selectedPlayButton.setText("Ⅱ Tạm dừng");
      selectedPlayButton.setContentDescription("Tạm ngưng radio");
    } else {
      selectedPlayButton.setText("▶ Phát");
      selectedPlayButton.setContentDescription("Phát radio");
    }
  }

  private void updateSelectedVolumePercent() {
    if (selectedVolumePercent == null || audio == null) return;
    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    int percent = max <= 0 ? 0 : Math.round(volume * 100f / max);
    selectedVolumePercent.setText(percent + "%");
    selectedVolumePercent.setContentDescription("Âm lượng " + percent + " phần trăm");
  }

  @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
    boolean handled = super.onKeyUp(keyCode, event);
    if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
      if (selectedVolumePercent != null) updateSelectedVolumePercent();
    }
    return handled;
  }

  /** Lightweight animated bars shown only while the selected station is really playing. */
  private final class EqualizerIndicator extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;
    private final int barCount = 5;

    EqualizerIndicator(Context context) {
      super(context);
      paint.setColor(0xFF1976D2);
      paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setActive(boolean value) {
      if (active != value) {
        active = value;
        invalidate();
      }
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (getWidth() <= 0 || getHeight() <= 0) return;
      float width = getWidth();
      float height = getHeight();
      float step = width / (barCount + 1f);
      float stroke = Math.max(dp(2), step * 0.22f);
      paint.setStrokeWidth(stroke);
      // Keep a stable baseline visible in every state. Bars rise from this baseline
      // only while the selected station is actually playing.
      float baselineY = height - Math.max(dp(2), stroke / 2f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      canvas.drawLine(step * 0.45f, baselineY, width - step * 0.45f, baselineY, paint);
      if (!active) return;
      double time = android.os.SystemClock.uptimeMillis() / 145.0;
      for (int i = 0; i < barCount; i++) {
        double wave = (Math.sin(time + i * 1.15) + 1.0) / 2.0;
        double wave2 = (Math.sin(time * 0.63 + i * 0.8 + 1.4) + 1.0) / 2.0;
        float barHeight = (float) ((0.22 + 0.68 * (0.62 * wave + 0.38 * wave2)) * (height - stroke));
        float x = step * (i + 1);
        canvas.drawLine(x, baselineY - barHeight, x, baselineY, paint);
      }
      postInvalidateDelayed(120);
    }
  }

  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    // Tapping anywhere outside the search field dismisses the soft keyboard.
    if (event.getAction() == MotionEvent.ACTION_DOWN && search != null && search.hasFocus()) {
      Rect searchBounds = new Rect();
      search.getGlobalVisibleRect(searchBounds);
      int x = (int) event.getRawX();
      int y = (int) event.getRawY();
      if (!searchBounds.contains(x, y)) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
      }
    }
    return super.dispatchTouchEvent(event);
  }

  private void openExternalUrl(String url) {
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    } catch (Exception e) {
      toast("Không mở được trình duyệt: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
    }
  }

  private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
  private LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, dp(52), w); }

  private Button btn(String text) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(13);
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setPadding(dp(3), dp(4), dp(3), dp(4));
    return b;
  }

  private void render(List<Station> data) {
    list.removeAllViews();
    for (Station s : data) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(4), dp(3), dp(4), dp(3));
      // Light-green background for the station that is currently active (playing, loading or paused).
      if (RadioPlayer.isCurrentStation(s.name)) row.setBackgroundColor(0xFFC8E6C9);

      LinearLayout stationInfo = new LinearLayout(this);
      stationInfo.setOrientation(LinearLayout.VERTICAL);
      stationInfo.setGravity(Gravity.CENTER_VERTICAL);
      // Small playback indicator sits beside the station name. The previous
      // oversized indicator at the far left has been removed.
      LinearLayout titleLine = new LinearLayout(this);
      titleLine.setOrientation(LinearLayout.HORIZONTAL);
      titleLine.setGravity(Gravity.CENTER_VERTICAL);

      TextView dot = new TextView(this);
      boolean stationPlaying = RadioPlayer.isPlayingStation(s.name);
      // Show a green dot only for the station that is currently playing.
      // Stopped stations have no dot at all (keep the slot width for alignment).
      dot.setText(stationPlaying ? "●" : "");
      dot.setTextSize(14);
      dot.setGravity(Gravity.CENTER);
      dot.setWidth(dp(18));
      dot.setTextColor(0xFF16A34A);
      dot.setContentDescription(stationPlaying ? "Đang phát" : "");
      titleLine.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(30)));

      TextView name = new TextView(this);
      name.setText(s.name);
      name.setTextSize(17);
      name.setGravity(Gravity.CENTER_VERTICAL);
      name.setIncludeFontPadding(true);
      name.setPadding(dp(3), dp(4), dp(4), dp(2));
      titleLine.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      stationInfo.addView(titleLine, new LinearLayout.LayoutParams(-1, -2));
      TextView status = new TextView(this);
      status.setTextSize(12);
      status.setPadding(dp(19), 0, dp(4), dp(4));
      status.setText(statusText(s));
      stationInfo.addView(status, new LinearLayout.LayoutParams(-1, -2));
      TextView scheduleStatus = new TextView(this);
      scheduleStatus.setTextSize(12);
      scheduleStatus.setPadding(dp(19), 0, dp(4), dp(4));
      scheduleStatus.setText(scheduleLinkStatusText(s));
      scheduleStatus.setTextColor(0xFF555555);
      stationInfo.addView(scheduleStatus, new LinearLayout.LayoutParams(-1, -2));
      row.addView(stationInfo, new LinearLayout.LayoutParams(0, -2, 1));

      Button fav = btn(prefs.getBoolean("fav_" + s.name, false) ? "★" : "☆");
      // This station's button is ▶ when stopped and ■ while its stream is active/loading.
      // Three states: playing (■ green), loading (⏳), stopped (▶).
      final boolean btnPlaying = RadioPlayer.isPlayingStation(s.name);
      final boolean btnLoading = !btnPlaying && RadioPlayer.isLoadingStation(s.name);
      Button play = btn(btnPlaying ? "■" : (btnLoading ? "⏳" : "▶"));
      if (btnPlaying) { play.setTextColor(0xFF16A34A); play.setContentDescription("Đang phát, bấm để dừng kênh " + s.name); }
      else if (btnLoading) { play.setContentDescription("Đang tải, bấm để hủy kênh " + s.name); }
      else play.setContentDescription("Đã dừng, bấm để phát kênh " + s.name);
      Button schedule = btn("Lịch");
      schedule.setTextSize(12);
      schedule.setContentDescription("Mở lịch phát sóng của " + s.name);
      row.addView(fav, new LinearLayout.LayoutParams(dp(46), dp(50)));
      row.addView(play, new LinearLayout.LayoutParams(dp(46), dp(50)));
      row.addView(schedule, new LinearLayout.LayoutParams(dp(54), dp(50)));
      schedule.setOnClickListener(v -> {
        Intent intent = new Intent(MainActivity.this, RadioScheduleActivity.class);
        intent.putExtra("stationName", s.name);
        startActivity(intent);
      });

      fav.setOnClickListener(v -> {
        boolean enabled = !prefs.getBoolean("fav_" + s.name, false);
        prefs.edit().putBoolean("fav_" + s.name, enabled).apply();
        fav.setText(enabled ? "★" : "☆");
        updateStationFilterButtons();
        if (showingFavorites && !enabled) filter(search.getText().toString());
      });
      View.OnClickListener choose = v -> {
        current = s;
        now.setText("Đang mở: " + s.name);
        updateSelectedStationPanel();
        startStation(s);
      };
      // Tapping the station name, its information area, or unused row space
      // should open that station immediately, just like the play button.
      name.setClickable(true);
      name.setOnClickListener(choose);
      stationInfo.setOnClickListener(choose);
      row.setOnClickListener(choose);
      play.setOnClickListener(v -> {
        if (btnPlaying || btnLoading) {
          RadioPlayer.stop();
          now.setText("Đã dừng: " + s.name);
          updateSelectedStationPanel();
        } else {
          current = s;
          now.setText("Đang mở: " + s.name);
          updateSelectedStationPanel();
          startStation(s);
        }
        if (list != null) filter(search == null ? "" : search.getText().toString());
      });
      list.addView(row, new LinearLayout.LayoutParams(-1, -2));
      View separator = new View(this);
      separator.setBackgroundColor(0xFFE0E0E0);
      list.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    updateSelectedStationPanel();
  }

  private void filter(String query) {
    List<Station> filtered = new ArrayList<>();
    for (Station s : stations) {
      boolean matchesName = s.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
      int status = streamStatus.containsKey(s.name) ? streamStatus.get(s.name) : -1;
      boolean matchesPlayableFilter = playableOnly == null || !playableOnly.isChecked()
          || status == 1 || status == -2;
      boolean matchesFavoriteFilter = !showingFavorites || prefs.getBoolean("fav_" + s.name, false);
      if (matchesName && matchesPlayableFilter && matchesFavoriteFilter) filtered.add(s);
    }
    render(filtered);
    if (showingFavorites && filtered.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText("Chưa có kênh yêu thích phù hợp. Hãy nhấn ☆ bên cạnh một kênh để thêm vào mục này.");
      empty.setTextSize(15);
      empty.setPadding(dp(12), dp(16), dp(12), dp(16));
      list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }
  }

  private List<String> stationNames() {
    List<String> names = new ArrayList<>();
    if (stations != null) {
      for (Station station : stations) if (station != null && station.name != null && !names.contains(station.name)) names.add(station.name);
    }
    return names;
  }

  private void refreshSearchSuggestions() {
    if (searchSuggestions instanceof StationSuggestionAdapter) {
      ((StationSuggestionAdapter) searchSuggestions).setStationNames(stationNames());
    }
  }

  private final class StationSuggestionAdapter extends ArrayAdapter<String> {
    private final List<String> allNames = new ArrayList<>();
    private final android.widget.Filter stationFilter = new android.widget.Filter() {
      @Override protected FilterResults performFiltering(CharSequence constraint) {
        List<String> matches = new ArrayList<>();
        String q = constraint == null ? "" : constraint.toString().trim().toLowerCase(Locale.ROOT);
        for (String name : allNames) {
          if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) matches.add(name);
        }
        FilterResults results = new FilterResults();
        results.values = matches;
        results.count = matches.size();
        return results;
      }
      @Override protected void publishResults(CharSequence constraint, FilterResults results) {
        clear();
        if (results != null && results.values != null) addAll((List<String>) results.values);
        notifyDataSetChanged();
      }
    };

    StationSuggestionAdapter(List<String> names) {
      super(MainActivity.this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(names));
      allNames.addAll(names);
    }

    void setStationNames(List<String> names) {
      allNames.clear();
      allNames.addAll(names);
      getFilter().filter(search == null ? "" : search.getText().toString());
    }

    @Override public android.widget.Filter getFilter() { return stationFilter; }
  }

  private void updateStationFilterButtons() {
    if (allStationsButton != null) allStationsButton.setText(showingFavorites ? "Tất cả kênh" : "✓ Tất cả kênh");
    if (favoritesButton != null) favoritesButton.setText(showingFavorites ? "✓ ★ Kênh yêu thích" : "★ Kênh yêu thích");
  }

  private String statusText(Station s) {
    if (s.url == null || s.url.trim().isEmpty()) return "Chưa có link phát trực tiếp";
    Integer state = streamStatus.get(s.name);
    if (state == null || state == -1) return "Chưa kiểm tra";
    if (state == -2) return "Máy chủ chưa cho xác minh chắc chắn; hãy thử phát";
    return state == 1 ? "Luồng đã phản hồi / phát được" : "Không truy cập được hoặc luồng lỗi";
  }

  private String scheduleLinkStatusText(Station station) {
    Integer value = scheduleLinkStatus.get(station.name);
    if (value == null || value == 0) return "Lịch phát sóng: Chưa kiểm tra";
    if (value == 1) return "Lịch phát sóng: Có dữ liệu từ máy chủ";
    if (value == 2) return "Lịch phát sóng: Máy chủ báo chưa có lịch hôm nay";
    return "Lịch phát sóng: Link lỗi hoặc chưa ghép được mã đài";
  }

  /** Only called by the user's button; never runs automatically at startup. */
  private void checkAllScheduleLinks() {
    if (checkScheduleLinksButton == null || !checkScheduleLinksButton.isEnabled()) return;
    checkScheduleLinksButton.setEnabled(false);
    checkScheduleLinksButton.setText("Đang lấy danh sách đài...");
    scheduleChecker.execute(() -> {
      String indexHtml = null;
      for (int attempt = 0; attempt < 3 && (indexHtml == null || indexHtml.trim().isEmpty()); attempt++) {
        try {
          indexHtml = downloadScheduleText("https://app.giavangtygia.com/api/schedule", false);
        } catch (Exception ignored) { }
        if ((indexHtml == null || indexHtml.trim().isEmpty()) && attempt < 2) {
          try { Thread.sleep(900L * (attempt + 1)); } catch (InterruptedException ignored) { }
        }
      }
      final String index = indexHtml;
      if (index == null || index.trim().isEmpty()) {
        runOnUiThread(() -> {
          for (Station st : stations) { Integer old = scheduleLinkStatus.get(st.name); if (old == null || old == 0) scheduleLinkStatus.put(st.name, -1); }
          checkScheduleLinksButton.setText("Thử kiểm tra lại link lịch");
          checkScheduleLinksButton.setEnabled(true);
          filter(search == null ? "" : search.getText().toString());
          if (!silentScheduleCheck) toast("Không tải được danh sách đài từ nguồn lịch.");
          silentScheduleCheck = false;
        });
        return;
      }
      Map<String, String> ids = new HashMap<>();
      for (Station st : stations) ids.put(st.name, findScheduleRadioId(index, st.name));
      runOnUiThread(() -> {
        scheduleChecksPending = stations.size();
        for (Station st : stations) if (!scheduleLinkStatus.containsKey(st.name)) scheduleLinkStatus.put(st.name, 0);
        checkScheduleLinksButton.setText("Đang kiểm tra link lịch (" + scheduleChecksPending + ")");
        filter(search == null ? "" : search.getText().toString());
      });
      for (Station st : stations) {
        final Station station = st;
        final String radioId = ids.get(st.name);
        scheduleChecker.execute(() -> {
          // Same sources/parsing as the schedule screen (official VOH page + API), with retries inside.
          int result = ScheduleFetcher.check(station.name, index);
          final int finalResult = result;
          runOnUiThread(() -> {
            scheduleLinkStatus.put(station.name, finalResult);
            scheduleChecksPending--;
            if (scheduleChecksPending <= 0) {
              checkScheduleLinksButton.setText("Kiểm tra lại link lịch tất cả các kênh");
              checkScheduleLinksButton.setEnabled(true);
            } else {
              checkScheduleLinksButton.setText("Đang kiểm tra link lịch (" + scheduleChecksPending + ")");
            }
            if (scheduleChecksPending <= 0) {
              silentScheduleCheck = false;
              saveCheckResults(false);
              filter(search == null ? "" : search.getText().toString());
              updateSelectedStationPanel();
            }
          });
        });
      }
    });
  }

  private String downloadScheduleText(String address, boolean scheduleInfo) throws Exception {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(address).openConnection();
      if (connection instanceof javax.net.ssl.HttpsURLConnection) LegacyTls.applyForScheduleHost((javax.net.ssl.HttpsURLConnection) connection, address);
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(6000);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
      connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
      if (scheduleInfo) connection.setRequestProperty("X-CSRF-TOKEN", String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)));
      int code = connection.getResponseCode();
      if (code < 200 || code >= 400) throw new java.io.IOException("HTTP " + code);
      InputStream input = connection.getInputStream();
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      final int maxBytes = 300000; // A status probe only needs a compact response.
      while (out.size() < maxBytes && (count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - out.size()))) != -1) {
        out.write(buffer, 0, count);
      }
      input.close();
      return new String(out.toByteArray(), Charset.forName("UTF-8"));
    } finally { if (connection != null) connection.disconnect(); }
  }

  private String findScheduleRadioId(String html, String wantedName) {
    String wanted = normalizeScheduleName(wantedName);
    Matcher matcher = java.util.regex.Pattern.compile("(?is)<([a-z0-9]+)\\b([^>]*class\\s*=\\s*['\"][^'\"]*radio-item[^'\"]*['\"][^>]*)>").matcher(html);
    List<Integer> starts = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    while (matcher.find()) {
      Matcher id = java.util.regex.Pattern.compile("(?is)data-id\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(matcher.group(2));
      if (id.find()) { starts.add(matcher.start()); ids.add(id.group(1)); }
    }
    for (int i = 0; i < starts.size(); i++) {
      String block = html.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : html.length());
      Matcher title = java.util.regex.Pattern.compile("(?is)class\\s*=\\s*['\"][^'\"]*radio-title[^'\"]*['\"][^>]*>(.*?)</").matcher(block);
      String label = title.find() ? title.group(1).replaceAll("(?is)<[^>]+>", " ") : block.replaceAll("(?is)<[^>]+>", " ");
      String normalized = normalizeScheduleName(android.text.Html.fromHtml(label).toString());
      if (!normalized.isEmpty() && (normalized.equals(wanted) || normalized.contains(wanted) || wanted.contains(normalized))) return ids.get(i);
      if (normalized.contains("binh duong") && wanted.contains("binh duong")) return ids.get(i);
      String frequency = wantedName.replaceAll(".*?([0-9]+(?:\\.[0-9]+)?).*", "$1").replace(".", " ");
      if (normalized.contains("voh") && wanted.contains("voh") && (frequency.equals(wantedName) || normalized.contains(frequency))) return ids.get(i);
    }
    return null;
  }

  private String normalizeScheduleName(String value) {
    if (value == null) return "";
    String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    return normalized.toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
  }

  private boolean isVohStation(String name) {
    return name != null && normalizeScheduleName(name).contains("voh");
  }

  /** Check all station streams. VOH uses a separate GET probe that accepts valid HLS playlists
   * and avoids false negatives caused by HEAD/Range or non-standard content-type responses.
   */
  private void checkAllStreams() {
    checksPending = 0;
    for (Station station : stations) {
      if (station.url == null || station.url.trim().isEmpty()) {
        streamStatus.put(station.name, 0);
      } else {
        if (!streamStatus.containsKey(station.name)) streamStatus.put(station.name, -1);
        checksPending++;
      }
    }
    if (checkStreamsButton != null) {
      checkStreamsButton.setText("Đang kiểm tra các đài...");
      checkStreamsButton.setEnabled(false);
    }
    filter(search == null ? "" : search.getText().toString());
    for (Station station : stations) {
      if (station.url == null || station.url.trim().isEmpty()) continue;
      final Station target = station;
      checker.execute(() -> {
        final int result;
        // Use the same tolerant GET/HLS probe for every station. Some radio servers
        // reject HEAD/Range requests or omit standard audio Content-Type headers.
        result = probeVohStream(target.url);
        runOnUiThread(() -> {
          // A real successful playback should never be overwritten by a failed standalone probe.
          Integer old = streamStatus.get(target.name);
          if (!(old != null && old == 1 && RadioPlayer.isPlayingStation(target.name))) {
            streamStatus.put(target.name, result);
          }
          checksPending--;
          if (checkStreamsButton != null) {
            checkStreamsButton.setText(checksPending > 0 ? "Đang kiểm tra... (" + checksPending + ")" : "Kiểm tra lại trạng thái các đài");
            checkStreamsButton.setEnabled(checksPending <= 0);
          }
          if (checksPending <= 0) {
            saveCheckResults(true);
            filter(search == null ? "" : search.getText().toString());
            updateSelectedStationPanel();
            if (autoChainSchedule) { autoChainSchedule = false; checkAllScheduleLinks(); }
          }
        });
      });
    }
    if (checksPending == 0 && checkStreamsButton != null) {
      checkStreamsButton.setText("Kiểm tra lại trạng thái các đài");
      checkStreamsButton.setEnabled(true);
    }
  }

  /** Shared tolerant stream check for every station: normal GET (no Range header),
   * browser-like headers, and HLS parsing. Return 1=valid/responding,
   * 0=definitely unavailable (404/410), -2=inconclusive.
   */
  private int probeVohStream(String address) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(address);
      connection = (HttpURLConnection) url.openConnection();
      if (connection instanceof javax.net.ssl.HttpsURLConnection) {
        LegacyTls.applyForStreamHost((javax.net.ssl.HttpsURLConnection) connection, address);
      }
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(4500);
      connection.setReadTimeout(4500);
      connection.setInstanceFollowRedirects(true);
      connection.setUseCaches(false);
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
      connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,audio/*,*/*;q=0.8");
      connection.setRequestProperty("Connection", "close");
      int code = connection.getResponseCode();
      if (code == 404 || code == 410) return 0;
      if (code < 200 || code >= 400) return -2; // auth/rate-limit/server errors do not prove the stream is dead
      InputStream in = connection.getInputStream();
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while (out.size() < 32768 && (n = in.read(buffer, 0, Math.min(buffer.length, 32768 - out.size()))) > 0) {
        out.write(buffer, 0, n);
        // Playlist headers and the first segment URL are usually in the first few KB.
        String partial = new String(out.toByteArray(), Charset.forName("UTF-8")).trim();
        if (partial.startsWith("#EXTM3U") && (partial.contains("#EXTINF") || partial.contains("#EXT-X-STREAM-INF") || firstPlaylistChild(partial) != null)) break;
        if (out.size() >= 8192) break;
      }
      in.close();
      byte[] bytes = out.toByteArray();
      if (bytes.length == 0) return -2;
      String body = new String(bytes, Charset.forName("UTF-8")).trim();
      String lower = body.toLowerCase(Locale.ROOT);
      String type = connection.getContentType();
      String contentType = type == null ? "" : type.toLowerCase(Locale.ROOT);
      if (body.startsWith("#EXTM3U")) {
        boolean hasSegment = body.contains("#EXTINF") && firstPlaylistChild(body) != null;
        boolean hasVariant = body.contains("#EXT-X-STREAM-INF") && firstPlaylistChild(body) != null;
        return (hasSegment || hasVariant) ? 1 : -2;
      }
      if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || contentType.contains("text/html")) return -2;
      if (contentType.startsWith("audio/") || contentType.startsWith("video/") || contentType.contains("octet-stream") || contentType.contains("binary")) return 1;
      // A server may omit Content-Type for a valid live audio response. Non-text bytes count as a response.
      boolean printable = true;
      for (byte b : bytes) {
        int v = b & 0xff;
        if (v == 9 || v == 10 || v == 13) continue;
        if (v < 32 || v > 126) { printable = false; break; }
      }
      return printable ? -2 : 1;
    } catch (Exception e) {
      // A timeout/TLS/proxy error is inconclusive, not proof the VOH stream is broken.
      return -2;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private boolean isStreamResponding(String address, int depth) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(address);
      connection = (HttpURLConnection) url.openConnection();
      // Match the playback client's Android 6 TLS compatibility behavior for known VOH hosts.
      // Hostname verification remains enabled; this only bypasses chain trust for these hosts.
      if (connection instanceof javax.net.ssl.HttpsURLConnection) {
        LegacyTls.applyForStreamHost((javax.net.ssl.HttpsURLConnection) connection, address);
      }
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(4500);
      connection.setReadTimeout(4500);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", "VietRadioAndroid6/1.6");
      connection.setRequestProperty("Range", "bytes=0-8191");
      connection.setRequestProperty("Accept", "audio/*,application/vnd.apple.mpegurl,application/x-mpegURL,*/*");
      connection.connect();
      int code = connection.getResponseCode();
      if (code < 200 || code >= 400) return false;
      String type = connection.getContentType();
      InputStream in = connection.getInputStream();
      byte[] data = new byte[8192];
      int n = in.read(data);
      in.close();
      if (n <= 0) return false;
      String prefix = new String(data, 0, n, Charset.forName("UTF-8")).trim();
      boolean looksLikePlaylist = prefix.startsWith("#EXTM3U") || (type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl"));
      if (looksLikePlaylist) {
        if (!prefix.startsWith("#EXTM3U")) return false;
        String child = firstPlaylistChild(prefix);
        // For a master playlist, also test the first variant stream once.
        if (child != null && depth == 0 && !child.startsWith("#")) {
          String resolved = new URL(url, child).toString();
          return isStreamResponding(resolved, 1);
        }
        // A media playlist must contain a segment reference and usually EXTINF.
        return prefix.contains("#EXTINF") && firstPlaylistChild(prefix) != null;
      }
      // Reject web error pages: an HTTP 200 HTML page is not a playable audio stream.
      String lowerType = type == null ? "" : type.toLowerCase(Locale.ROOT);
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      if (lowerType.contains("text/html") || lowerPrefix.startsWith("<!doctype html")
          || lowerPrefix.startsWith("<html") || lowerPrefix.startsWith("<head")) return false;
      // Non-HLS streams are considered reachable if the server returns media/binary bytes.
      return lowerType.startsWith("audio/") || lowerType.startsWith("video/")
          || lowerType.contains("octet-stream") || lowerType.contains("binary")
          || type == null;
    } catch (Exception e) {
      return false;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private String firstPlaylistChild(String playlist) {
    String[] lines = playlist.split("\\r?\\n");
    for (String line : lines) {
      String candidate = line.trim();
      if (!candidate.isEmpty() && !candidate.startsWith("#")) return candidate;
    }
    return null;
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

  private boolean isRecording() { return getSharedPreferences("recording_state", MODE_PRIVATE).getBoolean("active", false); }

  private void startRecording(Station station, boolean silent, int minutes) {
    if (station.url == null || station.url.trim().isEmpty()) { toast("Kênh này chưa có URL stream trực tiếp."); return; }
    if (isRecording()) { toast("Đang có một phiên ghi âm. Hãy dừng trước khi ghi phiên mới."); return; }
    if (!silent) startStation(station);
    long started = System.currentTimeMillis();
    getSharedPreferences("recording_state", MODE_PRIVATE).edit().putBoolean("active", true).putLong("started", started).putString("station", station.name).apply();
    Intent intent = new Intent(this, RadioRecordService.class);
    intent.putExtra("url", station.url);
    intent.putExtra("name", station.name);
    intent.putExtra("silent", silent);
    intent.putExtra("durationMinutes", minutes);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    updateRecordingUi();
    toast(silent ? "Đang ghi âm im lặng, không phát ra loa" : "Đang ghi âm; chế độ nghe được bật");
  }

  private void stopRecording() {
    Intent stop = new Intent(this, RadioRecordService.class);
    stop.setAction("com.vietradio.android6.STOP_RECORDING");
    startService(stop);
    getSharedPreferences("recording_state", MODE_PRIVATE).edit().putBoolean("active", false).apply();
    updateRecordingUi();
    toast("Đã yêu cầu dừng ghi âm.");
  }

  private void updateRecordingUi() {
    if (recordingElapsed == null || recordButton == null) return;
    boolean active = isRecording();
    recordingElapsed.setVisibility(active ? View.VISIBLE : View.GONE);
    if (active) {
      long start = getSharedPreferences("recording_state", MODE_PRIVATE).getLong("started", System.currentTimeMillis());
      long seconds = Math.max(0, (System.currentTimeMillis() - start) / 1000);
      recordingElapsed.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds/3600, (seconds/60)%60, seconds%60));
      recordButton.setText("■ Đang ghi âm • Dừng");
    } else recordButton.setText("● Ghi âm");
    tintButton(recordButton, active ? Integer.valueOf(0xFFFFD6D6) : null);
  }

  private void showRecordedFiles() {
    File dir = new File(Environment.getExternalStorageDirectory(), "RadioRecordings");
    File[] found = dir.listFiles();
    ArrayList<File> files = new ArrayList<>();
    if (found != null) for (File f : found) if (f.isFile()) files.add(f);
    if (files.isEmpty()) {
      new AlertDialog.Builder(this).setTitle("Các file đã ghi âm").setMessage("Chưa tìm thấy file ghi âm nào trong thư mục RadioRecordings.").setPositiveButton("Đóng", null).show();
      return;
    }
    Collections.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(8),dp(4),dp(8),dp(4));
    ScrollView scroll = new ScrollView(this); scroll.addView(content);
    Calendar today = Calendar.getInstance(); Calendar yesterday = Calendar.getInstance(); yesterday.add(Calendar.DAY_OF_YEAR,-1);
    SimpleDateFormat dayFmt = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
    SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    // Box 1: search by channel. Box 2: search by recording date.
    final EditText channelBox = new EditText(this);
    channelBox.setHint("Tìm theo kênh (vd: VOV1)");
    channelBox.setSingleLine(true);
    channelBox.setTextSize(14);
    channelBox.setText(lastRecordedChannelQuery);
    final EditText dateBox = new EditText(this);
    dateBox.setHint("Tìm theo ngày ghi âm (vd: 30/10/2026 hoặc 30/10)");
    dateBox.setSingleLine(true);
    dateBox.setTextSize(14);
    dateBox.setText(lastRecordedDateQuery);
    final ArrayList<File> allFiles = files;
    final Runnable fill = new Runnable() {
      @Override public void run() {
        content.removeAllViews();
        lastRecordedChannelQuery = channelBox.getText().toString();
        lastRecordedDateQuery = dateBox.getText().toString();
        String[] channelTokens = foldText(lastRecordedChannelQuery).trim().split("\\s+");
        String[] dateTokens = foldText(lastRecordedDateQuery).trim().split("\\s+");
        String currentGroup = "";
        int shown = 0;
        for (File f : allFiles) {
          Calendar c = Calendar.getInstance(); c.setTimeInMillis(f.lastModified());
          String group;
          if (sameDay(c,today)) group = "Hôm nay • " + dayFmt.format(c.getTime());
          else if (sameDay(c,yesterday)) group = "Hôm qua • " + dayFmt.format(c.getTime());
          else group = dayFmt.format(c.getTime());
          Station owner = stationForFile(f);
          String channelHay = foldText(owner == null ? f.getName() : owner.name + " " + f.getName());
          String dateHay = foldText(group + " " + dayFmt.format(c.getTime())
              + " " + new SimpleDateFormat("dd-MM-yyyy dd.MM.yyyy yyyyMMdd", Locale.getDefault()).format(c.getTime()));
          boolean ok = true;
          for (String tk : channelTokens) if (!tk.isEmpty() && !channelHay.contains(tk)) { ok = false; break; }
          if (ok) for (String tk : dateTokens) if (!tk.isEmpty() && !dateHay.contains(tk)) { ok = false; break; }
          if (!ok) continue;
          shown++;
          if (!group.equals(currentGroup)) {
            TextView header = new TextView(MainActivity.this); header.setText(group); header.setTextSize(16); header.setTypeface(null, android.graphics.Typeface.BOLD); header.setPadding(dp(8),dp(10),dp(8),dp(6));
            content.addView(header); currentGroup = group;
          }
          final File item = f;
          long resumeAt = savedResumeMs(item);
          Button fileButton = btn(item.getName() + "\n"
              + timeFmt.format(c.getTime()) + " • "
              + android.text.format.Formatter.formatFileSize(MainActivity.this, item.length())
              + "\n" + durationLine(item)
              + (resumeAt > 0L ? "\n◐ Nghe dở tại " + formatPlaybackTime(resumeAt) : ""));
          // Half-listened recordings are marked with a light amber background.
          if (resumeAt > 0L) tintButton(fileButton, 0xFFFFE7B0);
          fileButton.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT); fileButton.setPadding(dp(12),dp(6),dp(8),dp(6));
          content.addView(fileButton,new LinearLayout.LayoutParams(-1,-2));
          fileButton.setOnClickListener(v -> showRecordingActions(item,false,recordedFilesDialog));
          fileButton.setOnLongClickListener(v -> { showRecordingActions(item,true,recordedFilesDialog); return true; });
        }
        if (shown == 0) {
          TextView none = new TextView(MainActivity.this); none.setText("Không có file ghi âm phù hợp."); none.setPadding(dp(12),dp(16),dp(12),dp(16));
          content.addView(none);
        }
      }
    };
    fill.run();
    // Lets the "nghe dở" marks update while the list is open (keeps the scroll position).
    recordedListRefresh = () -> {
      final int y = scroll.getScrollY();
      fill.run();
      scroll.post(() -> scroll.scrollTo(0, y));
    };
    android.text.TextWatcher searchWatcher = new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s1, int a1, int b1, int c1) {}
      @Override public void onTextChanged(CharSequence s1, int a1, int b1, int c1) {}
      @Override public void afterTextChanged(android.text.Editable e) { fill.run(); }
    };
    channelBox.addTextChangedListener(searchWatcher);
    dateBox.addTextChangedListener(searchWatcher);
    // Keep the recorded-file list visible while audio plays. Put the same dock
    // inside this dialog so playback controls remain accessible above the list.
    LinearLayout dialogContent = new LinearLayout(this);
    dialogContent.setOrientation(LinearLayout.VERTICAL);
    dialogContent.addView(channelBox, new LinearLayout.LayoutParams(-1, -2));
    dialogContent.addView(dateBox, new LinearLayout.LayoutParams(-1, -2));
    dialogContent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    ViewGroup oldDockParent = (ViewGroup) localPlayerDock.getParent();
    if (oldDockParent != null) oldDockParent.removeView(localPlayerDock);
    dialogContent.addView(localPlayerDock, new LinearLayout.LayoutParams(-1, -2));
    final AlertDialog thisDialog = new AlertDialog.Builder(this).setTitle("Các file đã ghi âm • Sắp xếp theo ngày").setView(dialogContent).setNegativeButton("Đóng",null).create();
    recordedFilesDialog = thisDialog;
    thisDialog.setOnDismissListener(dialog -> {
      // Dialog dismiss listeners run asynchronously. When the list is rebuilt (after delete/rename),
      // the new dialog is already current by the time the old one's listener fires: do not clobber it.
      if (recordedFilesDialog != thisDialog) return;
      recordedFilesDialog = null;
      recordedListRefresh = null;
      lastRecordedChannelQuery = ""; lastRecordedDateQuery = "";
      ViewGroup parent = (ViewGroup) localPlayerDock.getParent();
      if (parent != null) parent.removeView(localPlayerDock);
      if (localPlayerDock.getParent() == null && mainContentRoot != null) {
        // The activity's screen owns the dock when the file-list dialog is closed.
        ViewGroup screenParent = (ViewGroup) mainContentRoot.getParent();
        if (screenParent != null && screenParent.getParent() instanceof LinearLayout) {
          ((LinearLayout) screenParent.getParent()).addView(localPlayerDock, new LinearLayout.LayoutParams(-1, -2));
        }
      }
    });
    thisDialog.show();
  }

  private boolean sameDay(Calendar a, Calendar b) { return a.get(Calendar.YEAR)==b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR); }

  private void showRecordingActions(File f, boolean longPress, AlertDialog listDialog) {
    if (f == null || !f.exists()) { toast("File không còn tồn tại."); return; }
    final String[] actions = new String[]{"Chạy file", "Đổi tên", "Xóa", "Truy cập đến file gốc", "Thông tin file"};
    new AlertDialog.Builder(this).setTitle(f.getName()).setItems(actions, (d, which) -> {
      if (which == 0) playRecordedFile(f);
      else if (which == 1) renameRecordedFile(f);
      else if (which == 2) new AlertDialog.Builder(this).setTitle("Xóa file ghi âm?").setMessage(f.getName()).setNegativeButton("Hủy", null).setPositiveButton("Xóa", (dd, ww) -> {
        if (f.delete()) { prefs.edit().remove(resumeKey(f)).apply(); toast("Đã xóa file."); if (listDialog != null) { listDialog.dismiss(); showRecordedFiles(); } }
        else toast("Không xóa được file. Hãy kiểm tra quyền truy cập bộ nhớ.");
      }).show();
      else if (which == 3) openRecordingLocation(f);
      else new AlertDialog.Builder(this).setTitle("Thông tin file").setMessage("Đường dẫn: " + f.getAbsolutePath() + "\nDung lượng: " + android.text.format.Formatter.formatFileSize(this, f.length()) + "\nĐịnh dạng: " + getFileExtension(f)).setPositiveButton("Đóng", null).show();
    }).setNegativeButton("Hủy", null).show();
  }

  private String formatPlaybackTime(long millis) {
    long sec = Math.max(0L, millis / 1000L);
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", sec / 3600L, (sec / 60L) % 60L, sec % 60L);
  }

  private LinearLayout buildLocalPlayerDock() {
    LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(8),dp(5),dp(8),dp(4));
    panel.setBackgroundColor(0xFFF1F3F5);
    LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
    dockFileName = new TextView(this); dockFileName.setTextSize(14); dockFileName.setTypeface(null,android.graphics.Typeface.BOLD); dockFileName.setSingleLine(true); dockFileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    top.addView(dockFileName,new LinearLayout.LayoutParams(0,-2,1));
    Button close = btn("✕"); close.setMinWidth(dp(42)); top.addView(close,new LinearLayout.LayoutParams(dp(44),dp(42))); close.setOnClickListener(v -> {
      // Closing the local playback dock must stop the recording, not just hide controls.
      saveResumePosition();
      refreshRecordedListMarks();
      RadioPlayer.stop();
      dockHandler.removeCallbacks(dockTicker);
      dockSeeking = false;
      localPlayerDock.setVisibility(View.GONE);
      setDockPlayState(false);
      if (dockSeek != null) dockSeek.setProgress(0);
      if (dockElapsed != null) dockElapsed.setText("00:00:00");
      if (dockRemaining != null) dockRemaining.setText("-00:00:00");
      if (dockEqualizer != null) dockEqualizer.setActive(false);
      if (recordedFilesDialog != null && recordedFilesDialog.isShowing()) recordedFilesDialog.dismiss();
    });
    panel.addView(top,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout times = new LinearLayout(this); times.setGravity(Gravity.CENTER_VERTICAL);
    dockElapsed = new TextView(this); dockElapsed.setText("00:00"); dockRemaining = new TextView(this); dockRemaining.setText("-00:00");
    times.addView(dockElapsed,new LinearLayout.LayoutParams(0,-2,1)); dockRemaining.setGravity(Gravity.RIGHT); times.addView(dockRemaining,new LinearLayout.LayoutParams(0,-2,1));
    panel.addView(times,new LinearLayout.LayoutParams(-1,-2));
    dockSeek = new SeekBar(this); dockSeek.setMax(1000); panel.addView(dockSeek,new LinearLayout.LayoutParams(-1,dp(30)));
    dockSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
       public void onStartTrackingTouch(SeekBar b){dockSeeking=true;}
       public void onStopTrackingTouch(SeekBar b){dockSeeking=false;}
       public void onProgressChanged(SeekBar b,int p,boolean u){
         if(u){long d=RadioPlayer.getDurationMs();if(d>0){long target=d*p/1000L;dockElapsed.setText(formatPlaybackTime(target));dockRemaining.setText("-"+formatPlaybackTime(Math.max(0L,d-target)));}}
       }
    });
    // Consume the complete touch sequence so SeekBar's default thumb handling cannot
    // overwrite a tap-to-seek position after we calculate it from the touched x-coordinate.
    dockSeek.setOnTouchListener((view,event)->{
      SeekBar bar=(SeekBar)view;
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_DOWN || action==android.view.MotionEvent.ACTION_MOVE || action==android.view.MotionEvent.ACTION_UP){
        int width=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight();
        if(width>0){
          float x=Math.max(0f,Math.min((float)width,event.getX()-bar.getPaddingLeft()));
          int progress=(int)(1000f*x/width);
          if(action==android.view.MotionEvent.ACTION_DOWN || action==android.view.MotionEvent.ACTION_MOVE){dockSeeking=true;bar.setProgress(progress);}
          if(action==android.view.MotionEvent.ACTION_UP){
            bar.setProgress(progress); dockSeeking=false;
            long duration=RadioPlayer.getDurationMs();
            if(duration>0){
              long target=Math.max(0L,Math.min(duration,duration*progress/1000L));
              // Always send the seek request, including for TS recordings whose
              // extractor does not advertise a seek map.
              RadioPlayer.seekToPosition(target);
            } else {
              toast("Chưa đọc được thời lượng file nên chưa thể tua.");
            }
          }
        }
        if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) dockSeeking=false;
        return true;
      }
      if(action==android.view.MotionEvent.ACTION_CANCEL){dockSeeking=false;return true;}
      return true;
    });
    LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
    dockPlayPause=btn("▶"); Button stop=btn("■"); Button volDown=btn("🔉 −"); Button volUp=btn("🔊 +");
    controls.addView(dockPlayPause,new LinearLayout.LayoutParams(0,dp(44),1)); controls.addView(stop,new LinearLayout.LayoutParams(0,dp(44),0.7f)); controls.addView(volDown,new LinearLayout.LayoutParams(0,dp(44),0.8f)); controls.addView(volUp,new LinearLayout.LayoutParams(0,dp(44),0.8f));
    dockVolume=new TextView(this); dockVolume.setGravity(Gravity.CENTER); dockVolume.setTextSize(12); controls.addView(dockVolume,new LinearLayout.LayoutParams(dp(48),-2));
    dockEqualizer=new EqualizerIndicator(this); controls.addView(dockEqualizer,new LinearLayout.LayoutParams(dp(28),dp(28)));
    panel.addView(controls,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout seekRow = new LinearLayout(this);
    seekRow.setOrientation(LinearLayout.HORIZONTAL);
    Button seekBack = btn("◀◀ Tua lùi 10s");
    Button seekForward = btn("Tua tới 10s ▶▶");
    seekBack.setContentDescription("Tua lùi. Bấm liên tiếp để tua thêm, mỗi lần 10 giây");
    seekForward.setContentDescription("Tua tới. Bấm liên tiếp để tua thêm, mỗi lần 10 giây");
    seekRow.addView(seekBack, new LinearLayout.LayoutParams(0, dp(44), 1));
    seekRow.addView(seekForward, new LinearLayout.LayoutParams(0, dp(44), 1));
    panel.addView(seekRow, new LinearLayout.LayoutParams(-1, -2));
    seekBack.setOnClickListener(v -> dockSeekTap(-1));
    seekForward.setOnClickListener(v -> dockSeekTap(1));
    panel.addView(createBoostBar(), new LinearLayout.LayoutParams(-1,-2));
    dockPlayPause.setOnClickListener(v->{if(RadioPlayer.isPlayingStation(dockFileName.getText().toString())) { RadioPlayer.pause(); saveResumePosition(); refreshRecordedListMarks(); setDockPlayState(false); } else if(RadioPlayer.hasPlayer()) { RadioPlayer.resume(); setDockPlayState(true); } else if(dockFile!=null) { playRecordedFile(dockFile); setDockPlayState(true); }});
    stop.setOnClickListener(v->{saveResumePosition();refreshRecordedListMarks();RadioPlayer.stop();dockElapsed.setText("00:00:00");dockRemaining.setText("-00:00:00");dockSeek.setProgress(0);dockEqualizer.setActive(false);});
    volDown.setOnClickListener(v->changeSystemVolume(-1)); volUp.setOnClickListener(v->changeSystemVolume(1));
    updateDockVolume(); return panel;
  }
  private void changeSystemVolume(int delta){ if(audio==null)return; audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,delta>0?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER,0); persistVolume(); updateDockVolume(); }
  private void updateDockVolume(){ if(dockVolume!=null&&audio!=null){int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),v=audio.getStreamVolume(AudioManager.STREAM_MUSIC);dockVolume.setText("🔊 "+(max==0?0:(v*100/max))+"%");} }
  // Rapid taps add up: 1 tap = 10 s, 2 taps = 20 s, 3 taps = 30 s ... The seek happens ~1 s after the last tap.
  private int seekPendingMs;
  private android.widget.Toast seekToast;
  private final Runnable seekRunner = new Runnable() {
    @Override public void run() {
      long delta = seekPendingMs;
      seekPendingMs = 0;
      if (delta == 0 || !RadioPlayer.hasPlayer()) return;
      long pos = RadioPlayer.getCurrentPositionMs(), dur = RadioPlayer.getDurationMs();
      long target = pos + delta;
      if (dur > 0) target = Math.min(target, Math.max(0L, dur - 1500L));
      target = Math.max(0L, target);
      RadioPlayer.seekToPosition(target);
      showSeekToast((delta > 0 ? "Đã tua tới " : "Đã tua lùi ") + (Math.abs(delta) / 1000) + " giây");
    }
  };
  private void showSeekToast(String msg) {
    try { if (seekToast != null) seekToast.cancel(); } catch (Exception ignored) {}
    seekToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    seekToast.show();
  }
  private void dockSeekTap(int dir) {
    if (!RadioPlayer.hasPlayer()) return;
    if (seekPendingMs != 0 && (seekPendingMs > 0) != (dir > 0)) seekPendingMs = 0;
    seekPendingMs += dir * 10000;
    showSeekToast((dir > 0 ? "Chuẩn bị tua tới " : "Chuẩn bị tua lùi ") + (Math.abs(seekPendingMs) / 1000) + " giây…");
    dockHandler.removeCallbacks(seekRunner);
    dockHandler.postDelayed(seekRunner, 1000);
  }
  // ---- Resume a partly-listened recording ----
  private static String durationWords(long ms) {
    long total = Math.max(0L, (ms + 500L) / 1000L);
    long h = total / 3600, m = (total % 3600) / 60, sec = total % 60;
    StringBuilder sb = new StringBuilder();
    if (h > 0) sb.append(h).append(" giờ ");
    if (h > 0 || m > 0) sb.append(m).append(" phút ");
    sb.append(sec).append(" giây");
    return sb.toString();
  }
  /** Cached duration text for a recording; starts a background computation when it is not known yet. */
  private String durationLine(final File f) {
    final String key = "dur2_" + f.getName() + "_" + f.length();
    Long cached = fileDurations.get(key);
    if (cached == null && prefs.contains(key)) { cached = prefs.getLong(key, 0L); fileDurations.put(key, cached); }
    if (cached != null) return cached > 0 ? "Độ dài: " + durationWords(cached) : "Độ dài: không xác định";
    if (durationInFlight.add(key)) {
      durationExecutor.execute(() -> {
        final long d = RadioPlayer.fileDurationMs(f);
        runOnUiThread(() -> {
          durationInFlight.remove(key);
          fileDurations.put(key, d);
          if (d > 0) prefs.edit().putLong(key, d).apply();
          // Coalesce refreshes so the list is rebuilt once after a burst of results.
          recordingHandler.removeCallbacks(durationListRefresh);
          recordingHandler.postDelayed(durationListRefresh, 400);
        });
      });
    }
    return "Độ dài: đang tính…";
  }
  private String resumeKey(File f) { return "resume_" + f.getName(); }
  private long savedResumeMs(File f) { return f == null ? 0L : prefs.getLong(resumeKey(f), 0L); }
  /** Remember where the recording in the dock is (forgotten if it just started or finished completely). */
  private boolean saveResumePosition() {
    File f = dockFile;
    if (f == null || pendingResumeMs > 0 || !RadioPlayer.hasPlayer() || !RadioPlayer.isCurrentStation(f.getName())) return false;
    long pos = RadioPlayer.getCurrentPositionMs();
    boolean hadMark = prefs.contains(resumeKey(f));
    // Remember any position (including the first and last 5 s); forget only if it has barely started or has played to the end.
    if (pos < 1000L || RadioPlayer.isEnded()) {
      prefs.edit().remove(resumeKey(f)).apply();
      return hadMark;
    }
    prefs.edit().putLong(resumeKey(f), pos).apply();
    return !hadMark; // true when the "nghe dở" mark appears or disappears, so the list can be refreshed
  }
  private void refreshRecordedListMarks() {
    if (recordedFilesDialog != null && recordedFilesDialog.isShowing() && recordedListRefresh != null) recordedListRefresh.run();
  }
  private void setDockPlayState(boolean playing) {
    if (dockPlayPause == null) return;
    dockPlayPause.setText(playing ? "Ⅱ" : "▶");
    tintButton(dockPlayPause, playing ? Integer.valueOf(0xFFA8DDB4) : null);
  }
  private void updateDockPlayback(){ if(localPlayerDock==null||localPlayerDock.getVisibility()!=View.VISIBLE)return; long pos=RadioPlayer.getCurrentPositionMs(),dur=RadioPlayer.getDurationMs(); dockElapsed.setText(formatPlaybackTime(pos)); dockRemaining.setText(dur>0?"-"+formatPlaybackTime(Math.max(0,dur-pos)):"- --:--:--"); if(!dockSeeking&&dur>0)dockSeek.setProgress((int)Math.min(1000,pos*1000L/dur)); boolean playing=RadioPlayer.isPlayingStation(dockFileName.getText().toString()); dockEqualizer.setActive(playing); setDockPlayState(playing);
    if (pendingResumeMs > 0) {
      // Jump to the saved position as soon as the duration is known (or after a few seconds regardless).
      if (dur > 0 || System.currentTimeMillis() - pendingResumeSince > 6000L) { long t = pendingResumeMs; pendingResumeMs = 0; RadioPlayer.seekToPosition(t); }
    } else if (playing && System.currentTimeMillis() - lastResumeSave >= 1000L) {
      lastResumeSave = System.currentTimeMillis();
      if (saveResumePosition()) refreshRecordedListMarks();
    }
    updateDockVolume(); }
  private void showLocalPlaybackControls(File file, Station localFile) {
    long wanted = pendingResumeMs; pendingResumeMs = 0; saveResumePosition(); pendingResumeMs = wanted;
    dockFile=file; dockFileName.setText(file.getName()); dockElapsed.setText("00:00:00"); dockRemaining.setText("-00:00:00"); dockSeek.setProgress(0); setDockPlayState(true); localPlayerDock.setVisibility(View.VISIBLE);
    // A recorded file plays with the volume and boost remembered for the station it was recorded from.
    Station owner = stationForFile(file);
    dockOwnerName = owner == null ? null : owner.name;
    if (owner != null) applyStationAudio(owner);
    else { RadioPlayer.setBoostMb(this, prefs.getInt("fileboost", RadioPlayer.defaultBoostMb())); syncBoostSliders(); }
    RadioPlayer.play(this,localFile); dockHandler.removeCallbacks(dockTicker); dockHandler.post(dockTicker);
  }

  private String getFileExtension(File f) {
    String n = f.getName(); int dot = n.lastIndexOf('.'); return dot >= 0 ? n.substring(dot + 1).toUpperCase(Locale.ROOT) : "Không rõ";
  }

  /** Entry point: if the file was left half-listened, ask whether to start over or continue; otherwise play from the start. */
  private void playRecordedFile(final File f) {
    if (f == null || !f.isFile() || !f.canRead()) {
      toast("Không đọc được file ghi âm.");
      return;
    }
    final long resume = savedResumeMs(f);
    if (resume > 0L) {
      // Note: setMessage() would hide the list items, so the explanation goes in the title.
      new AlertDialog.Builder(this).setTitle("Bạn đã nghe dở file này:\n" + f.getName())
        .setItems(new String[]{"Phát từ đầu", "Phát tiếp từ " + formatPlaybackTime(resume)}, (d, which) -> {
          if (which == 0) { prefs.edit().remove(resumeKey(f)).apply(); startRecordedPlayback(f, 0L); }
          else startRecordedPlayback(f, resume);
        })
        .setNegativeButton("Hủy", null).show();
    } else startRecordedPlayback(f, 0L);
  }

  private void startRecordedPlayback(File f, long startMs) {
    String ext = getFileExtension(f).toLowerCase(Locale.US);
    if (ext.equals("ts") || ext.equals("mp3") || ext.equals("audio") || ext.equals("aac") || ext.equals("m4a")) {
      // Play inside the app using Media3/ExoPlayer; file:// works on Android 6.
      android.net.Uri fileUri = android.net.Uri.fromFile(f);
      Station localFile = new Station(f.getName(), fileUri.toString(), "");
      // Keep the grouped recordings list visible while the selected recording plays.
      // Do not show the recording filename in the home-screen status line.
      pendingResumeMs = startMs; pendingResumeSince = System.currentTimeMillis();
      showLocalPlaybackControls(f, localFile);
    } else {
      try {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(android.net.Uri.fromFile(f), "*/*");
        startActivity(i);
      } catch (Exception e) {
        toast("Định dạng file này chưa được hỗ trợ phát trong ứng dụng.");
      }
    }
  }

  private void renameRecordedFile(File f) {
    EditText name = new EditText(this); name.setSingleLine(true); name.setText(f.getName()); name.setSelection(name.length());
    new AlertDialog.Builder(this).setTitle("Đổi tên file").setView(name).setNegativeButton("Hủy", null).setPositiveButton("Lưu", (d,w) -> {
      String n = name.getText().toString().trim();
      if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..")) { toast("Tên file không hợp lệ."); return; }
      File target = new File(f.getParentFile(), n);
      if (target.exists()) { toast("Tên file đã tồn tại."); return; }
      if (f.renameTo(target)) { toast("Đã đổi tên file."); if (recordedFilesDialog != null) recordedFilesDialog.dismiss(); showRecordedFiles(); }
      else toast("Không đổi tên được file. Hãy kiểm tra quyền truy cập bộ nhớ.");
    }).show();
  }

  private void openRecordingLocation(File f) {
    // Legacy Android 6 file managers commonly accept the parent directory URI.
    try {
      Intent i = new Intent(Intent.ACTION_VIEW);
      i.setDataAndType(android.net.Uri.fromFile(f.getParentFile()), "resource/folder");
      startActivity(i);
    } catch (Exception e) {
      new AlertDialog.Builder(this).setTitle("Vị trí file gốc").setMessage(f.getAbsolutePath()).setPositiveButton("Sao chép đường dẫn", (d,w) -> {
        android.content.ClipboardManager cb = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Đường dẫn file", f.getAbsolutePath()));
        toast("Đã sao chép đường dẫn file.");
      }).setNegativeButton("Đóng", null).show();
    }
  }

  private static class TimeInput {
    final EditText hour;
    final EditText minute;
    final Spinner ampm;
    final View container;
    TimeInput(EditText hour, EditText minute, Spinner ampm, View container) {
      this.hour = hour; this.minute = minute; this.ampm = ampm; this.container = container;
    }
    void setVisibility(int visibility) { container.setVisibility(visibility); }
    int getHourOfDay() {
      int h = Integer.parseInt(hour.getText().toString().trim());
      int m = Integer.parseInt(minute.getText().toString().trim());
      if (h < 1 || h > 12 || m < 0 || m > 59) throw new IllegalArgumentException("Giờ hoặc phút không hợp lệ");
      boolean pm = ampm.getSelectedItemPosition() == 1;
      return (h % 12) + (pm ? 12 : 0);
    }
    int getMinute() {
      int h = Integer.parseInt(hour.getText().toString().trim());
      int m = Integer.parseInt(minute.getText().toString().trim());
      if (h < 1 || h > 12 || m < 0 || m > 59) throw new IllegalArgumentException("Giờ hoặc phút không hợp lệ");
      return m;
    }
  }

  private TimeInput addTypedTimeInput(LinearLayout parent, Calendar initial) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    EditText hour = new EditText(this);
    hour.setSingleLine(true);
    hour.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    hour.setEms(2);
    hour.setHint("HH");
    hour.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
    int hour24 = initial.get(Calendar.HOUR_OF_DAY);
    TextView colon = new TextView(this);
    colon.setText(":");
    colon.setTextSize(20);
    EditText minute = new EditText(this);
    minute.setSingleLine(true);
    minute.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    minute.setEms(2);
    minute.setHint("MM");
    minute.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
    Spinner ampm = new Spinner(this);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"AM", "PM"});
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    ampm.setAdapter(adapter);
    ampm.setSelection(hour24 >= 12 ? 1 : 0);
    row.addView(hour, new LinearLayout.LayoutParams(dp(64), dp(52)));
    row.addView(colon, new LinearLayout.LayoutParams(dp(18), -2));
    row.addView(minute, new LinearLayout.LayoutParams(dp(64), dp(52)));
    row.addView(ampm, new LinearLayout.LayoutParams(dp(90), dp(52)));
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return new TimeInput(hour, minute, ampm, row);
  }

  private void setTypedTime(TimeInput input, int minuteOfDay) {
    int hour24 = ((minuteOfDay / 60) % 24);
    int hour12 = hour24 % 12;
    if (hour12 == 0) hour12 = 12;
    input.hour.setText(String.valueOf(hour12));
    input.minute.setText(String.format(Locale.US, "%02d", minuteOfDay % 60));
    input.ampm.setSelection(hour24 >= 12 ? 1 : 0);
  }

  private JSONArray getSavedRecordingSchedules() {
    try { return new JSONArray(getSharedPreferences("recording_schedules", MODE_PRIVATE).getString("items", "[]")); }
    catch (Exception e) { return new JSONArray(); }
  }

  private void saveRecordingSchedules(JSONArray items) {
    getSharedPreferences("recording_schedules", MODE_PRIVATE).edit().putString("items", items.toString()).apply();
  }

  private void addRecordingSchedule(JSONObject item) {
    JSONArray old = getSavedRecordingSchedules();
    JSONArray updated = new JSONArray();
    try {
      for (int i = 0; i < old.length(); i++) {
        JSONObject existing = old.getJSONObject(i);
        if (existing.optLong("start", 0L) > System.currentTimeMillis()) updated.put(existing);
      }
      updated.put(item);
    } catch (Exception ignored) { updated.put(item); }
    saveRecordingSchedules(updated);
  }

  private void cancelRecordingSchedule(long startMillis) {
    int request = (int)(startMillis / 60000L);
    Intent intent = new Intent(this, RecordAlarmReceiver.class);
    PendingIntent pi = PendingIntent.getBroadcast(this, request, intent,
      PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    AlarmManager am = (AlarmManager)getSystemService(ALARM_SERVICE);
    if (am != null) am.cancel(pi);
    pi.cancel();
    JSONArray old = getSavedRecordingSchedules();
    JSONArray updated = new JSONArray();
    for (int i = 0; i < old.length(); i++) {
      JSONObject item = old.optJSONObject(i);
      if (item != null && item.optLong("start", 0L) != startMillis) updated.put(item);
    }
    saveRecordingSchedules(updated);
  }

  private void populateRecordingScheduleList(LinearLayout container) {
    container.removeAllViews();
    JSONArray items = getSavedRecordingSchedules();
    long nowMillis = System.currentTimeMillis();
    JSONArray upcoming = new JSONArray();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null && item.optLong("start", 0L) > nowMillis) upcoming.put(item);
    }
    saveRecordingSchedules(upcoming);
    TextView heading = new TextView(this);
    heading.setText("Danh sách lịch hẹn ghi âm (" + upcoming.length() + ")");
    heading.setTextSize(16);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setPadding(0, dp(4), 0, dp(4));
    container.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    if (upcoming.length() == 0) {
      TextView empty = new TextView(this);
      empty.setText("Chưa có lịch ghi âm nào sắp tới.");
      empty.setTextColor(0xFF666666);
      empty.setPadding(0, 0, 0, dp(8));
      container.addView(empty, new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    for (int i = 0; i < upcoming.length(); i++) {
      JSONObject item = upcoming.optJSONObject(i);
      if (item == null) continue;
      final long startMillis = item.optLong("start", 0L);
      long endMillis = startMillis + item.optInt("duration", 60) * 60000L;
      java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, dp(4), 0, dp(4));
      TextView detail = new TextView(this);
      detail.setText(item.optString("name", "Đài radio") + "\n" + fmt.format(new Date(startMillis)) + " – " + new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(endMillis)) +
        " • " + (item.optBoolean("silent", true) ? "ghi im lặng" : "có âm thanh")
        + (item.optBoolean("repeat", false) ? " • lặp lại mỗi ngày" : " • một lần"));
      detail.setTextSize(13);
      row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1f));
      Button edit = btn("Sửa");
      edit.setTextSize(12);
      row.addView(edit, new LinearLayout.LayoutParams(dp(56), dp(38)));
      final JSONObject itemToEdit = item;
      edit.setOnClickListener(v -> {
        editingSchedule = itemToEdit;
        if (scheduleListDialog != null && scheduleListDialog.isShowing()) scheduleListDialog.dismiss();
        scheduleDialog();
      });
      Button cancel = btn("Hủy");
      cancel.setTextSize(12);
      row.addView(cancel, new LinearLayout.LayoutParams(dp(60), dp(38)));
      cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
        .setMessage((item.optBoolean("repeat", false) ? "Hủy lịch ghi âm mỗi ngày của kênh " : "Hủy lịch ghi âm kênh ") + item.optString("name", "") + "?")
        .setNegativeButton("Không", null)
        .setPositiveButton("Hủy lịch", (d, w) -> {
          cancelRecordingSchedule(startMillis);
          populateRecordingScheduleList(container);
          toast("Đã hủy lịch ghi âm.");
        }).show());
      container.addView(row, new LinearLayout.LayoutParams(-1, -2));
      View line = new View(this);
      line.setBackgroundColor(0xFFE0E0E0);
      container.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
  }

  // ---- Clock ----
  private int clockStyle() { return prefs.getInt("clock_style", 0); }
  private void applyClockStyle() {
    if (clockHolder == null) return;
    int style = clockStyle();
    clockDigital.setVisibility(style < 2 ? View.VISIBLE : View.GONE);
    clockAnalog.setVisibility(style == 2 ? View.VISIBLE : View.GONE);
    if (titleView != null) {
      // Keep the title clear of the clock.
      int right = style == 1 ? dp(140) : dp(120);
      titleView.setPadding(dp(8), dp(8), right, dp(8));
    }
    updateClock();
  }
  private void updateClock() {
    if (clockHolder == null) return;
    int style = clockStyle();
    if (style == 0) clockDigital.setText(clockFmt24.format(new Date()));
    else if (style == 1) clockDigital.setText(clockFmtAmPm.format(new Date()));
    else clockAnalog.invalidate();
    // Solar date, then lunar date; recomputed only when the day changes.
    Calendar cal = Calendar.getInstance();
    int key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
    if (key != clockDateKey) {
      clockDateKey = key;
      String[] dow = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
      int d = cal.get(Calendar.DAY_OF_MONTH), m = cal.get(Calendar.MONTH) + 1, y = cal.get(Calendar.YEAR);
      clockSolarDate.setText(dow[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + String.format(Locale.US, "%02d/%02d/%d", d, m, y));
      String lunar;
      try { lunar = "ÂL " + LunarCalendar.format(d, m, y); } catch (Exception e) { lunar = ""; }
      clockLunarDate.setText(lunar);
      clockHolder.setContentDescription("Đồng hồ. Dương lịch " + clockSolarDate.getText() + ". " + clockLunarDate.getText() + ". Chạm để đổi kiểu đồng hồ");
    }
  }
  private void showClockStyleDialog() {
    String[] items = {"Đồng hồ số 24 giờ", "Đồng hồ số AM/PM", "Đồng hồ kim"};
    new AlertDialog.Builder(this).setTitle("Kiểu đồng hồ")
      .setSingleChoiceItems(items, clockStyle(), (d, which) -> {
        prefs.edit().putInt("clock_style", which).apply();
        applyClockStyle();
        d.dismiss();
      })
      .setNegativeButton("Đóng", null).show();
  }
  @Override protected void onResume() {
    super.onResume();
    recordingHandler.removeCallbacks(clockTicker);
    updateClock();
    recordingHandler.postDelayed(clockTicker, 1000);
  }
  @Override protected void onPause() {
    saveResumePosition();
    recordingHandler.removeCallbacks(clockTicker);
    super.onPause();
  }

  /** Round analog clock with hour, minute and second hands. */
  private static final class AnalogClockView extends View {
    private final android.graphics.Paint face = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint tick = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint hourHand = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint minuteHand = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint secondHand = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint numerals = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint ampm = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final float density;
    AnalogClockView(android.content.Context c) {
      super(c);
      density = c.getResources().getDisplayMetrics().density;
      numerals.setColor(0xFF222222); numerals.setTextAlign(android.graphics.Paint.Align.CENTER); numerals.setTextSize(9f * density);
      numerals.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
      ampm.setColor(0xFF1565C0); ampm.setTextAlign(android.graphics.Paint.Align.CENTER); ampm.setTextSize(8f * density);
      ampm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
      face.setColor(0xFFFFFFFF); face.setStyle(android.graphics.Paint.Style.FILL);
      ring.setColor(0xFF555555); ring.setStyle(android.graphics.Paint.Style.STROKE); ring.setStrokeWidth(1.5f * density);
      tick.setColor(0xFF555555); tick.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      hourHand.setColor(0xFF222222); hourHand.setStrokeWidth(3f * density); hourHand.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      minuteHand.setColor(0xFF222222); minuteHand.setStrokeWidth(2f * density); minuteHand.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      secondHand.setColor(0xFFD32F2F); secondHand.setStrokeWidth(1f * density); secondHand.setStrokeCap(android.graphics.Paint.Cap.ROUND);
    }
    @Override protected void onMeasure(int w, int h) { int d = (int) (80 * density); setMeasuredDimension(d, d); }
    private void hand(android.graphics.Canvas cv, float cx, float cy, float deg, float len, android.graphics.Paint p) {
      double rad = Math.toRadians(deg);
      cv.drawLine(cx, cy, cx + (float) (len * Math.sin(rad)), cy - (float) (len * Math.cos(rad)), p);
    }
    @Override protected void onDraw(android.graphics.Canvas cv) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 2 * density;
      cv.drawCircle(cx, cy, r, face);
      cv.drawCircle(cx, cy, r, ring);
      for (int i = 0; i < 12; i++) {
        double rad = Math.toRadians(i * 30);
        float inner = r - 0.09f * r;
        tick.setStrokeWidth((i % 3 == 0 ? 1.5f : 1f) * density);
        cv.drawLine(cx + (float) (inner * Math.sin(rad)), cy - (float) (inner * Math.cos(rad)),
          cx + (float) ((r - density) * Math.sin(rad)), cy - (float) ((r - density) * Math.cos(rad)), tick);
      }
      // Numbers 1..12 inside the dial
      android.graphics.Paint.FontMetrics fm = numerals.getFontMetrics();
      float numR = r * 0.72f;
      for (int n = 1; n <= 12; n++) {
        double rad = Math.toRadians(n * 30);
        float tx = cx + (float) (numR * Math.sin(rad));
        float ty = cy - (float) (numR * Math.cos(rad)) - (fm.ascent + fm.descent) / 2f;
        cv.drawText(String.valueOf(n), tx, ty, numerals);
      }
      Calendar c = Calendar.getInstance();
      // AM / PM label inside the dial (below the centre)
      android.graphics.Paint.FontMetrics fm2 = ampm.getFontMetrics();
      cv.drawText(c.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", cx, cy + r * 0.34f - (fm2.ascent + fm2.descent) / 2f, ampm);
      int h = c.get(Calendar.HOUR) % 12, m = c.get(Calendar.MINUTE), s = c.get(Calendar.SECOND);
      hand(cv, cx, cy, (h + m / 60f) * 30f, r * 0.42f, hourHand);
      hand(cv, cx, cy, (m + s / 60f) * 6f, r * 0.62f, minuteHand);
      hand(cv, cx, cy, s * 6f, r * 0.8f, secondHand);
      cv.drawCircle(cx, cy, 2f * density, hourHand);
    }
  }

  private void showRecordingScheduleListDialog() {
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(16), dp(8), dp(16), dp(8));
    populateRecordingScheduleList(list);
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -2));
    scheduleListDialog = new AlertDialog.Builder(this).setTitle("Lịch hẹn ghi âm").setView(scroll)
      .setNegativeButton("Đóng", null).create();
    scheduleListDialog.show();
  }

  private void scheduleDialog() {
    if (stations == null || stations.isEmpty()) { toast("Chưa có danh sách kênh để hẹn ghi âm."); return; }

    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(12), dp(8), dp(12), dp(8));

    final JSONObject editing = editingSchedule;
    editingSchedule = null;
    TextView stationLabel = new TextView(this);
    stationLabel.setText("Chọn kênh muốn hẹn giờ ghi âm");
    box.addView(stationLabel, new LinearLayout.LayoutParams(-1, -2));

    List<String> stationNames = new ArrayList<>();
    for (Station station : stations) stationNames.add(station.name);
    AutoCompleteTextView stationPicker = new AutoCompleteTextView(this);
    stationPicker.setSingleLine(true);
    stationPicker.setHint("Nhập tên kênh để tìm kiếm...");
    stationPicker.setThreshold(1);
    stationPicker.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    ArrayAdapter<String> stationAdapter = new ArrayAdapter<>(this,
      android.R.layout.simple_dropdown_item_1line, stationNames);
    stationPicker.setAdapter(stationAdapter);
    stationPicker.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus && stationPicker.getText().length() > 0) stationPicker.showDropDown();
    });
    String requestedStationName = pendingScheduleIntent == null ? null
      : pendingScheduleIntent.getStringExtra("schedule_station_name");
    // Pre-fill: station requested from the schedule screen, else the station currently being listened to, else empty.
    String initialStationName = editing != null && stationNames.contains(editing.optString("name", ""))
      ? editing.optString("name", "")
      : (requestedStationName != null && stationNames.contains(requestedStationName)
        ? requestedStationName : (current != null && stationNames.contains(current.name) ? current.name : ""));
    stationPicker.setText(initialStationName, false);
    box.addView(stationPicker, new LinearLayout.LayoutParams(-1, dp(48)));

    TextView startTimeLabel = new TextView(this);
    startTimeLabel.setText("Giờ bắt đầu (nhập giờ, phút và chọn AM/PM)");
    box.addView(startTimeLabel, new LinearLayout.LayoutParams(-1, -2));
    TimeInput time = addTypedTimeInput(box, Calendar.getInstance());
    int prefStart = pendingScheduleIntent == null ? -1
      : pendingScheduleIntent.getIntExtra("schedule_start_minute", -1);
    int prefEnd = pendingScheduleIntent == null ? -1
      : pendingScheduleIntent.getIntExtra("schedule_end_minute", -1);
    if (prefStart >= 0) setTypedTime(time, prefStart);
    if (editing != null) {
      Calendar es = Calendar.getInstance();
      es.setTimeInMillis(editing.optLong("start", System.currentTimeMillis()));
      setTypedTime(time, es.get(Calendar.HOUR_OF_DAY) * 60 + es.get(Calendar.MINUTE));
    }

    CheckBox silent = new CheckBox(this);
    silent.setText("Ghi âm im lặng — không phát ra loa");
    silent.setChecked(editing == null || editing.optBoolean("silent", true));
    box.addView(silent, new LinearLayout.LayoutParams(-1, -2));
    CheckBox repeatDaily = new CheckBox(this);
    repeatDaily.setText("Lặp lại mỗi ngày vào giờ này");
    repeatDaily.setChecked(editing != null ? editing.optBoolean("repeat", false) : prefs.getBoolean("schedule_repeat_daily", true));
    box.addView(repeatDaily, new LinearLayout.LayoutParams(-1, -2));

    TextView modeLabel = new TextView(this);
    modeLabel.setText("Cách kết thúc ghi âm");
    box.addView(modeLabel, new LinearLayout.LayoutParams(-1, -2));

    RadioGroup stopMode = new RadioGroup(this);
    stopMode.setOrientation(RadioGroup.VERTICAL);
    RadioButton byDuration = new RadioButton(this);
    byDuration.setText("Ghi theo thời lượng");
    byDuration.setId(View.generateViewId());
    RadioButton byEndTime = new RadioButton(this);
    byEndTime.setText("Ghi đến giờ kết thúc");
    byEndTime.setId(View.generateViewId());
    stopMode.addView(byDuration, new RadioGroup.LayoutParams(-1, -2));
    stopMode.addView(byEndTime, new RadioGroup.LayoutParams(-1, -2));
    stopMode.check(byDuration.getId());
    box.addView(stopMode, new LinearLayout.LayoutParams(-1, -2));

    TextView durationLabel = new TextView(this);
    durationLabel.setText("Thời lượng ghi âm (phút)");
    box.addView(durationLabel, new LinearLayout.LayoutParams(-1, -2));
    NumberPicker duration = new NumberPicker(this);
    duration.setMinValue(1);
    duration.setMaxValue(360);
    duration.setValue(editing != null ? Math.max(1, Math.min(360, editing.optInt("duration", 60))) : 60);
    box.addView(duration, new LinearLayout.LayoutParams(-1, -2));

    TextView endLabel = new TextView(this);
    endLabel.setText("Giờ kết thúc ghi âm");
    box.addView(endLabel, new LinearLayout.LayoutParams(-1, -2));
    Calendar defaultEnd = Calendar.getInstance();
    defaultEnd.add(Calendar.MINUTE, 60);
    TimeInput endTime = addTypedTimeInput(box, defaultEnd);
    if (prefEnd >= 0) setTypedTime(endTime, prefEnd);
    endLabel.setVisibility(View.GONE);
    endTime.setVisibility(View.GONE);
    stopMode.setOnCheckedChangeListener((group, checkedId) -> {
      boolean useEndTime = checkedId == byEndTime.getId();
      durationLabel.setVisibility(useEndTime ? View.GONE : View.VISIBLE);
      duration.setVisibility(useEndTime ? View.GONE : View.VISIBLE);
      endLabel.setVisibility(useEndTime ? View.VISIBLE : View.GONE);
      endTime.setVisibility(useEndTime ? View.VISIBLE : View.GONE);
    });
    if (prefStart >= 0 && prefEnd >= 0) stopMode.check(byEndTime.getId());

    pendingScheduleIntent = null;
    new AlertDialog.Builder(this).setTitle(editing != null ? "Sửa lịch ghi âm" : "Hẹn giờ ghi âm").setView(box)
      .setPositiveButton(editing != null ? "Lưu thay đổi" : "Đặt lịch", (dialog, which) -> {
        String selectedName = stationPicker.getText().toString().trim();
        Station scheduledStation = null;
        for (Station candidate : stations) {
          if (candidate.name.equalsIgnoreCase(selectedName)) {
            scheduledStation = candidate;
            break;
          }
        }
        if (scheduledStation == null) {
          toast("Hãy tìm và chọn một kênh có trong danh sách.");
          return;
        }
        if (scheduledStation.url == null || scheduledStation.url.trim().isEmpty()) {
          toast("Kênh \"" + scheduledStation.name + "\" chưa có URL stream trực tiếp nên chưa thể hẹn ghi âm.");
          return;
        }
        Calendar c = Calendar.getInstance();
        int startHour;
        int startMinute;
        try {
          startHour = time.getHourOfDay();
          startMinute = time.getMinute();
          // Validate the end-time fields too, when that mode is selected.
          if (stopMode.getCheckedRadioButtonId() == byEndTime.getId()) {
            endTime.getHourOfDay();
            endTime.getMinute();
          }
        } catch (Exception invalidTime) {
          toast("Giờ không hợp lệ. Nhập giờ 1–12, phút 00–59 và chọn AM/PM.");
          return;
        }
        c.set(Calendar.HOUR_OF_DAY, startHour);
        c.set(Calendar.MINUTE, startMinute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        int recordingMinutes = duration.getValue();
        if (stopMode.getCheckedRadioButtonId() == byEndTime.getId()) {
          int endHour = endTime.getHourOfDay();
          int endMinute = endTime.getMinute();
          Calendar end = (Calendar) c.clone();
          end.set(Calendar.HOUR_OF_DAY, endHour);
          end.set(Calendar.MINUTE, endMinute);
          end.set(Calendar.SECOND, 0);
          end.set(Calendar.MILLISECOND, 0);
          if (end.getTimeInMillis() <= c.getTimeInMillis()) end.add(Calendar.DAY_OF_YEAR, 1);
          recordingMinutes = (int)Math.max(1L, (end.getTimeInMillis() - c.getTimeInMillis() + 59999L) / 60000L);
        }
        prefs.edit().putBoolean("schedule_repeat_daily", repeatDaily.isChecked()).apply();
        JSONObject saved = new JSONObject();
        try {
          saved.put("start", c.getTimeInMillis());
          saved.put("name", scheduledStation.name);
          saved.put("url", scheduledStation.url);
          saved.put("duration", recordingMinutes);
          saved.put("silent", silent.isChecked());
          saved.put("repeat", repeatDaily.isChecked());
        } catch (Exception ignored) { }
        // Editing replaces the old schedule (cancel its alarm and entry first).
        if (editing != null) cancelRecordingSchedule(editing.optLong("start", 0L));
        RecordingSchedules.setAlarm(this, saved);
        addRecordingSchedule(saved);
        String stopDescription = stopMode.getCheckedRadioButtonId() == byEndTime.getId()
          ? ("đến " + String.format(Locale.getDefault(), "%02d:%02d %s",
              (endTime.getHourOfDay() % 12 == 0 ? 12 : endTime.getHourOfDay() % 12),
              endTime.getMinute(), endTime.ampm.getSelectedItemPosition() == 1 ? "PM" : "AM"))
          : (recordingMinutes + " phút");
        toast((editing != null ? "Đã sửa lịch ghi âm kênh " : "Đã đặt lịch ghi âm kênh ") + scheduledStation.name + " — " + stopDescription + " — " +
          (silent.isChecked() ? "im lặng" : "có âm thanh") + (repeatDaily.isChecked() ? " — lặp lại mỗi ngày" : " — một lần"));
      }).setNegativeButton("Hủy", null).show();
  }

  @Override public void onDestroy() {
    super.onDestroy();
    // Do not keep worker threads alive after the screen is closed.
    checker.shutdownNow();
    scheduleChecker.shutdownNow();
  }

  private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
