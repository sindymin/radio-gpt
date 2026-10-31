package com.vietradio.android6;

import android.text.Html;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads a station's programme schedule (same sources as RadioScheduleActivity) and finds the programme airing now. */
final class ScheduleFetcher {
    private ScheduleFetcher() {}

    /** One programme: start/end minute of day (end = -1 if unknown) and title. */
    static final class Program {
        final int startMin; final int endMin; final String title;
        Program(int s, int e, String t) { startMin = s; endMin = e; title = t; }
    }

    private static final String INDEX_URL = "https://app.giavangtygia.com/api/schedule";

    /** Blocking; call from a background thread. Returns an empty list if the station has no schedule. */
    static List<String> fetchEntries(String stationName) {
        try {
            String official = officialVohScheduleUrl(stationName);
            if (official != null) {
                try {
                    String html = download(official, false);
                    List<String> e = parseApiSchedule(html);
                    if (e.isEmpty()) e = parseEntries(html, stationName);
                    if (!e.isEmpty()) return e;
                } catch (Exception ignored) {}
            }
            String radioId = null;
            for (int attempt = 0; attempt < 3 && (radioId == null || radioId.trim().isEmpty()); attempt++) {
                radioId = findRadioId(download(INDEX_URL, false), stationName);
                if ((radioId == null || radioId.trim().isEmpty()) && attempt < 2) Thread.sleep(650L * (attempt + 1));
            }
            if (radioId == null || radioId.trim().isEmpty()) return new ArrayList<>();
            String apiUrl = "https://app.giavangtygia.com/api/scheduleInfo?imgNm=" + java.net.URLEncoder.encode(radioId, "UTF-8");
            List<String> parsed = new ArrayList<>();
            for (int attempt = 0; attempt < 3 && parsed.isEmpty(); attempt++) {
                parsed = parseApiSchedule(download(apiUrl, true));
                if (parsed.isEmpty() && attempt < 2) Thread.sleep(650L * (attempt + 1));
            }
            return parsed;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Link check using exactly the same sources and parsing as the schedule screen.
     * Returns 1 = has schedule, 2 = server answered but there is no schedule today, -1 = failed to get an answer.
     * indexHtml is the already-downloaded station index (may be null; it is re-downloaded if the station is not found in it).
     */
    static int check(String stationName, String indexHtml) {
        try {
            String official = officialVohScheduleUrl(stationName);
            if (official != null) {
                try {
                    String html = download(official, false);
                    List<String> e = parseApiSchedule(html);
                    if (e.isEmpty()) e = parseEntries(html, stationName);
                    if (!e.isEmpty()) return 1;
                } catch (Exception ignored) {}
            }
            String radioId = indexHtml == null ? null : findRadioId(indexHtml, stationName);
            for (int attempt = 0; attempt < 3 && (radioId == null || radioId.trim().isEmpty()); attempt++) {
                Thread.sleep(650L * (attempt + 1));
                radioId = findRadioId(download(INDEX_URL, false), stationName);
            }
            if (radioId == null || radioId.trim().isEmpty()) return -1;
            String apiUrl = "https://app.giavangtygia.com/api/scheduleInfo?imgNm=" + java.net.URLEncoder.encode(radioId, "UTF-8");
            boolean failed = false;
            boolean explicitNone = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    String body = download(apiUrl, true);
                    failed = false;
                    if (!parseApiSchedule(body).isEmpty()) return 1;
                    String lower = body.trim().toLowerCase(Locale.ROOT);
                    if (lower.contains("chưa có lịch") || lower.contains("quay lại sau")) { explicitNone = true; break; }
                } catch (Exception e) {
                    failed = true;
                }
                if (attempt < 2) Thread.sleep(800L * (attempt + 1));
            }
            if (explicitNone) return 2;
            return failed ? -1 : 2;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The programme whose slot contains the device's current time, or null (also null if entries is empty). */
    static Program currentProgram(List<String> entries) {
        if (entries == null || entries.isEmpty()) return null;
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        for (int i = 0; i < entries.size(); i++) {
            String[] parts = entries.get(i).split("\\n", 2);
            int[] range = parseTimeRange(parts[0]);
            if (range == null) continue;
            int start = range[0], end = range[1];
            if (end < 0) {
                // No end time: use the next programme's start; for the last one of the day, wrap to the first of the day.
                int[] next = parseTimeRange(entries.get(i + 1 < entries.size() ? i + 1 : 0).split("\\n", 2)[0]);
                if (next != null && (i + 1 < entries.size() || entries.size() > 1)) end = next[0];
            }
            if (end < 0) continue;
            boolean airing = end < start ? (now >= start || now < end) : (now >= start && now < end);
            if (airing) return new Program(start, end, parts.length > 1 ? parts[1] : "");
        }
        return null;
    }

    static String formatMinute(int m) { return String.format(Locale.US, "%02d:%02d", (m / 60) % 24, m % 60); }

        private static String officialVohScheduleUrl(String stationName) {
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

        private static String download(String address, boolean scheduleInfo) throws Exception {
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

        private static String findRadioId(String html, String wantedName) {
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

        private static String normalizeName(String value) {
            if (value == null) return "";
            String v = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
            return v.toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
        }

        private static String extractFrequency(String name) {
            Matcher m = Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(name == null ? "" : name);
            return m.find() ? m.group() : "";
        }
        private static List<String> parseApiSchedule(String html) {
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
    private static List<String> parseEntries(String html, String stationName) {
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

    private static String vohSection(String html, String stationName) {
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

    private static boolean containsTime(String value) {
        return Pattern.compile("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)").matcher(value).find();
    }

    private static String cleanText(String html) {
        String value = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ").replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ").replaceAll("(?s)<[^>]+>", " ");
        value = Html.fromHtml(value).toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return value;
    }

    private static List<String> deduplicate(List<String> items) {
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

    private static int[] parseTimeRange(String value) {
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
}
