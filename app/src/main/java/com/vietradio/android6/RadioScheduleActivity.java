package com.vietradio.android6;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.graphics.Rect;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native schedule screen: downloads schedule pages and renders parsed entries as Android views. */
public class RadioScheduleActivity extends Activity {
    private AutoCompleteTextView stationSearch;
    private LinearLayout entries;
    private ScrollView scheduleScroll;
    private TextView status;
    private ProgressBar progress;
    private Button refreshButton;
    private List<Station> stations;
    private String currentSourceUrl = "";
    private String currentWebSourceUrl = "https://app.giavangtygia.com/api/schedule";
    private String currentStationName = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        stations = StationRepository.all();
        buildScreen();
        String requestedName = getIntent().getStringExtra("stationName");
        int requested = getIntent().getIntExtra("stationIndex", -1);
        if (requestedName != null && !requestedName.trim().isEmpty()) {
            // When opened from a station row, show that station in the search field.
            stationSearch.setText(requestedName, false);
            loadScheduleForName(requestedName);
        } else if (requested >= 0 && requested < stations.size()) {
            String requestedStation = stations.get(requested).name;
            stationSearch.setText(requestedStation, false);
            loadScheduleForName(requestedStation);
        } else {
            status.setText("Nhập tên đài vào ô tìm kiếm rồi chọn một kết quả để xem lịch phát sóng.");
        }
    }

    private void buildScreen() {
        // Keep the home button outside the scrolling area so it remains visible
        // at the top while the user scrolls through a long program schedule.
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFFFFFFFF);

        Button homeButton = makeButton("← Trở về trang chính");
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(-1, dp(48));
        homeParams.setMargins(dp(12), dp(8), dp(12), dp(4));
        screen.addView(homeButton, homeParams);
        homeButton.setOnClickListener(v -> finish());

        ScrollView scroll = new ScrollView(this);
        scheduleScroll = scroll;
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(dp(12), dp(8), dp(12), dp(12));
        root.setBackgroundColor(0xFFFFFFFF);
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = new TextView(this);
        title.setText("LỊCH PHÁT SÓNG");
        title.setTextSize(23);
        title.setTextColor(0xFF111111);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(12));
        root.addView(title);

        TextView label = new TextView(this);
        label.setText("Chọn đài radio");
        label.setTextSize(15);
        label.setTextColor(0xFF222222);
        root.addView(label);

        stationSearch = new AutoCompleteTextView(this);
        stationSearch.setSingleLine(true);
        stationSearch.setTextSize(16);
        stationSearch.setTextColor(0xFF111111);
        stationSearch.setHintTextColor(0xFF777777);
        stationSearch.setBackgroundColor(0xFFFFFFFF);
        stationSearch.setHint("Tìm kiếm tên đài...");
        stationSearch.setThreshold(1);
        stationSearch.setPadding(dp(10), dp(6), dp(10), dp(6));
        List<String> names = new ArrayList<>();
        for (Station s : stations) names.add(s.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, names);
        stationSearch.setAdapter(adapter);
        root.addView(stationSearch, new LinearLayout.LayoutParams(-1, dp(52)));
        stationSearch.setOnItemClickListener((parent, view, position, id) -> loadSchedule());

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = makeButton("Cập nhật lịch");
        refreshButton = refresh;
        Button openSource = makeButton("Mở nguồn lịch");
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(openSource, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(0xFF222222);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status);

        entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        root.addView(entries, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Lịch được tải từ nguồn trực tuyến và hiển thị bằng giao diện của ứng dụng. Một số đài không công bố lịch dạng bảng; khi đó ứng dụng sẽ báo rõ thay vì tự tạo lịch giả.");
        note.setTextSize(12);
        note.setTextColor(0xFF555555);
        note.setPadding(0, dp(16), 0, dp(8));
        root.addView(note);

        setContentView(screen);
        root.requestFocus();
        stationSearch.clearFocus();
        refresh.setOnClickListener(v -> loadSchedule());
        openSource.setOnClickListener(v -> {
            String url = "https://app.giavangtygia.com/api/schedule";
            currentWebSourceUrl = url;
            Intent sourceIntent = new Intent(RadioScheduleActivity.this, ScheduleSourceActivity.class);
            sourceIntent.putExtra("url", url);
            startActivity(sourceIntent);
        });
    }

    private void loadSchedule() {
        if (stationSearch == null || stations == null || stations.isEmpty()) return;
        String query = stationSearch.getText().toString().trim();
        if (query.isEmpty() && currentStationName != null && !currentStationName.isEmpty()) {
            loadScheduleForName(currentStationName);
            return;
        }
        loadScheduleForName(query);
    }

    private void loadScheduleForName(String stationName) {
        hideSearchKeyboard();
        if (stations == null || stations.isEmpty()) return;
        Station station = null;
        for (Station candidate : stations) {
            if (candidate.name.equalsIgnoreCase(stationName.trim())) { station = candidate; break; }
        }
        if (station == null) {
            status.setText("Hãy nhập tên đài rồi chọn một kết quả trong danh sách gợi ý.");
            return;
        }
        currentStationName = station.name;
        currentSourceUrl = sourceFor(station.name); // API endpoint used by the native schedule parser
        currentWebSourceUrl = webSourceFor(station.name); // API source opened inside the app
        entries.removeAllViews();
        progress.setVisibility(View.VISIBLE);
        refreshButton.setEnabled(false);
        status.setText("Đang tải lịch của " + station.name + "…");
        new FetchScheduleTask(station.name, currentSourceUrl).execute();
    }

    private String sourceFor(String name) {
        // The source's /api/schedule endpoint contains radio-item entries and data-id values.
        // /api/scheduleInfo requires the matching data-id as imgNm.
        return "https://app.giavangtygia.com/api/schedule";
    }

    private String webSourceFor(String name) {
        // Source button opens the same API endpoint used by the native schedule parser.
        return "https://app.giavangtygia.com/api/schedule";
    }

    private final class FetchScheduleTask extends AsyncTask<Void, Void, FetchResult> {
        private final String stationName;
        private final String sourceUrl;
        FetchScheduleTask(String stationName, String sourceUrl) { this.stationName = stationName; this.sourceUrl = sourceUrl; }

        @Override protected FetchResult doInBackground(Void... ignored) {
            if (sourceUrl == null || sourceUrl.isEmpty()) return new FetchResult("", new ArrayList<String>(), "Chưa tìm được nguồn lịch trực tuyến cho đài này.");
            HttpURLConnection connection = null;
            // For VOH stations, try the station's official schedule page first. This avoids
            // depending exclusively on a third-party API whose certificate chain may not be
            // trusted by older Android 6 certificate stores.
            String officialFirst = officialVohScheduleUrl(stationName);
            if (officialFirst != null) {
                try {
                    String officialHtml = download(officialFirst, false);
                    List<String> officialEntries = parseApiSchedule(officialHtml);
                    if (officialEntries.isEmpty()) officialEntries = parseEntries(officialHtml, stationName);
                    if (!officialEntries.isEmpty()) {
                        return new FetchResult(officialFirst, officialEntries,
                                "Đã lấy lịch từ trang chính thức của VOH lúc " + new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", new Locale("vi", "VN")).format(new Date()));
                    }
                } catch (Exception officialPageError) {
                    // Continue to the shared API; never disable certificate validation.
                }
            }
            try {
                String radioId = null;
                // The schedule index can occasionally arrive before its radio-item/data-id
                // markup is complete. Re-fetch it a few times before declaring the station missing.
                for (int attempt = 0; attempt < 3 && (radioId == null || radioId.trim().isEmpty()); attempt++) {
                    String indexHtml = download(sourceUrl, false);
                    radioId = findRadioId(indexHtml, stationName);
                    if (radioId == null || radioId.trim().isEmpty()) {
                        if (attempt < 2) Thread.sleep(650L * (attempt + 1));
                    }
                }
                if (radioId == null || radioId.trim().isEmpty()) {
                    // Keep the official VOH fallback, but only after retrying the shared index.
                    String officialUrl = officialVohScheduleUrl(stationName);
                    if (officialUrl != null) {
                        String officialHtml = download(officialUrl, false);
                        List<String> officialEntries = parseApiSchedule(officialHtml);
                        if (officialEntries.isEmpty()) officialEntries = parseEntries(officialHtml, stationName);
                        return new FetchResult(officialUrl, officialEntries,
                                officialEntries.isEmpty()
                                ? "Đã thử lại nguồn lịch nhưng chưa tìm thấy mã đài; nguồn dự phòng VOH cũng chưa đọc được lịch. Hãy bấm Cập nhật lịch để thử lại."
                                : "Đã lấy lịch từ trang chính thức của VOH lúc " + new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", new Locale("vi", "VN")).format(new Date()));
                    }
                    return new FetchResult(sourceUrl, new ArrayList<String>(), "Đã thử tải lại nguồn lịch 3 lần nhưng chưa tìm thấy mã của đài ‘" + stationName + "’. Hãy bấm Cập nhật lịch để thử lại.");
                }
                String apiUrl = "https://app.giavangtygia.com/api/scheduleInfo?imgNm=" + java.net.URLEncoder.encode(radioId, "UTF-8");
                List<String> parsed = new ArrayList<>();
                // Retry the detail endpoint too; a first response may be temporarily empty.
                for (int attempt = 0; attempt < 3 && parsed.isEmpty(); attempt++) {
                    String scheduleHtml = download(apiUrl, true);
                    parsed = parseApiSchedule(scheduleHtml);
                    if (parsed.isEmpty() && attempt < 2) Thread.sleep(650L * (attempt + 1));
                }
                return new FetchResult(apiUrl, parsed, parsed.isEmpty() ? "Đã thử tải lịch 3 lần nhưng máy chủ chưa trả về nội dung lịch. Hãy bấm Cập nhật lịch để thử lại." : "Đã cập nhật lịch lúc " + new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", new Locale("vi", "VN")).format(new Date()));
            } catch (Exception e) {
                // If the shared API fails (including SSL trust errors), retry the official
                // VOH schedule page when this is a VOH station. SSL verification remains on.
                String officialUrl = officialVohScheduleUrl(stationName);
                if (officialUrl != null) {
                    try {
                        String officialHtml = download(officialUrl, false);
                        List<String> fallbackEntries = parseApiSchedule(officialHtml);
                        if (fallbackEntries.isEmpty()) fallbackEntries = parseEntries(officialHtml, stationName);
                        if (!fallbackEntries.isEmpty()) {
                            return new FetchResult(officialUrl, fallbackEntries,
                                    "API phụ bị lỗi; đã chuyển sang lịch chính thức của VOH lúc " + new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", new Locale("vi", "VN")).format(new Date()));
                        }
                    } catch (Exception fallbackError) {
                        // Report both causes below, without weakening TLS security.
                    }
                }
                return new FetchResult(sourceUrl, new ArrayList<String>(), "Không tải được lịch (" + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "không rõ lỗi" : e.getMessage()) + "). Bản 3.12 đang dùng chế độ tương thích SSL thử nghiệm cho một số nguồn lịch.");
            } finally { if (connection != null) connection.disconnect(); }
        }

        private String officialVohScheduleUrl(String stationName) {
            String n = normalizeName(stationName);
            if (!n.contains("voh")) return null;
            if (n.contains("877")) return "https://voh.com.vn/radio-lich-phat-song-fm-877-fm877mhz.html";
            if (n.contains("999")) return "https://voh.com.vn/radio-lich-phat-song-fm-999-fm999mhz.html";
            if (n.contains("956")) return "https://voh.com.vn/radio-lich-phat-song-fm-956-fm956mhz.html";
            if (n.contains("610")) return "https://voh.com.vn/radio-lich-phat-song-am-610-am610khz.html";
            // The current VOH site uses FM 92-92.5 for the merged VOH-M channel.
            if (n.contains("92")) return "https://voh.com.vn/radios/schedule";
            return null;
        }

        private String download(String address, boolean scheduleInfo) throws Exception {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(address).openConnection();
                if (c instanceof javax.net.ssl.HttpsURLConnection) LegacyTls.applyForScheduleHost((javax.net.ssl.HttpsURLConnection) c, address);
                c.setRequestMethod("GET");
                c.setConnectTimeout(12000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
                c.setRequestProperty("Cache-Control", "no-cache, no-store");
                c.setRequestProperty("Pragma", "no-cache");
                if (scheduleInfo) c.setRequestProperty("X-CSRF-TOKEN", String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)));
                int code = c.getResponseCode();
                if (code < 200 || code >= 400) throw new java.io.IOException("HTTP " + code);
                InputStream stream = c.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && body.length() < 1800000) body.append(line).append('\n');
                reader.close();
                return body.toString();
            } finally { if (c != null) c.disconnect(); }
        }

        private String findRadioId(String html, String wantedName) {
            String wanted = normalizeName(wantedName);
            Matcher tags = Pattern.compile("(?is)<([a-z0-9]+)\\b([^>]*class\\s*=\\s*['\"][^'\"]*radio-item[^'\"]*['\"][^>]*)>").matcher(html);
            List<Integer> starts = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            while (tags.find()) {
                String attrs = tags.group(2);
                Matcher idMatcher = Pattern.compile("(?is)data-id\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(attrs);
                if (idMatcher.find()) { starts.add(tags.start()); ids.add(idMatcher.group(1)); }
            }
            for (int i = 0; i < starts.size(); i++) {
                int a = starts.get(i);
                int b = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
                String block = html.substring(a, b);
                Matcher titleMatcher = Pattern.compile("(?is)class\\s*=\\s*['\"][^'\"]*radio-title[^'\"]*['\"][^>]*>(.*?)</").matcher(block);
                String title = titleMatcher.find() ? cleanText(titleMatcher.group(1)) : cleanText(block.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " "));
                String normalized = normalizeName(title);
                // Normalization may turn "87.7MHz" into "87 7mhz" while the app label
                // is "87.7 MHz" -> "87 7 mhz". Compare a compact form as well.
                String compactTitle = normalized.replace(" ", "");
                String compactWanted = wanted.replace(" ", "");
                if (!normalized.isEmpty() && (normalized.equals(wanted) || normalized.contains(wanted) || wanted.contains(normalized)
                        || compactTitle.equals(compactWanted) || compactTitle.contains(compactWanted) || compactWanted.contains(compactTitle))) return ids.get(i);
                // Match station and frequency separately when labels contain extra wording.
                String wantedFrequency = extractFrequency(wantedName).replace(".", "");
                String titleFrequency = extractFrequency(title).replace(".", "");
                if (normalized.contains("binh duong") && wanted.contains("binh duong")) return ids.get(i);
                if (normalized.contains("voh") && wanted.contains("voh")
                        && (wantedFrequency.isEmpty() || titleFrequency.equals(wantedFrequency))) return ids.get(i);
            }
            return null;
        }

        private String normalizeName(String value) {
            if (value == null) return "";
            String v = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
            return v.toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
        }

        private String extractFrequency(String name) {
            Matcher m = Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(name == null ? "" : name);
            return m.find() ? m.group() : "";
        }

        private List<String> parseApiSchedule(String html) {
            List<String> out = new ArrayList<>();
            Matcher timeTags = Pattern.compile("(?is)<([a-z0-9]+)\\b[^>]*class\\s*=\\s*['\"][^'\"]*time_live[^'\"]*['\"][^>]*>(.*?)</\\1>").matcher(html);
            while (timeTags.find()) {
                String time = cleanText(timeTags.group(2));
                if (time.isEmpty()) continue;
                int from = Math.max(0, timeTags.start() - 700);
                int to = Math.min(html.length(), timeTags.end() + 700);
                String around = html.substring(from, to);
                String title = "";
                // The schedule item is the parent of .time_live; extract that item's text.
                int parentStart = html.lastIndexOf("<", timeTags.start());
                int parentStart2 = parentStart > 0 ? html.lastIndexOf("<", parentStart - 1) : -1;
                if (parentStart2 >= 0) {
                    int parentEnd = html.indexOf(">", parentStart2);
                    if (parentEnd >= 0) {
                        String parentTag = html.substring(parentStart2 + 1, parentEnd).split("\\s+")[0];
                        Matcher parent = Pattern.compile("(?is)<" + Pattern.quote(parentTag) + "\\b[^>]*>(.*?)</" + Pattern.quote(parentTag) + "\\s*>").matcher(html.substring(parentStart2));
                        if (parent.find()) {
                            String txt = cleanText(parent.group(1));
                            txt = txt.replace(time, " ").replaceAll("\\s+", " ").trim();
                            if (txt.length() > 1 && txt.length() < 220) title = txt;
                        }
                    }
                }
                if (title.isEmpty()) {
                    String plain = cleanText(around).replace(time, " ").replaceAll("\\s+", " ").trim();
                    if (plain.length() > 1 && plain.length() < 220) title = plain;
                }
                if (!title.isEmpty()) out.add(time + "\n" + title);
            }
            if (!out.isEmpty()) return deduplicate(out);
            // Fallback to the existing parser for API responses whose markup differs.
            return parseEntries(html, "");
        }

        @Override protected void onPostExecute(FetchResult result) {
            progress.setVisibility(View.GONE);
            refreshButton.setEnabled(true);
            status.setText(result.message);
            entries.removeAllViews();
            if (result.entries.isEmpty()) {
                TextView empty = new TextView(RadioScheduleActivity.this);
                empty.setText("Chưa hiển thị được lịch của " + stationName + ".\n\nỨng dụng không tự bịa giờ phát hay tên chương trình. Bạn có thể bấm ‘Mở nguồn lịch’ để xem nguồn gốc; nếu trang đổi cấu trúc, cần cập nhật bộ đọc lịch.");
                empty.setTextSize(15);
                empty.setTextColor(0xFF222222);
                empty.setPadding(dp(12), dp(16), dp(12), dp(16));
                empty.setBackgroundColor(0xFFF2F2F2);
                entries.addView(empty);
                return;
            }
            int currentIndex = -1;
            for (int i = 0; i < result.entries.size(); i++) {
                addEntry(result.entries.get(i), result.entries, i);
                if (isCurrentlyAiring(result.entries.get(i), result.entries, i)) currentIndex = i;
            }
            // Center the currently airing program in the visible screen when the schedule opens/refreshes.
            if (currentIndex >= 0) {
                final int targetIndex = currentIndex;
                scheduleScroll.post(() -> {
                    if (targetIndex < 0 || targetIndex >= entries.getChildCount()) return;
                    View target = entries.getChildAt(targetIndex * 2); // each program row is followed by a divider
                    if (target == null || scheduleScroll.getHeight() <= 0) return;
                    int[] rootLocation = new int[2];
                    int[] targetLocation = new int[2];
                    scheduleScroll.getLocationOnScreen(rootLocation);
                    target.getLocationOnScreen(targetLocation);
                    int targetTop = scheduleScroll.getScrollY() + targetLocation[1] - rootLocation[1];
                    int centeredY = targetTop - (scheduleScroll.getHeight() - target.getHeight()) / 2;
                    scheduleScroll.smoothScrollTo(0, Math.max(0, centeredY));
                });
            }
        }
    }

    private static final class FetchResult {
        final String url; final List<String> entries; final String message;
        FetchResult(String url, List<String> entries, String message) { this.url = url; this.entries = entries; this.message = message; }
    }

    private List<String> parseEntries(String html, String stationName) {
        String relevant = html.replaceAll("(?i)(\\d{1,2})h(\\d{2})", "$1:$2");
        if (stationName.toLowerCase(Locale.ROOT).contains("voh")) relevant = vohSection(relevant, stationName);
        List<String> out = new ArrayList<>();
        Pattern rowPattern = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
        Matcher rows = rowPattern.matcher(relevant);
        while (rows.find()) {
            String row = rows.group(1);
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>").matcher(row);
            while (cellMatcher.find()) {
                String cell = cleanText(cellMatcher.group(1));
                if (!cell.isEmpty()) cells.add(cell);
            }
            if (cells.size() >= 2 && containsTime(cells.get(0))) {
                String time = cells.get(0);
                String title = cells.get(1);
                if (title.length() > 180) title = title.substring(0, 177) + "…";
                out.add(time + "\n" + title);
            }
        }
        if (!out.isEmpty()) return deduplicate(out);

        // Fallback for schedule cards/divs that are not represented as HTML tables.
        String lineText = relevant
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(tr|td|th|div|li|p|h[1-6]|section|article|header|footer)>\\s*", "\n")
                .replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        lineText = Html.fromHtml(lineText).toString().replace('\u00a0', ' ');
        String[] lines = lineText.split("[\\r\\n]+");
        Pattern timePattern = Pattern.compile("^\\s*((?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s*[-–—]\\s*(?:[01]?\\d|2[0-3]):[0-5]\\d)?)\\s*(.*)$");
        for (int i = 0; i < lines.length; i++) {
            String current = lines[i].trim();
            Matcher tm = timePattern.matcher(current);
            if (!tm.find()) continue;
            String title = tm.group(2).trim();
            if (title.length() < 2) {
                for (int j = i + 1; j < lines.length && j <= i + 4; j++) {
                    String candidate = lines[j].trim();
                    if (candidate.isEmpty() || candidate.length() < 2 || containsTime(candidate)) continue;
                    if (candidate.length() <= 180 && !candidate.equalsIgnoreCase("lịch phát sóng") && !candidate.equalsIgnoreCase("đang cập nhật")) { title = candidate; break; }
                }
            }
            if (title.length() >= 2) out.add(tm.group(1).trim() + "\n" + title);
        }
        return deduplicate(out);
    }

    private String vohSection(String html, String stationName) {
        String key;
        String n = stationName.toLowerCase(Locale.ROOT);
        if (n.contains("99.9")) key = "FM 99.9";
        else if (n.contains("95.6")) key = "FM 95.6";
        else if (n.contains("87.7")) key = "FM 87.7";
        else if (n.contains("92")) key = "FM 92-92.5";
        else key = "AM 610";
        String lower = html.toLowerCase(Locale.ROOT);
        int start = lower.indexOf(("radio voh " + key).toLowerCase(Locale.ROOT));
        if (start < 0) return html;
        int next = lower.indexOf("radio voh", start + ("radio voh " + key).length());
        return html.substring(start, next > start ? next : html.length());
    }

    private boolean containsTime(String value) {
        return Pattern.compile("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)").matcher(value).find();
    }

    private String cleanText(String html) {
        String value = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ").replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ").replaceAll("(?s)<[^>]+>", " ");
        value = Html.fromHtml(value).toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return value;
    }

    private List<String> deduplicate(List<String> items) {
        List<String> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String item : items) {
            String[] parts = item.split("\\n", 2);
            String normalized;
            if (parts.length == 2) {
                normalized = parts[0].replaceAll("\\s+", " ").trim() + "\n"
                        + parts[1].replaceAll("\\s+", " ").trim();
            } else {
                normalized = item.replaceAll("\\s+", " ").trim();
            }
            if (normalized.length() > 2 && seen.add(normalized.toLowerCase(Locale.ROOT))) result.add(normalized);
        }
        return result;
    }

    private void addEntry(String text, List<String> allEntries, int index) {
        String[] parts = text.split("\\n", 2);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        TextView time = new TextView(this);
        time.setText(parts[0]);
        time.setTextSize(15);
        time.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        time.setTextColor(0xFF1769AA);
        row.addView(time, new LinearLayout.LayoutParams(dp(100), -2));
        TextView title = new TextView(this);
        title.setText(parts.length > 1 ? parts[1] : "Chương trình");
        title.setTextSize(15);
        if (isCurrentlyAiring(text, allEntries, index)) {
            title.setTextColor(0xFFD00000);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setContentDescription("Hẹn giờ ghi âm: " + (parts.length > 1 ? parts[1] : "Chương trình"));
        row.setOnClickListener(v -> openRecordingForProgram(text, allEntries, index));
        entries.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        entries.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void openRecordingForProgram(String item, List<String> allEntries, int index) {
        String[] parts = item.split("\\n", 2);
        if (parts.length == 0) return;
        int[] range = parseTimeRange(parts[0]);
        if (range == null) {
            Toast.makeText(this, "Không đọc được giờ bắt đầu của chương trình này.", Toast.LENGTH_LONG).show();
            return;
        }
        int end = range[1];
        if (end < 0 && index + 1 < allEntries.size()) {
            String[] nextParts = allEntries.get(index + 1).split("\\n", 2);
            int[] nextRange = nextParts.length > 0 ? parseTimeRange(nextParts[0]) : null;
            if (nextRange != null) end = nextRange[0];
        }
        if (end < 0) {
            Toast.makeText(this, "Chưa xác định được giờ kết thúc từ lịch; chưa thể điền giờ kết thúc tự động.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("schedule_record_prefill", true);
        intent.putExtra("schedule_station_name", currentStationName);
        intent.putExtra("schedule_program_title", parts.length > 1 ? parts[1] : "Chương trình");
        intent.putExtra("schedule_start_minute", range[0]);
        intent.putExtra("schedule_end_minute", end);
        startActivity(intent);
        finish();
    }

    /** Highlights the program whose time slot contains the device's current local time. */
    private boolean isCurrentlyAiring(String item, List<String> allEntries, int index) {
        String[] parts = item.split("\\n", 2);
        if (parts.length == 0) return false;
        int now = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) * 60
                + java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE);
        int[] range = parseTimeRange(parts[0]);
        if (range == null) return false;
        int start = range[0];
        int end = range[1];
        if (end < 0 && index + 1 < allEntries.size()) {
            String[] nextParts = allEntries.get(index + 1).split("\\n", 2);
            int[] nextRange = nextParts.length > 0 ? parseTimeRange(nextParts[0]) : null;
            if (nextRange != null) end = nextRange[0];
        }
        if (end < 0) return false;
        // Support a broadcast slot crossing midnight.
        if (end < start) return now >= start || now < end;
        return now >= start && now < end;
    }

    /** Returns {startMinute, endMinute}; end is -1 if only a start time is supplied. */
    private int[] parseTimeRange(String value) {
        if (value == null) return null;
        Matcher m = Pattern.compile("(?i)\\b([0-2]?\\d):([0-5]\\d)(?:\\s*[-–—至到]\\s*([0-2]?\\d):([0-5]\\d))?").matcher(value);
        if (!m.find()) return null;
        int sh = Integer.parseInt(m.group(1));
        int sm = Integer.parseInt(m.group(2));
        if (sh > 23) return null;
        int start = sh * 60 + sm;
        int end = -1;
        if (m.group(3) != null) {
            int eh = Integer.parseInt(m.group(3));
            int em = Integer.parseInt(m.group(4));
            if (eh > 23) return null;
            end = eh * 60 + em;
        }
        return new int[]{start, end};
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }


    private void hideSearchKeyboard() {
        if (stationSearch == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(stationSearch.getWindowToken(), 0);
        stationSearch.clearFocus();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && stationSearch != null && stationSearch.hasFocus()) {
            Rect bounds = new Rect();
            stationSearch.getGlobalVisibleRect(bounds);
            if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) hideSearchKeyboard();
        }
        return super.dispatchTouchEvent(event);
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}
