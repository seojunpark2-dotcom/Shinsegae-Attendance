package com.codex.shinsegaeattendance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        android.content.SharedPreferences prefs = appContext
                .getSharedPreferences(ScheduleManager.PREFS, Context.MODE_PRIVATE);
        boolean reminderEnabled = prefs.getBoolean(ScheduleManager.KEY_REMINDER_ENABLED, false);
        boolean automationEnabled = prefs.getBoolean(ScheduleManager.KEY_ENABLED, false);
        // Capture and launch automation before posting our notification. A
        // heads-up notification can otherwise make this app look like the
        // previously foreground app to the accessibility service.
        if (automationEnabled) {
            AttendanceAccessibilityService.requestScheduledRun(appContext);
        }
        if (reminderEnabled) {
            Notifier.show(
                    appContext,
                    "신세계 출석 알림",
                    "오늘의 출석체크를 아직 완료하지 않았어요!"
            );
        }
        ScheduleManager.schedule(appContext);
    }
}
