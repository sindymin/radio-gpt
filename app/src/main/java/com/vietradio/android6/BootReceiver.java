package com.vietradio.android6;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are lost on reboot: re-arm the saved recording schedules. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        RecordingSchedules.rearmAll(c);
    }
}
