package com.codex.shinsegaeattendance;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;

public class MainActivity extends Activity {
    private static final int INK = Color.rgb(20, 20, 20);
    private static final int MUTED = Color.rgb(110, 113, 117);
    private static final int CANVAS = Color.rgb(247, 248, 247);
    private static final int LINE = Color.rgb(231, 233, 231);

    private SharedPreferences prefs;
    private TextView timeText;
    private TextView nextText;
    private TextView resultText;
    private TextView accessibilityState;
    private TextView alarmState;
    private TextView accountCountText;
    private LinearLayout accountList;
    private EditText accountUsernameInput;
    private EditText accountPasswordInput;
    private Switch autoSwitch;
    private Switch reminderSwitch;
    private Switch privacySwitch;
    private boolean updatingUi;
    private int hour;
    private int minute;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The main screen now contains several account IDs and a password
        // field. Keep them out of screenshots and Recents thumbnails.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE);
        Notifier.createChannels(this);
        hour = prefs.getInt(ScheduleManager.KEY_HOUR, 10);
        minute = prefs.getInt(ScheduleManager.KEY_MINUTE, 0);
        setContentView(buildUi());
        if (prefs.getBoolean(ScheduleManager.KEY_ENABLED, false)) requestNotificationPermission();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (timeText != null) {
            ScheduleManager.schedule(this);
            refresh();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CANVAS);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher);
        mark.setBackground(shape(Color.WHITE, 15, LINE, 1));
        header.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout headerWords = column();
        headerWords.setPadding(dp(14), 0, 0, 0);
        headerWords.addView(text("신세계 자동 출석", 21, true, INK));
        headerWords.addView(text("매일의 출석을 간편하게", 13, false, MUTED));
        header.addView(headerWords);
        root.addView(header, spaced(0, 0, 0, 24));

        LinearLayout hero = card(INK, 24, 24);
        TextView eyebrow = text("DAILY ATTENDANCE", 11, true, Color.rgb(175, 178, 181));
        eyebrow.setLetterSpacing(0.15f);
        hero.addView(eyebrow);
        hero.addView(text("매일 예약 시간", 15, false, Color.rgb(225, 225, 225)), spaced(0, 12, 0, 0));
        timeText = text("", 48, true, Color.WHITE);
        timeText.setLetterSpacing(-0.03f);
        hero.addView(timeText, spaced(0, 0, 0, 0));
        nextText = text("", 13, false, Color.rgb(187, 190, 192));
        hero.addView(nextText, spaced(0, 0, 0, 20));
        Button choose = action("시간 변경", false, true);
        choose.setOnClickListener(v -> showTimePicker());
        hero.addView(choose, matchWrap());
        divider(hero, Color.rgb(68, 68, 68), 20);
        autoSwitch = toggle("매일 자동 출석", "예약 시간에 자동으로 실행", true);
        autoSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUi) return;
            if (checked) {
                enableSchedule();
            } else {
                prefs.edit().putBoolean(ScheduleManager.KEY_ENABLED, false).apply();
                ScheduleManager.schedule(this);
                Toast.makeText(this, "자동 출석을 껐습니다.", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        hero.addView(autoSwitch, matchWrap());
        root.addView(hero, spaced(0, 0, 0, 18));

        Button manual = action("지금 수동 실행  →", true, false);
        manual.setOnClickListener(v -> {
            requestNotificationPermission();
            AttendanceAccessibilityService.requestManualRun(this);
        });
        root.addView(manual, spaced(0, 0, 0, 22));

        root.addView(section("최근 실행"), spaced(0, 0, 0, 10));
        LinearLayout resultCard = card(Color.WHITE, 18, 18);
        resultText = text("", 14, false, INK);
        resultCard.addView(resultText);
        root.addView(resultCard, spaced(0, 0, 0, 24));

        root.addView(section("환경 설정"), spaced(0, 0, 0, 10));
        LinearLayout settingsCard = card(Color.WHITE, 20, 18);
        reminderSwitch = toggle("출석 알림", "자동 출석과 별개로 예약 시간에 알림", false);
        reminderSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUi) return;
            prefs.edit().putBoolean(ScheduleManager.KEY_REMINDER_ENABLED, checked).apply();
            if (checked) {
                requestNotificationPermission();
                if (!ScheduleManager.canScheduleExact(this)) openExactAlarmSettings();
            }
            ScheduleManager.schedule(this);
            refresh();
        });
        settingsCard.addView(reminderSwitch, matchWrap());
        divider(settingsCard, LINE, 16);
        privacySwitch = toggle("프라이버시 커튼", "꺼진 화면에서 실행할 때만 가림", false);
        privacySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUi) return;
            prefs.edit().putBoolean(ScheduleManager.KEY_PRIVACY_CURTAIN, checked).apply();
            refresh();
        });
        settingsCard.addView(privacySwitch, matchWrap());
        root.addView(settingsCard, spaced(0, 0, 0, 24));

        root.addView(section("자동 출석 계정"), spaced(0, 0, 0, 10));
        LinearLayout accountsCard = card(Color.WHITE, 20, 18);
        accountCountText = text("", 13, false, MUTED);
        accountsCard.addView(accountCountText);
        accountList = column();
        accountsCard.addView(accountList, spaced(0, 12, 0, 14));
        accountUsernameInput = new EditText(this);
        accountUsernameInput.setHint("신세계 앱 아이디");
        accountUsernameInput.setSingleLine(true);
        accountUsernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        accountUsernameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        styleAccountInput(accountUsernameInput);
        accountsCard.addView(accountUsernameInput, matchWrap());
        accountPasswordInput = new EditText(this);
        accountPasswordInput.setHint("비밀번호");
        accountPasswordInput.setSingleLine(true);
        accountPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        accountPasswordInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        styleAccountInput(accountPasswordInput);
        accountsCard.addView(accountPasswordInput, spaced(0, 9, 0, 10));
        Button addAccount = action("계정 추가 / 비밀번호 변경", true, false);
        addAccount.setOnClickListener(v -> saveAccount());
        accountsCard.addView(addAccount, matchWrap());
        TextView accountNote = text("저장된 순서대로 실행합니다. 같은 아이디를 다시 저장하면 순서는 유지되고 비밀번호만 바뀝니다.", 12, false, MUTED);
        accountsCard.addView(accountNote, spaced(0, 10, 0, 0));
        root.addView(accountsCard, spaced(0, 0, 0, 24));

        root.addView(section("시작 전 확인"), spaced(0, 0, 0, 10));
        LinearLayout permissionCard = card(Color.WHITE, 20, 18);
        accessibilityState = permissionRow(permissionCard, "접근성 권한", "접근성 설정", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        divider(permissionCard, LINE, 16);
        alarmState = permissionRow(permissionCard, "정확한 알람", "알람 설정", this::openExactAlarmSettings);
        divider(permissionCard, LINE, 16);
        Button notificationButton = action("결과 팝업 알림 설정  →", false, false);
        notificationButton.setOnClickListener(v -> {
            Intent settings = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            settings.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            settings.putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.resultChannelId());
            startActivity(settings);
        });
        permissionCard.addView(notificationButton, matchWrap());
        root.addView(permissionCard, spaced(0, 0, 0, 20));

        TextView footnote = text("휴대폰 전원이 켜져 있어야 합니다. PIN·지문·패턴 잠금은 자동으로 해제하지 않습니다.", 12, false, MUTED);
        footnote.setGravity(Gravity.CENTER);
        root.addView(footnote, matchWrap());
        return scroll;
    }

    private void showTimePicker() {
        new TimePickerDialog(this, (view, h, m) -> {
            hour = h;
            minute = m;
            prefs.edit().putInt(ScheduleManager.KEY_HOUR, h).putInt(ScheduleManager.KEY_MINUTE, m).apply();
            ScheduleManager.schedule(this);
            refresh();
            Toast.makeText(this, "예약 시간을 변경했습니다.", Toast.LENGTH_SHORT).show();
        }, hour, minute, DateFormat.is24HourFormat(this)).show();
    }

    private void styleAccountInput(EditText input) {
        input.setTextSize(15);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(shape(Color.rgb(250, 251, 250), 12, LINE, 1));
    }

    private void saveAccount() {
        String username = accountUsernameInput.getText().toString().trim();
        String password = accountPasswordInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "아이디와 비밀번호를 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            boolean added = LoginCredentials.addOrUpdate(this, username, password);
            accountUsernameInput.setText("");
            accountPasswordInput.setText("");
            Toast.makeText(this, added ? "계정을 저장했습니다." : "비밀번호를 변경했습니다.", Toast.LENGTH_SHORT).show();
            refreshAccounts();
        } catch (IllegalStateException limit) {
            Toast.makeText(this, "계정은 최대 " + LoginCredentials.MAX_ACCOUNTS + "개까지 저장할 수 있습니다.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "계정을 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAccounts() {
        accountList.removeAllViews();
        try {
            List<LoginCredentials.Account> accounts = LoginCredentials.readAccounts(this);
            accountCountText.setText("" + accounts.size() + "개 저장됨 · 목록 순서대로 로그인 및 출석");
            if (accounts.isEmpty()) {
                accountList.addView(text("저장된 계정이 없습니다.", 14, false, MUTED));
                return;
            }
            for (int i = 0; i < accounts.size(); i++) {
                final int index = i;
                LinearLayout item = row();
                TextView order = text(String.format(Locale.KOREA, "%02d", i + 1), 13, true, MUTED);
                item.addView(order, new LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT));
                TextView username = text(accounts.get(i).username, 15, true, INK);
                item.addView(username, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                Button delete = smallButton("삭제");
                delete.setOnClickListener(v -> confirmDeleteAccount(index, accounts.get(index).username));
                item.addView(delete);
                if (i > 0) divider(accountList, LINE, 10);
                accountList.addView(item, matchWrap());
            }
        } catch (Exception error) {
            accountCountText.setText("계정 정보를 읽지 못했습니다");
            accountList.addView(text("기기의 암호화 키를 확인하거나 계정을 다시 저장해주세요.", 13, false, MUTED));
        }
    }

    private void confirmDeleteAccount(int index, String username) {
        new AlertDialog.Builder(this)
                .setTitle("계정 삭제")
                .setMessage(username + " 계정을 목록에서 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    try {
                        LoginCredentials.remove(this, index);
                        refreshAccounts();
                    } catch (Exception error) {
                        Toast.makeText(this, "계정을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void enableSchedule() {
        requestNotificationPermission();
        if (!AttendanceAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "먼저 접근성 권한을 켜주세요.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            refresh();
            return;
        }
        if (!ScheduleManager.canScheduleExact(this)) {
            Toast.makeText(this, "먼저 정확한 알람 권한을 허용해주세요.", Toast.LENGTH_LONG).show();
            openExactAlarmSettings();
            refresh();
            return;
        }
        prefs.edit().putInt(ScheduleManager.KEY_HOUR, hour)
                .putInt(ScheduleManager.KEY_MINUTE, minute)
                .putBoolean(ScheduleManager.KEY_ENABLED, true).apply();
        ScheduleManager.schedule(this);
        Toast.makeText(this, "매일 자동 출석을 예약했습니다.", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void refresh() {
        boolean enabled = prefs.getBoolean(ScheduleManager.KEY_ENABLED, false);
        boolean reminderEnabled = prefs.getBoolean(ScheduleManager.KEY_REMINDER_ENABLED, false);
        updatingUi = true;
        timeText.setText(String.format(Locale.KOREA, "%02d:%02d", hour, minute));
        autoSwitch.setChecked(enabled);
        reminderSwitch.setChecked(reminderEnabled);
        privacySwitch.setChecked(prefs.getBoolean(ScheduleManager.KEY_PRIVACY_CURTAIN, true));
        updatingUi = false;

        if (enabled || reminderEnabled) {
            long at = ScheduleManager.nextTriggerMillis(hour, minute);
            nextText.setText("다음 예약 · " + new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date(at)));
        } else {
            nextText.setText("현재 예약이 꺼져 있습니다");
        }
        long lastAt = prefs.getLong("last_result_at", 0L);
        String result = prefs.getString("last_result", "아직 실행 기록이 없습니다.");
        resultText.setText(lastAt == 0L ? result
                : new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date(lastAt)) + "  ·  " + result);
        accessibilityState.setText(AttendanceAccessibilityService.isEnabled(this) ? "연결됨" : "설정 필요");
        alarmState.setText(ScheduleManager.canScheduleExact(this) ? "허용됨" : "설정 필요");
        refreshAccounts();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private TextView permissionRow(LinearLayout parent, String title, String action, Runnable onClick) {
        LinearLayout row = row();
        LinearLayout words = column();
        words.addView(text(title, 15, true, INK));
        TextView state = text("", 12, false, MUTED);
        words.addView(state, spaced(0, 3, 0, 0));
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button button = smallButton(action);
        button.setOnClickListener(v -> onClick.run());
        row.addView(button);
        parent.addView(row, matchWrap());
        return state;
    }

    private Switch toggle(String title, String description, boolean dark) {
        Switch control = new Switch(this);
        SpannableString content = new SpannableString(title + "\n" + description);
        int start = title.length() + 1;
        content.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        content.setSpan(new RelativeSizeSpan(0.82f), start, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        content.setSpan(new ForegroundColorSpan(dark ? Color.rgb(187, 190, 192) : MUTED),
                start, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        control.setText(content);
        control.setTextSize(15);
        control.setLineSpacing(dp(3), 1f);
        control.setTextColor(dark ? Color.WHITE : INK);
        control.setPadding(0, dp(5), 0, dp(5));
        control.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{dark ? Color.WHITE : INK, dark ? Color.rgb(190, 190, 190) : Color.rgb(175, 175, 175)}));
        control.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{dark ? Color.rgb(105, 105, 105) : Color.rgb(160, 160, 160), Color.rgb(216, 216, 216)}));
        return control;
    }

    private Button action(String title, boolean primary, boolean onDark) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : onDark ? Color.WHITE : INK);
        button.setBackground(shape(primary ? INK : onDark ? INK : Color.WHITE, 14,
                onDark ? Color.rgb(99, 99, 99) : primary ? INK : LINE, 1));
        button.setMinHeight(dp(50));
        button.setElevation(0);
        return button;
    }

    private Button smallButton(String title) {
        Button button = action(title, false, false);
        button.setTextSize(12);
        button.setMinHeight(dp(38));
        button.setMinimumWidth(dp(70));
        return button;
    }

    private LinearLayout card(int color, int radius, int padding) {
        LinearLayout card = column();
        card.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        card.setBackground(shape(color, radius, color == Color.WHITE ? LINE : color, 1));
        return card;
    }

    private GradientDrawable shape(int color, int radius, int border, int borderWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(borderWidth), border);
        return drawable;
    }

    private void divider(LinearLayout parent, int color, int margin) {
        View line = new View(this);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(margin), 0, dp(margin));
        parent.addView(line, params);
    }

    private TextView section(String title) {
        return text(title, 17, true, INK);
    }

    private TextView text(String content, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
