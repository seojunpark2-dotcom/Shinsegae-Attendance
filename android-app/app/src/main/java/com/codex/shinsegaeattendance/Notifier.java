package com.codex.shinsegaeattendance;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class Notifier {
    // Android does not allow raising the importance of an existing channel.
    private static final String RESULT_CHANNEL_ID = "attendance_result_popup_v2";
    private static final String REMINDER_CHANNEL_ID = "attendance_reminder";

    private Notifier() {}

    public static String resultChannelId() {
        return RESULT_CHANNEL_ID;
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel result = new NotificationChannel(
                RESULT_CHANNEL_ID, "자동 출석 결과 팝업", NotificationManager.IMPORTANCE_HIGH);
        result.setDescription("자동 출석 완료 또는 실패 원인을 팝업으로 표시합니다.");
        manager.createNotificationChannel(result);
        manager.createNotificationChannel(new NotificationChannel(
                REMINDER_CHANNEL_ID, "예약 시각 출석 알림", NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static void show(Context context, String title, String message) {
        showOnChannel(context, RESULT_CHANNEL_ID, title, message);
    }

    public static void showReminder(Context context, String title, String message) {
        showOnChannel(context, REMINDER_CHANNEL_ID, title, message);
    }

    public static void showVerification(Context context, String title, String message) {
        Intent open = context.getPackageManager().getLaunchIntentForPackage("com.F1.ShinSG");
        if (open == null) open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        showOnChannel(context, RESULT_CHANNEL_ID, title, message, open);
    }

    private static void showOnChannel(Context context, String channelId, String title, String message) {
        showOnChannel(context, channelId, title, message, new Intent(context, MainActivity.class));
    }

    private static void showOnChannel(Context context, String channelId, String title, String message, Intent open) {
        createChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        PendingIntent pending = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        android.app.Notification notification = new android.app.Notification.Builder(context, channelId)
                .setSmallIcon(com.codex.shinsegaeattendance.R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(message))
                .setPriority(channelId.equals(RESULT_CHANNEL_ID)
                        ? android.app.Notification.PRIORITY_HIGH
                        : android.app.Notification.PRIORITY_DEFAULT)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify((int) (System.currentTimeMillis() & 0xfffffff), notification);
    }
}

