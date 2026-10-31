package com.vietradio.android6;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/** Saved recording schedules (SharedPreferences "recording_schedules") and their alarms. Daily schedules re-arm themselves. */
final class RecordingSchedules {
    private RecordingSchedules() {}

    static final String PREFS = "recording_schedules";

    static JSONArray load(Context c) {
        try { return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static void save(Context c, JSONArray items) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", items.toString()).apply();
    }

    /** Arms the alarm for item.start. Extras carry everything the receiver needs, including repeat info. */
    static void setAlarm(Context c, JSONObject item) {
        long start = item.optLong("start", 0L);
        if (start <= 0L) return;
        Intent intent = new Intent(c, RecordAlarmReceiver.class);
        intent.putExtra("url", item.optString("url", ""));
        intent.putExtra("name", item.optString("name", ""));
        intent.putExtra("silent", item.optBoolean("silent", true));
        intent.putExtra("durationMinutes", item.optInt("duration", 60));
        intent.putExtra("start", start);
        intent.putExtra("repeat", item.optBoolean("repeat", false));
        int request = (int) (start / 60000L);
        PendingIntent pi = PendingIntent.getBroadcast(c, request, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, pi);
        else if (Build.VERSION.SDK_INT >= 19) am.setExact(AlarmManager.RTC_WAKEUP, start, pi);
        else am.set(AlarmManager.RTC_WAKEUP, start, pi);
    }

    private static long nextDay(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** Called when a daily schedule has just fired: move it to the next day and arm the next alarm. */
    static void rescheduleAfterFire(Context c, long firedStart) {
        try {
            JSONArray items = load(c);
            long now = System.currentTimeMillis();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.optLong("start", 0L) != firedStart || !item.optBoolean("repeat", false)) continue;
                long next = nextDay(firedStart);
                while (next <= now) next = nextDay(next);
                item.put("start", next);
                save(c, items);
                setAlarm(c, item);
                return;
            }
        } catch (Exception ignored) { }
    }

    /**
     * Re-arms every saved alarm (app start / device reboot). Daily schedules whose time already passed move to the
     * next future occurrence; one-time schedules in the past are dropped.
     */
    static void rearmAll(Context c) {
        try {
            JSONArray items = load(c);
            JSONArray kept = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                long start = item.optLong("start", 0L);
                boolean repeat = item.optBoolean("repeat", false) && !item.optString("url", "").isEmpty();
                if (repeat) while (start <= now) start = nextDay(start);
                if (start <= now) continue;
                item.put("start", start);
                kept.put(item);
                if (!item.optString("url", "").isEmpty()) setAlarm(c, item);
            }
            save(c, kept);
        } catch (Exception ignored) { }
    }
}
