package com.codex.shinsegaeattendance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

public final class ScheduleManager {
    public static final String PREFS = "attendance_prefs";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_HOUR = "hour";
    public static final String KEY_MINUTE = "minute";
    public static final String KEY_PRIVACY_CURTAIN = "privacy_curtain";
    public static final String KEY_REMINDER_ENABLED = "reminder_enabled";
    private static final int REQUEST_CODE = 2409;

    private ScheduleManager() {}

    public static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public static long nextTriggerMillis(int hour, int minute) {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
    }

    public static boolean canScheduleExact(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms();
    }

    public static void schedule(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean automationEnabled = prefs.getBoolean(KEY_ENABLED, false);
        boolean reminderEnabled = prefs.getBoolean(KEY_REMINDER_ENABLED, false);
        if (!automationEnabled && !reminderEnabled) {
            cancel(context);
            return;
        }
        if (!canScheduleExact(context)) return;

        int hour = prefs.getInt(KEY_HOUR, 10);
        int minute = prefs.getInt(KEY_MINUTE, 0);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTriggerMillis(hour, minute),
                pendingIntent(context)
        );
    }

    public static void cancel(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarm.cancel(pendingIntent(context));
    }
}
