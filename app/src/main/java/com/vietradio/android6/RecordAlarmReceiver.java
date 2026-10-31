package com.vietradio.android6;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class RecordAlarmReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    Intent s = new Intent(c, RadioRecordService.class);
    s.putExtra("url", i.getStringExtra("url"));
    s.putExtra("name", i.getStringExtra("name"));
    s.putExtra("silent", i.getBooleanExtra("silent", true));
    s.putExtra("durationMinutes", i.getIntExtra("durationMinutes", 60));
    if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
    // Daily schedules: arm tomorrow's alarm now so the schedule keeps repeating.
    if (i.getBooleanExtra("repeat", false)) RecordingSchedules.rescheduleAfterFire(c, i.getLongExtra("start", 0L));
  }
}
