package com.vietradio.android6;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Fetches radio stations from the public API used by the supplied Radio Vietnam app.
 * The bundled station list remains the offline fallback. */
public final class StationApi {
  private static final String PREFS = "radio_station_api";
  private static final String CACHE_KEY = "last_good_station_list";
  private static final String PACKAGE_ID = "com.mobitwins.radiovietnamonline";
  private static final String[] ENDPOINTS = {
      "https://radiovn-9845dd793694.herokuapp.com/api/v3/radios/radiovn",
      "https://liveapi.giavangtygia.com/api/v3/rvn"
  };

  private StationApi() {}

  public static List<Station> loadCached(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String json = prefs.getString(CACHE_KEY, null);
    if (json == null || json.trim().isEmpty()) return new ArrayList<>();
    try { return parse(json); } catch (Exception ignored) { return new ArrayList<>(); }
  }

  public static void saveCache(Context context, List<Station> stations) {
    if (stations == null || stations.isEmpty()) return;
    JSONArray array = new JSONArray();
    try {
      for (Station station : stations) {
        if (station == null || station.name == null || station.url == null || station.url.trim().isEmpty()) continue;
        JSONObject item = new JSONObject();
        item.put("name", station.name);
        item.put("streamUrl", station.url);
        item.put("website", station.website == null ? "" : station.website);
        array.put(item);
      }
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CACHE_KEY, array.toString()).apply();
    } catch (Exception ignored) { }
  }

  /** Tries both known endpoints; returns the first useful response, or an empty list. */
  public static List<Station> fetch() {
    for (String endpoint : ENDPOINTS) {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "Radio-Vietnam/6.8.6 (" + PACKAGE_ID + ")");
        byte[] body = new JSONObject().put("data", PACKAGE_ID).toString().getBytes(Charset.forName("UTF-8"));
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream out = connection.getOutputStream();
        out.write(body);
        out.flush();
        out.close();
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (input == null || status < 200 || status >= 300) continue;
        String response = readAll(input);
        input.close();
        List<Station> parsed = parse(response);
        if (parsed.size() >= 5) return parsed;
      } catch (Exception ignored) {
        // Try the second endpoint. Caller retains bundled/cached data if both fail.
      } finally {
        if (connection != null) connection.disconnect();
      }
    }
    return new ArrayList<>();
  }

  private static String readAll(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    return new String(output.toByteArray(), Charset.forName("UTF-8"));
  }

  private static List<Station> parse(String json) throws Exception {
    List<Station> result = new ArrayList<>();
    Object root = new org.json.JSONTokener(json).nextValue();
    JSONArray data = null;
    if (root instanceof JSONArray) data = (JSONArray) root;
    else if (root instanceof JSONObject) {
      JSONObject object = (JSONObject) root;
      Object dataValue = object.opt("data");
      if (dataValue instanceof JSONArray) data = (JSONArray) dataValue;
      else if (dataValue instanceof JSONObject) {
        JSONObject nested = (JSONObject) dataValue;
        if (nested.opt("data") instanceof JSONArray) data = nested.optJSONArray("data");
      }
    }
    if (data == null) return result;
    for (int i = 0; i < data.length() && i < 1000; i++) {
      JSONObject item = data.optJSONObject(i);
      if (item == null) continue;
      String name = first(item, "name", "title", "radioName");
      String stream = first(item, "streamUrl", "streamURL", "url", "stream_url");
      String website = first(item, "website", "web", "homepage");
      if (name == null || name.trim().isEmpty() || stream == null || stream.trim().isEmpty()) continue;
      if (!(stream.startsWith("http://") || stream.startsWith("https://"))) continue;
      result.add(new Station(name.trim(), stream.trim(), website == null ? "https://aimobi.pages.dev/radiovn/index.html" : website.trim()));
    }
    return result;
  }

  private static String first(JSONObject item, String... keys) {
    for (String key : keys) {
      String value = item.optString(key, "");
      if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) return value;
    }
    return null;
  }
}
