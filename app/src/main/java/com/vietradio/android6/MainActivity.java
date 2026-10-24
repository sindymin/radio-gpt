package com.vietradio.android6;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.graphics.Rect;
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

public class MainActivity extends Activity {
  private LinearLayout list;
  private AutoCompleteTextView search;
  private ArrayAdapter<String> searchSuggestions;
  private TextView now;
  private AudioManager audio;
  private SharedPreferences prefs;
  private List<Station> stations;
  private Station current;
  private CheckBox playableOnly;
  private Button checkStreamsButton;
  private Button checkScheduleLinksButton;
  private Button allStationsButton;
  private Button favoritesButton;
  private LinearLayout selectedStationPanel;
  private TextView selectedStationName;
  private TextView selectedStationStatus;
  private TextView selectedStationScheduleStatus;
  private Button selectedFavoriteButton;
  private Button selectedPlayButton;
  private boolean showingFavorites = false;
  private final Map<String, Integer> streamStatus = new HashMap<>(); // -1=unknown, 1=responding, 0=unavailable
  private final Map<String, Integer> scheduleLinkStatus = new HashMap<>(); // 0=not checked, 1=has schedule, 2=no schedule today, -1=failed
  private final ExecutorService checker = Executors.newFixedThreadPool(4);
  private final ExecutorService scheduleChecker = Executors.newFixedThreadPool(4);
  private int scheduleChecksPending = 0;
  private int checksPending = 0;

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
    buildUi();
    RadioPlayer.setStateListener((stationName, isPlaying) -> {
      if (list != null) filter(search == null ? "" : search.getText().toString());
      updateSelectedStationPanel();
    });
    // Refresh in the background. If the network/API is unavailable, the bundled
    // list plus the last successful cached response remain available offline.
    refreshStationListFromApi();
  }

  private void refreshStationListFromApi() {
    checker.execute(() -> {
      List<Station> remote = StationApi.fetch();
      if (remote == null || remote.isEmpty()) return;
      StationApi.saveCache(this, remote);
      List<Station> merged = StationRepository.merge(StationRepository.all(), remote);
      runOnUiThread(() -> {
        String selectedName = current == null ? null : current.name;
        stations = merged;
        refreshSearchSuggestions();
        if (selectedName != null) {
          for (Station station : stations) {
            if (station.name.equals(selectedName)) { current = station; break; }
          }
        }
        filter(search == null ? "" : search.getText().toString());
        toast("Đã cập nhật danh sách đài trực tuyến (" + remote.size() + " mục từ API).");
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
    root.setOrientation(LinearLayout.VERTICAL);
    root.setFocusableInTouchMode(true);
    root.setPadding(dp(8), dp(4), dp(8), dp(4));
    page.addView(root, new ScrollView.LayoutParams(-1, -2));
    screen.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));

    TextView title = new TextView(this);
    title.setText("📻 RADIO VIỆT NAM");
    title.setTextSize(24);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setPadding(dp(8), dp(8), dp(8), dp(8));
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
          RadioPlayer.play(MainActivity.this, station);
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

    LinearLayout topActions = new LinearLayout(this);
    topActions.setOrientation(LinearLayout.HORIZONTAL);
    Button scheduleAll = btn("Lịch phát sóng");
    Button home = btn("Trang chính");
    topActions.addView(scheduleAll, weight(1));
    topActions.addView(home, weight(1));
    root.addView(topActions, new LinearLayout.LayoutParams(-1, -2));
    scheduleAll.setOnClickListener(v -> startActivity(new Intent(this, RadioScheduleActivity.class)));

    playableOnly = new CheckBox(this);
    playableOnly.setText("Chỉ hiện kênh máy chủ đang phản hồi");
    playableOnly.setTextSize(14);
    playableOnly.setPadding(dp(6), dp(2), dp(6), dp(2));
    root.addView(playableOnly, new LinearLayout.LayoutParams(-1, -2));
    playableOnly.setOnCheckedChangeListener((button, checked) -> filter(search.getText().toString()));

    checkStreamsButton = btn("Kiểm tra trạng thái các đài");
    root.addView(checkStreamsButton, new LinearLayout.LayoutParams(-1, dp(44)));
    checkStreamsButton.setOnClickListener(v -> checkAllStreams());

    checkScheduleLinksButton = btn("Kiểm tra link lịch tất cả các kênh");
    root.addView(checkScheduleLinksButton, new LinearLayout.LayoutParams(-1, dp(44)));
    checkScheduleLinksButton.setOnClickListener(v -> checkAllScheduleLinks());

    Button source = btn("Mở nguồn lịch trong ứng dụng");
    root.addView(source, new LinearLayout.LayoutParams(-1, dp(44)));
    source.setOnClickListener(v -> {
      Intent intent = new Intent(MainActivity.this, ScheduleSourceActivity.class);
      intent.putExtra("url", "https://app.giavangtygia.com/api/schedule");
      startActivity(intent);
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
    selectedStationPanel.setBackgroundColor(0xFFEAF3FF);
    selectedStationName = new TextView(this);
    selectedStationName.setTextSize(16);
    selectedStationName.setTypeface(null, android.graphics.Typeface.BOLD);
    selectedStationName.setPadding(dp(4), dp(2), dp(4), dp(2));
    selectedStationPanel.addView(selectedStationName, new LinearLayout.LayoutParams(-1, -2));
    selectedStationStatus = new TextView(this);
    selectedStationStatus.setTextSize(12);
    selectedStationStatus.setPadding(dp(4), 0, dp(4), dp(1));
    selectedStationPanel.addView(selectedStationStatus, new LinearLayout.LayoutParams(-1, -2));
    selectedStationScheduleStatus = new TextView(this);
    selectedStationScheduleStatus.setTextSize(12);
    selectedStationScheduleStatus.setPadding(dp(4), 0, dp(4), dp(2));
    selectedStationPanel.addView(selectedStationScheduleStatus, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout selectedControls = new LinearLayout(this);
    selectedControls.setOrientation(LinearLayout.HORIZONTAL);
    selectedFavoriteButton = btn("☆ Yêu thích");
    selectedPlayButton = btn("▶ Phát");
    Button selectedScheduleButton = btn("Lịch");
    selectedControls.addView(selectedFavoriteButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedControls.addView(selectedPlayButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedControls.addView(selectedScheduleButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    selectedStationPanel.addView(selectedControls, new LinearLayout.LayoutParams(-1, -2));
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
      RadioPlayer.toggle(MainActivity.this, current);
      updateSelectedStationPanel();
    });
    selectedScheduleButton.setOnClickListener(v -> {
      if (current == null) { toast("Hãy chọn một đài"); return; }
      Intent intent = new Intent(MainActivity.this, RadioScheduleActivity.class);
      intent.putExtra("stationName", current.name);
      startActivity(intent);
    });

    // Fixed controls at the bottom: selected-station card, volume, then recording actions.
    LinearLayout volumeBar = new LinearLayout(this);
    volumeBar.setOrientation(LinearLayout.HORIZONTAL);
    volumeBar.setGravity(Gravity.CENTER_VERTICAL);
    volumeBar.setPadding(dp(6), dp(1), dp(6), dp(1));
    volumeBar.setBackgroundColor(0xFFF1F1F1);
    Button down = btn("− Giảm âm lượng");
    Button up = btn("Tăng âm lượng +");
    volumeBar.addView(down, new LinearLayout.LayoutParams(0, dp(42), 1));
    volumeBar.addView(up, new LinearLayout.LayoutParams(0, dp(42), 1));
    screen.addView(volumeBar, new LinearLayout.LayoutParams(-1, dp(46)));

    LinearLayout recordBar = new LinearLayout(this);
    recordBar.setOrientation(LinearLayout.HORIZONTAL);
    recordBar.setPadding(dp(6), dp(1), dp(6), dp(2));
    Button rec = btn("● Ghi âm");
    Button timer = btn("◷ Hẹn giờ ghi âm");
    recordBar.addView(rec, new LinearLayout.LayoutParams(0, dp(44), 1));
    recordBar.addView(timer, new LinearLayout.LayoutParams(0, dp(44), 1));
    screen.addView(recordBar, new LinearLayout.LayoutParams(-1, dp(48)));

    setContentView(screen);
    root.requestFocus();
    search.clearFocus();
    render(stations);
    updateSelectedStationPanel();

    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
      public void afterTextChanged(Editable e) {}
    });
    down.setOnClickListener(v -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI));
    up.setOnClickListener(v -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI));
    rec.setOnClickListener(v -> recordDialog());
    timer.setOnClickListener(v -> scheduleDialog());
    home.setOnClickListener(v -> {
      showingFavorites = false;
      current = null;
      updateStationFilterButtons();
      search.setText("");
      filter("");
      now.setText("Trang chính • Chọn một đài");
      updateSelectedStationPanel();
      page.smoothScrollTo(0, 0);
      root.requestFocus();
    });
  }

  private void updateSelectedStationPanel() {
    if (selectedStationPanel == null) return;
    if (current == null) {
      selectedStationPanel.setVisibility(View.GONE);
      return;
    }
    selectedStationPanel.setVisibility(View.VISIBLE);
    selectedStationName.setText(current.name);
    String playbackState;
    if (RadioPlayer.isPlayingStation(current.name)) playbackState = "Đang phát radio";
    else if (RadioPlayer.isCurrentStation(current.name)) playbackState = "Đã chọn • đang tạm dừng hoặc kết nối";
    else playbackState = "Đã chọn kênh";
    selectedStationStatus.setText(playbackState + " • " + statusText(current));
    selectedStationScheduleStatus.setText(scheduleLinkStatusText(current));
    selectedFavoriteButton.setText(prefs.getBoolean("fav_" + current.name, false) ? "★ Đã yêu thích" : "☆ Yêu thích");
    selectedPlayButton.setText(RadioPlayer.isPlayingStation(current.name) ? "Ⅱ Tạm dừng" : "▶ Phát");
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
      Button play = btn(RadioPlayer.isCurrentStation(s.name) ? "■" : "▶");
      play.setContentDescription(RadioPlayer.isCurrentStation(s.name) ? "Dừng kênh " + s.name : "Phát kênh " + s.name);
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
        RadioPlayer.play(this, s);
      };
      // Tapping the station name, its information area, or unused row space
      // should open that station immediately, just like the play button.
      name.setClickable(true);
      name.setOnClickListener(choose);
      stationInfo.setOnClickListener(choose);
      row.setOnClickListener(choose);
      play.setOnClickListener(v -> {
        if (RadioPlayer.isCurrentStation(s.name)) {
          RadioPlayer.stop();
          now.setText("Đã dừng: " + s.name);
          updateSelectedStationPanel();
        } else {
          current = s;
          now.setText("Đang mở: " + s.name);
          updateSelectedStationPanel();
          RadioPlayer.play(this, s);
        }
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
      boolean matchesPlayableFilter = playableOnly == null || !playableOnly.isChecked() || status == 1;
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
    return state == 1 ? "Máy chủ phản hồi / luồng có vẻ khả dụng" : "Không truy cập được hoặc luồng lỗi";
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
      try {
        indexHtml = downloadScheduleText("https://app.giavangtygia.com/api/schedule", false);
      } catch (Exception ignored) { }
      final String index = indexHtml;
      if (index == null || index.trim().isEmpty()) {
        runOnUiThread(() -> {
          for (Station st : stations) scheduleLinkStatus.put(st.name, -1);
          checkScheduleLinksButton.setText("Thử kiểm tra lại link lịch");
          checkScheduleLinksButton.setEnabled(true);
          filter(search == null ? "" : search.getText().toString());
          toast("Không tải được danh sách đài từ nguồn lịch.");
        });
        return;
      }
      Map<String, String> ids = new HashMap<>();
      for (Station st : stations) ids.put(st.name, findScheduleRadioId(index, st.name));
      runOnUiThread(() -> {
        scheduleChecksPending = stations.size();
        for (Station st : stations) scheduleLinkStatus.put(st.name, 0);
        checkScheduleLinksButton.setText("Đang kiểm tra link lịch (" + scheduleChecksPending + ")");
        filter(search == null ? "" : search.getText().toString());
      });
      for (Station st : stations) {
        final Station station = st;
        final String radioId = ids.get(st.name);
        scheduleChecker.execute(() -> {
          int result = -1;
          if (radioId != null && !radioId.trim().isEmpty()) {
            try {
              String address = "https://app.giavangtygia.com/api/scheduleInfo?imgNm=" + java.net.URLEncoder.encode(radioId, "UTF-8");
              String body = downloadScheduleText(address, true).trim();
              if (!body.isEmpty()) {
                String lower = body.toLowerCase(Locale.ROOT);
                result = (lower.contains("chưa có lịch phát sóng") || lower.contains("chưa có lịch") || lower.contains("quay lại sau")) ? 2 : 1;
              } else result = 2;
            } catch (Exception ignored) { result = -1; }
          }
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
            filter(search == null ? "" : search.getText().toString());
            updateSelectedStationPanel();
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
      connection.setConnectTimeout(9000);
      connection.setReadTimeout(12000);
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
      while ((count = input.read(buffer)) != -1 && out.size() < 1200000) out.write(buffer, 0, count);
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

  /** Checks actual network response, rather than merely checking whether the URL is non-empty. */
  private void checkAllStreams() {
    checksPending = 0;
    for (Station station : stations) {
      if (station.url == null || station.url.trim().isEmpty()) {
        streamStatus.put(station.name, 0);
      } else {
        streamStatus.put(station.name, -1);
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
      checker.execute(() -> {
        boolean available = isStreamResponding(station.url, 0);
        runOnUiThread(() -> {
          streamStatus.put(station.name, available ? 1 : 0);
          checksPending--;
          if (checkStreamsButton != null) {
            checkStreamsButton.setText(checksPending > 0 ? "Đang kiểm tra... (" + checksPending + ")" : "Kiểm tra lại trạng thái các đài");
            checkStreamsButton.setEnabled(checksPending <= 0);
          }
          filter(search == null ? "" : search.getText().toString());
        });
      });
    }
    if (checksPending == 0 && checkStreamsButton != null) {
      checkStreamsButton.setText("Kiểm tra lại trạng thái các đài");
      checkStreamsButton.setEnabled(true);
    }
  }

  private boolean isStreamResponding(String address, int depth) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(address);
      connection = (HttpURLConnection) url.openConnection();
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

  @Override public void onDestroy() {
    super.onDestroy();
    // Do not keep worker threads alive after the screen is closed.
    checker.shutdownNow();
    scheduleChecker.shutdownNow();
  }

  private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
