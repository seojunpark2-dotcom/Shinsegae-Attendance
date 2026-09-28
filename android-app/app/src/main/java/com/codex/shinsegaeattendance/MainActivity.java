package com.codex.shinsegaeattendance;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView timeText;
    private TextView statusText;
    private int hour;
    private int minute;
    private Switch privacySwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE);
        hour = prefs.getInt(ScheduleManager.KEY_HOUR, 10);
        minute = prefs.getInt(ScheduleManager.KEY_MINUTE, 0);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusText != null) {
            ScheduleManager.schedule(this);
            refresh();
        }
    }

    private View buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(36), pad, pad);
        root.setBackgroundColor(Color.rgb(250, 247, 244));

        TextView title = label("신세계 자동 출석", 28, true);
        root.addView(title);
        TextView intro = label("휴대폰에서 매일 리워드 캘린더 출석을 실행합니다. 계정 정보는 저장하지 않습니다.", 16, false);
        intro.setPadding(0, dp(12), 0, dp(24));
        root.addView(intro);

        timeText = label("", 32, true);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, dp(20), 0, dp(20));
        root.addView(timeText, matchWrap());

        Button choose = button("예약 시각 선택");
        choose.setOnClickListener(v -> new TimePickerDialog(
                this,
                (view, h, m) -> {
                    hour = h;
                    minute = m;
                    prefs.edit()
                            .putInt(ScheduleManager.KEY_HOUR, hour)
                            .putInt(ScheduleManager.KEY_MINUTE, minute)
                            .apply();
                    ScheduleManager.schedule(this);
                    refresh();
                    Toast.makeText(this, "예약 시각과 다음 실행을 변경했습니다.", Toast.LENGTH_SHORT).show();
                },
                hour, minute, DateFormat.is24HourFormat(this)
        ).show());
        root.addView(choose, matchWrap());

        Button enable = button("매일 자동 출석 켜기");
        enable.setOnClickListener(v -> enableSchedule());
        root.addView(enable, matchWrap());

        Button disable = button("자동 출석 끄기");
        disable.setOnClickListener(v -> {
            prefs.edit().putBoolean(ScheduleManager.KEY_ENABLED, false).apply();
            ScheduleManager.schedule(this);
            Toast.makeText(this, "자동 출석을 껐습니다.", Toast.LENGTH_SHORT).show();
            refresh();
        });
        root.addView(disable, matchWrap());

        Switch reminderSwitch = new Switch(this);
        reminderSwitch.setText("출석 알림\n켜면 예약 시각에 ‘오늘의 출석체크를 아직 완료하지 않았어요!’ 알림을 표시합니다.");
        reminderSwitch.setTextSize(16);
        reminderSwitch.setTextColor(Color.rgb(35, 31, 29));
        reminderSwitch.setPadding(dp(4), dp(14), dp(4), dp(14));
        reminderSwitch.setChecked(prefs.getBoolean(ScheduleManager.KEY_REMINDER_ENABLED, false));
        reminderSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(ScheduleManager.KEY_REMINDER_ENABLED, isChecked).apply();
            if (isChecked) {
                requestNotificationPermission();
                if (!ScheduleManager.canScheduleExact(this)) openExactAlarmSettings();
            }
            ScheduleManager.schedule(this);
            Toast.makeText(this,
                    isChecked ? "출석 알림을 켰습니다." : "출석 알림을 껐습니다.",
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
        root.addView(reminderSwitch, matchWrap());

        privacySwitch = new Switch(this);
        privacySwitch.setText("프라이버시 커튼\n화면이 꺼진 상태에서 자동 실행될 때만 검은 화면으로 가립니다.");
        privacySwitch.setTextSize(16);
        privacySwitch.setTextColor(Color.rgb(35, 31, 29));
        privacySwitch.setPadding(dp(4), dp(14), dp(4), dp(14));
        privacySwitch.setChecked(prefs.getBoolean(ScheduleManager.KEY_PRIVACY_CURTAIN, true));
        privacySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(ScheduleManager.KEY_PRIVACY_CURTAIN, isChecked).apply();
            Toast.makeText(this,
                    isChecked ? "프라이버시 커튼을 켰습니다." : "프라이버시 커튼을 껐습니다.",
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
        root.addView(privacySwitch, matchWrap());

        Button accessibility = button("접근성 권한 열기");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrap());

        Button exactAlarm = button("알람 권한 열기");
        exactAlarm.setOnClickListener(v -> openExactAlarmSettings());
        root.addView(exactAlarm, matchWrap());

        Button test = button("수동 실행");
        test.setOnClickListener(v -> AttendanceAccessibilityService.requestManualRun(this));
        root.addView(test, matchWrap());

        statusText = label("", 15, false);
        statusText.setPadding(0, dp(20), 0, 0);
        root.addView(statusText, matchWrap());

        TextView note = label("필수 조건: 휴대폰 전원 켜짐, 신세계 앱 로그인 유지, 예약 시각에 잠금 해제 상태. PIN·지문·패턴 잠금은 자동으로 우회하지 않습니다.", 14, false);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note, matchWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private void enableSchedule() {
        if (!AttendanceAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "먼저 접근성에서 ‘신세계 자동 출석’을 켜주세요.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!ScheduleManager.canScheduleExact(this)) {
            Toast.makeText(this, "먼저 정확한 알람 권한을 허용해주세요.", Toast.LENGTH_LONG).show();
            openExactAlarmSettings();
            return;
        }
        prefs.edit()
                .putInt(ScheduleManager.KEY_HOUR, hour)
                .putInt(ScheduleManager.KEY_MINUTE, minute)
                .putBoolean(ScheduleManager.KEY_ENABLED, true)
                .apply();
        ScheduleManager.schedule(this);
        Toast.makeText(this, "매일 자동 출석을 예약했습니다.", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void refresh() {
        updateTime();
        boolean enabled = prefs.getBoolean(ScheduleManager.KEY_ENABLED, false);
        boolean reminderEnabled = prefs.getBoolean(ScheduleManager.KEY_REMINDER_ENABLED, false);
        String next = "예약 꺼짐";
        if (enabled || reminderEnabled) {
            long at = ScheduleManager.nextTriggerMillis(
                    prefs.getInt(ScheduleManager.KEY_HOUR, 10),
                    prefs.getInt(ScheduleManager.KEY_MINUTE, 0));
            next = "다음 예약: " + new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date(at));
        }
        String lastResult = prefs.getString("last_result", "아직 실행 기록이 없습니다.");
        long lastResultAt = prefs.getLong("last_result_at", 0L);
        String lastResultLine = lastResultAt == 0L
                ? lastResult
                : new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date(lastResultAt)) + " — " + lastResult;
        statusText.setText(
                "상태: " + (enabled ? "켜짐" : "꺼짐") + "\n" + next +
                "\n접근성: " + (AttendanceAccessibilityService.isEnabled(this) ? "허용됨" : "허용 필요") +
                "\n정확한 알람: " + (ScheduleManager.canScheduleExact(this) ? "허용됨" : "허용 필요") +
                "\n출석 알림: " + (reminderEnabled ? "켜짐" : "꺼짐") +
                "\n프라이버시 커튼: " + (prefs.getBoolean(ScheduleManager.KEY_PRIVACY_CURTAIN, true) ? "켜짐" : "꺼짐") +
                "\n마지막 결과: " + lastResultLine
        );
    }

    private void updateTime() {
        if (timeText != null) timeText.setText(String.format(Locale.KOREA, "%02d:%02d", hour, minute));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(35, 31, 29));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(p);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
