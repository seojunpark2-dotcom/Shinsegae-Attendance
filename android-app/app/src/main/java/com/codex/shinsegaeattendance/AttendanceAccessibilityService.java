package com.codex.shinsegaeattendance;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Path;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.List;

public class AttendanceAccessibilityService extends AccessibilityService {
    private static final String TARGET_PACKAGE = "com.F1.ShinSG";
    private static final long EVENT_DEBOUNCE_MS = 250L;
    private static final long STARTUP_WAIT_MS = 3000L;
    private static final long SHORT_WAIT_MS = 650L;
    private static final long PAGE_WAIT_MS = 1600L;
    private static final long RESULT_WAIT_MS = 2200L;
    private static final int STAGE_HOME = 0;
    private static final int STAGE_WALLET = 1;
    private static final int STAGE_DETAIL = 2;
    private static final int STAGE_RESULT = 3;
    private static AttendanceAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WindowManager windowManager;
    private View curtainView;
    private boolean privacyCurtainEnabled;
    private boolean screenWasInteractive;
    private String previousPackageName;
    private String lastForegroundPackageName;
    private boolean running;
    private int carouselAttempts;
    private int detailAttempts;
    private int detailLoadAttempts;
    private int foregroundLaunchAttempts;
    private int recentScanAttempts;
    private int stage;
    private long stageReadyAt;
    private long runStartedAt;

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(context, AttendanceAccessibilityService.class);
        for (String item : enabled.split(":")) {
            ComponentName actual = ComponentName.unflattenFromString(item);
            if (expected.equals(actual)) return true;
        }
        return false;
    }

    public static void requestScheduledRun(Context context) {
        if (instance == null) {
            Notifier.show(context, "자동 출석 실행 불가", "접근성 서비스가 실행 중이 아닙니다. 앱에서 권한 상태를 확인해주세요.");
            return;
        }
        instance.startAutomation(false);
    }

    public static void requestManualRun(Context context) {
        if (instance == null) {
            Notifier.show(context, "테스트 실행 불가", "먼저 접근성에서 ‘신세계 자동 출석’을 켜주세요.");
            return;
        }
        instance.startAutomation(true);
    }

    static void onSystemKeyguardDismissResult(boolean dismissed) {
        if (instance != null) instance.handleSystemKeyguardDismissResult(dismissed);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (running) {
            String reason = "접근성 서비스가 종료되어 자동 출석이 중단되었습니다. 접근성 설정을 확인해주세요.";
            getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE).edit()
                    .putString("last_result", reason)
                    .putLong("last_result_at", System.currentTimeMillis())
                    .apply();
            Notifier.show(this, "신세계 자동 출석 실패", reason);
            running = false;
        }
        if (instance == this) instance = null;
        finishWakeLock();
        removePrivacyCurtain();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (!running) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && pkg != null
                    && isRestorablePackage(pkg.toString())) {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                if (power.isInteractive()) lastForegroundPackageName = pkg.toString();
            }
            return;
        }
        if (pkg != null && !TARGET_PACKAGE.contentEquals(pkg)) return;
        handler.removeCallbacks(processRunnable);
        handler.postDelayed(processRunnable, EVENT_DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        fail("접근성 서비스가 중단되었습니다.");
    }

    private void startAutomation(boolean manual) {
        if (running) {
            long runningFor = SystemClock.uptimeMillis() - runStartedAt;
            if (!manual || runningFor < 20000L) {
                Notifier.show(this, "자동 출석", "이미 실행 중입니다.");
                return;
            }
            // A manual retry after 20 seconds replaces a run that could not
            // bring the target app to the foreground.
            running = false;
            handler.removeCallbacksAndMessages(null);
            finishWakeLock();
            removePrivacyCurtain();
        }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean swipeKeyguardLocked = keyguard.isKeyguardLocked();

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        // Remember the display state before ACQUIRE_CAUSES_WAKEUP changes it.
        screenWasInteractive = power.isInteractive();
        previousPackageName = null;
        if (screenWasInteractive) {
            AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
            if (currentRoot != null && currentRoot.getPackageName() != null) {
                String currentPackage = currentRoot.getPackageName().toString();
                if (isRestorablePackage(currentPackage)) previousPackageName = currentPackage;
            }
            if (previousPackageName == null) previousPackageName = lastForegroundPackageName;
        }
        privacyCurtainEnabled = getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE)
                .getBoolean(ScheduleManager.KEY_PRIVACY_CURTAIN, true);
        // The curtain is only needed when automation wakes a sleeping phone.
        // If the user was already using the phone, preserve the visible screen.
        if (privacyCurtainEnabled && !screenWasInteractive) showPrivacyCurtain();

        wakeLock = power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ShinsegaeAttendance:Run");
        wakeLock.acquire(3 * 60 * 1000L);
        running = true;
        runStartedAt = SystemClock.uptimeMillis();
        carouselAttempts = 0;
        detailAttempts = 0;
        detailLoadAttempts = 0;
        foregroundLaunchAttempts = 0;
        stage = STAGE_HOME;
        stageReadyAt = 0L;

        if (swipeKeyguardLocked) {
            handler.postDelayed(this::requestSystemKeyguardDismiss, 350L);
        } else {
            launchTargetApp();
        }
        handler.postDelayed(() -> {
            if (running) fail(timeoutReason());
        }, 90000);
    }

    private String timeoutReason() {
        switch (stage) {
            case STAGE_WALLET:
                return "90초 동안 리워드 캘린더 카드를 열지 못했습니다. 앱 화면이나 로딩 상태를 확인해주세요.";
            case STAGE_DETAIL:
                return "90초 동안 리워드 캘린더 상세 화면에서 참여 버튼을 찾지 못했습니다.";
            case STAGE_RESULT:
                return "참여 버튼을 누른 뒤 90초 안에 완료 표시를 확인하지 못했습니다. 신세계 앱에서 실제 출석 여부를 확인해주세요.";
            default:
                return "90초 동안 신세계 앱 첫 화면에서 출석 절차를 시작하지 못했습니다. 앱 화면이나 로그인 상태를 확인해주세요.";
        }
    }

    private void requestSystemKeyguardDismiss() {
        if (!running) return;
        Intent dismiss = new Intent(this, UnlockActivity.class);
        dismiss.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(dismiss);
        } catch (RuntimeException error) {
            handleSystemKeyguardDismissResult(false);
        }
    }

    private void handleSystemKeyguardDismissResult(boolean dismissed) {
        if (!running) return;
        if (dismissed) {
            handler.postDelayed(this::launchTargetApp, 500L);
            return;
        }

        // Fallback for vendor lock screens: use a long diagonal drag rather
        // than the vertical gesture that some Samsung builds ignore.
        handler.postDelayed(
                () -> swipe(0.18f, 0.90f, 0.82f, 0.08f, 950L),
                350L
        );
        handler.postDelayed(this::launchTargetApp, 1800L);
        handler.postDelayed(this::verifyKeyguardAfterLaunch, 6000L);
    }

    private void verifyKeyguardAfterLaunch() {
        if (!running) return;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!keyguard.isKeyguardLocked()) return;

        // Some devices report a stale locked state briefly. If Shinsegae is
        // already the active window, automation can safely continue.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence activePackage = root == null ? null : root.getPackageName();
        if (activePackage == null || !TARGET_PACKAGE.contentEquals(activePackage)) {
            fail("시스템 잠금 해제 요청과 대각선 드래그 후에도 잠금화면이 유지되고 있습니다.");
        }
    }

    private void launchTargetApp() {
        if (!running) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        if (launch == null) {
            fail("신세계백화점 앱을 찾을 수 없습니다.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(launch);
            handler.postDelayed(processRunnable, STARTUP_WAIT_MS);
        } catch (RuntimeException error) {
            fail("신세계백화점 앱을 실행하지 못했습니다.");
        }
    }

    private final Runnable processRunnable = this::processScreen;

    private void processScreen() {
        if (!running) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !TARGET_PACKAGE.contentEquals(root.getPackageName())) {
            foregroundLaunchAttempts++;
            if (foregroundLaunchAttempts == 4 || foregroundLaunchAttempts == 9) {
                launchTargetApp();
                return;
            }
            if (foregroundLaunchAttempts > 14) {
                fail("신세계 앱을 화면에 표시하지 못했습니다. 잠금화면이나 배터리 설정을 확인해주세요.");
                return;
            }
            handler.postDelayed(processRunnable, SHORT_WAIT_MS);
            return;
        }
        foregroundLaunchAttempts = 0;

        long waitForStage = stageReadyAt - SystemClock.uptimeMillis();
        if (waitForStage > 0) {
            handler.postDelayed(processRunnable, waitForStage);
            return;
        }

        if (contains(root, "로그인이 필요한 서비스") || contains(root, "로그인 하시겠습니까")) {
            fail("신세계 앱 로그인이 필요합니다. 직접 로그인한 뒤 다시 실행해주세요.");
            return;
        }
        if (contains(root, "오늘 참여 완료") || contains(root, "R 당첨")) {
            succeed("오늘 출석이 완료되었습니다.");
            return;
        }
        if (contains(root, "나의 출석 현황") && contains(root, "누적 출석")) {
            succeed("출석 캘린더가 확인되었습니다. 오늘 출석이 완료되었거나 이미 처리된 상태입니다.");
            return;
        }

        AccessibilityNodeInfo close = findVisibleExact(root, "닫기");
        if (close != null) {
            clickNodeOrCenter(close);
            handler.postDelayed(processRunnable, SHORT_WAIT_MS);
            return;
        }

        // The same calendar title is shown both on the wallet card and as the
        // detail-page header. Once the card is opened, never click that title
        // again; immediately scroll the detail page toward its apply button.
        if (stage == STAGE_DETAIL
                && !contains(root, "이벤트 기간")
                && (contains(root, "내 리워드") || contains(root, "신백리워드"))) {
            if (detailLoadAttempts++ < 10) {
                handler.postDelayed(processRunnable, 400L);
            } else {
                fail("리워드 캘린더 상세 페이지가 열리지 않았습니다.");
            }
            return;
        }
        if (stage == STAGE_DETAIL || contains(root, "이벤트 기간")) {
            stage = STAGE_DETAIL;
            AccessibilityNodeInfo cta = findVisibleAnyContains(
                    root,
                    "리워드 받으러 가기",
                    "응모하러가기",
                    "응모하러 가기",
                    "출석하기",
                    "참여하기"
            );
            if (cta != null) {
                stage = STAGE_RESULT;
                clickNodeOrCenter(cta);
                handler.postDelayed(processRunnable, RESULT_WAIT_MS);
            } else if (detailAttempts == 0) {
                // The CTA sits just below the first viewport. Move the page
                // only once and only a short distance so we never pass it.
                detailAttempts++;
                swipe(0.50f, 0.66f, 0.50f, 0.51f, 280);
                handler.postDelayed(processRunnable, 700L);
            } else if (detailAttempts++ < 8) {
                // Do not scroll again. Give the WebView a moment to expose the
                // button after the single short movement.
                handler.postDelayed(processRunnable, 400L);
            } else {
                fail("짧은 스크롤 후 ‘리워드 받으러 가기’ 버튼을 찾지 못했습니다.");
            }
            return;
        }

        if (stage == STAGE_WALLET || contains(root, "내 리워드") || contains(root, "신백리워드")) {
            stage = STAGE_WALLET;
            AccessibilityNodeInfo calendar = findVisibleContains(root, "신세계 리워드 캘린더");
            if (calendar != null) {
                stage = STAGE_DETAIL;
                stageReadyAt = SystemClock.uptimeMillis() + 900L;
                detailAttempts = 0;
                detailLoadAttempts = 0;
                clickNodeOrCenter(calendar);
                handler.postDelayed(processRunnable, 900L);
                return;
            }
            if (carouselAttempts++ < 5) {
                swipe(0.78f, 0.68f, 0.20f, 0.68f, 450);
                handler.postDelayed(processRunnable, SHORT_WAIT_MS);
            } else {
                fail("리워드 캘린더 카드를 찾지 못했습니다. 앱 화면이 변경되었을 수 있습니다.");
            }
            return;
        }

        if (contains(root, "MY SHINSEGAE") || contains(root, "온라인쇼핑")) {
            // Current app build exposes the R tab as an unlabeled second item.
            stage = STAGE_WALLET;
            stageReadyAt = SystemClock.uptimeMillis() + 900L;
            tap(0.271f, 0.892f);
            handler.postDelayed(processRunnable, 900L);
            return;
        }

        handler.postDelayed(processRunnable, SHORT_WAIT_MS);
    }

    private boolean contains(AccessibilityNodeInfo root, String text) {
        return find(root, text, false, false) != null;
    }

    private AccessibilityNodeInfo findVisibleExact(AccessibilityNodeInfo root, String text) {
        return find(root, text, true, true);
    }

    private AccessibilityNodeInfo findVisibleContains(AccessibilityNodeInfo root, String text) {
        return find(root, text, false, true);
    }

    private AccessibilityNodeInfo findVisibleAnyContains(AccessibilityNodeInfo root, String... texts) {
        for (String text : texts) {
            AccessibilityNodeInfo node = findVisibleContains(root, text);
            if (node != null) return node;
        }
        return null;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String text, boolean exact, boolean visibleOnly) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        Rect bounds = new Rect();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence value = node.getText();
            if (TextUtils.isEmpty(value)) value = node.getContentDescription();
            boolean match = value != null && (exact
                    ? text.equals(value.toString().trim())
                    : value.toString().contains(text));
            if (match) {
                node.getBoundsInScreen(bounds);
                if (!visibleOnly || (node.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0)) return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private void clickNodeOrCenter(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            current = current.getParent();
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        tapPixels(r.centerX(), r.centerY());
    }

    private void tap(float xRatio, float yRatio) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        tapPixels(Math.round(w * xRatio), Math.round(h * yRatio));
    }

    private void tapPixels(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void swipe(float sx, float sy, float ex, float ey, long duration) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(w * sx, h * sy);
        path.lineTo(w * ex, h * ey);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, duration);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void succeed(String message) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(processRunnable);
        getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE).edit()
                .putString("last_result", message)
                .putLong("last_result_at", System.currentTimeMillis())
                .apply();
        Notifier.show(this, "신세계 출석 완료", message);
        finishVisualRun();
    }

    private void fail(String message) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(processRunnable);
        getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE).edit()
                .putString("last_result", message)
                .putLong("last_result_at", System.currentTimeMillis())
                .apply();
        Notifier.show(this, "신세계 자동 출석 실패", message);
        finishVisualRun();
    }

    private void showPrivacyCurtain() {
        if (curtainView != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        curtainView = new View(this);
        curtainView.setBackgroundColor(Color.BLACK);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.screenBrightness = 0.01f;
        try {
            windowManager.addView(curtainView, params);
        } catch (RuntimeException error) {
            curtainView = null;
            windowManager = null;
        }
    }

    private void removePrivacyCurtain() {
        if (windowManager != null && curtainView != null) {
            try {
                windowManager.removeView(curtainView);
            } catch (RuntimeException ignored) {
            }
        }
        curtainView = null;
        windowManager = null;
    }

    private void finishVisualRun() {
        // Shinsegae is the current task. Open Recents and dismiss its card
        // before restoring the app/display state that existed before the run.
        recentScanAttempts = 0;
        performGlobalAction(GLOBAL_ACTION_RECENTS);
        handler.postDelayed(this::findAndDismissShinsegaeRecentTask, 900L);
    }

    private void findAndDismissShinsegaeRecentTask() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // The Samsung Recents title is exactly "신세계". Substring matching
        // could select this app's own "신세계 자동 출석" task.
        AccessibilityNodeInfo card = root == null ? null : findVisibleExact(root, "신세계");
        if (card == null) {
            if (recentScanAttempts < 6) {
                // Move through the carousel, but never dismiss an unidentified
                // card. Scan two positions on one side, then four positions
                // across the starting point to cover the other side.
                int scanStep = recentScanAttempts++;
                if (scanStep < 2) {
                    swipe(0.28f, 0.58f, 0.78f, 0.58f, 420L);
                } else {
                    swipe(0.72f, 0.58f, 0.22f, 0.58f, 420L);
                }
                handler.postDelayed(this::findAndDismissShinsegaeRecentTask, 650L);
            } else {
                // Safety first: if the Shinsegae label cannot be identified,
                // leave every recent app untouched.
                handler.postDelayed(this::restorePreviousVisualState, 300L);
            }
            return;
        }

        AccessibilityNodeInfo current = card;
        Rect candidateBounds = new Rect();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        while (current != null) {
            current.getBoundsInScreen(candidateBounds);
            boolean cardSized = candidateBounds.width() > screenWidth * 0.20f
                    && candidateBounds.width() < screenWidth * 0.90f
                    && candidateBounds.height() > screenHeight * 0.20f
                    && candidateBounds.height() < screenHeight * 0.90f;
            if (cardSized
                    && (current.getActions() & AccessibilityNodeInfo.ACTION_DISMISS) != 0
                    && current.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                handler.postDelayed(this::restorePreviousVisualState, 700L);
                return;
            }
            current = current.getParent();
        }

        // Use only the exact title's horizontal center, never an estimated
        // parent container that may span a neighboring app card.
        Rect targetBounds = new Rect();
        card.getBoundsInScreen(targetBounds);
        float x = Math.max(0.08f, Math.min(0.92f,
                targetBounds.centerX() / (float) screenWidth));
        swipe(x, 0.65f, x, 0.06f, 560L);
        handler.postDelayed(this::restorePreviousVisualState, 800L);
    }

    private void restorePreviousVisualState() {
        // If the screen was already on, bring the app that was in front back
        // to the foreground; otherwise go Home before restoring screen-off.
        boolean restoredPreviousApp = false;
        if (screenWasInteractive
                && previousPackageName != null
                && !TARGET_PACKAGE.equals(previousPackageName)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    // Unlike getLaunchIntentForPackage, this API is not
                    // restricted by Android's package-visibility filtering.
                    IntentSender sender = getPackageManager()
                            .getLaunchIntentSenderForPackage(previousPackageName);
                    sender.sendIntent(this, 0, null, null, null);
                    restoredPreviousApp = true;
                } catch (IntentSender.SendIntentException | RuntimeException ignored) {
                }
            } else {
                Intent previous = getPackageManager().getLaunchIntentForPackage(previousPackageName);
                if (previous != null) {
                    previous.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(previous);
                        restoredPreviousApp = true;
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        if (!restoredPreviousApp) performGlobalAction(GLOBAL_ACTION_HOME);

        handler.postDelayed(() -> {
            finishWakeLock();
            if (!screenWasInteractive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Keep the curtain until Android has restored the original off state.
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                handler.postDelayed(this::removePrivacyCurtain, 900L);
            } else {
                removePrivacyCurtain();
            }
        }, 600L);
    }

    private boolean isRestorablePackage(String packageName) {
        if (TextUtils.isEmpty(packageName)
                || TARGET_PACKAGE.equals(packageName)
                || getPackageName().equals(packageName)
                || "com.android.systemui".equals(packageName)) {
            return false;
        }
        // Do not query PackageManager here: Android 11+ can hide otherwise
        // valid launcher apps from that query. Restore will validate/launch it.
        return true;
    }

    private void finishWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
