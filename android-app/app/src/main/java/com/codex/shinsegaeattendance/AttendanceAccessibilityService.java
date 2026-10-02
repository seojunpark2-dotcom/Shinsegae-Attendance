package com.codex.shinsegaeattendance;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
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
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class AttendanceAccessibilityService extends AccessibilityService {
    private static final String TARGET_PACKAGE = "com.F1.ShinSG";
    private static final long EVENT_DEBOUNCE_MS = 250L;
    private static final long STARTUP_WAIT_MS = 500L;
    private static final long FOREGROUND_TIMEOUT_MS = 12000L;
    private static final long SHORT_WAIT_MS = 650L;
    private static final long PAGE_WAIT_MS = 1600L;
    private static final long RESULT_WAIT_MS = 2200L;
    private static final int STAGE_HOME = 0;
    private static final int STAGE_WALLET = 1;
    private static final int STAGE_DETAIL = 2;
    private static final int STAGE_RESULT = 3;
    private static final int STAGE_LOGIN = 4;
    private static final int STAGE_LOGOUT = 5;
    private static final int STAGE_OPEN_LOGIN = 6;
    private static final long ACCOUNT_TIMEOUT_MS = 120000L;
    private static final long HUMAN_VERIFICATION_TIMEOUT_MS = 10 * 60 * 1000L;
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
    private boolean targetLaunchRequested;
    private long targetLaunchedAt;
    private long foregroundMissingSince;
    private boolean eventProcessScheduled;
    private int recentScanAttempts;
    private int stage;
    private int loginWaitAttempts;
    private boolean loginPromptClicked;
    private boolean loginFieldsFilled;
    private boolean loginSubmitted;
    private boolean awaitingHumanVerification;
    private long humanVerificationStartedAt;
    private boolean verificationCurtainSuspended;
    private boolean captchaResubmitted;
    private boolean verificationVibrated;
    private boolean humanVerificationComplete;
    private boolean loginAttemptedThisRun;
    private boolean loginKeyboardDismissed;
    private int loginPasswordCenterY;
    private int loginAutoToggleAttempts;
    private int loginAutoControlWaits;
    private int loginOpenAttempts;
    private int loginBackAttempts;
    private List<LoginCredentials.Account> runAccounts = new ArrayList<>();
    private final List<String> accountResults = new ArrayList<>();
    private int accountIndex;
    private int accountSuccessCount;
    private long accountStartedAt;
    private int logoutAttempts;
    private int logoutBackAttempts;
    private int logoutStep;
    private int logoutScrolls;
    private boolean adOptOutTapped;
    private int adToggleAttempts;
    private int adCloseAttempts;
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
        runAccounts = new ArrayList<>();
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
        if (!targetLaunchRequested || eventProcessScheduled) return;
        eventProcessScheduled = true;
        handler.postDelayed(eventProcessRunnable, EVENT_DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        fail("접근성 서비스가 중단되었습니다.");
    }

    private void startAutomation(boolean manual) {
        if (running) {
            if (awaitingHumanVerification) {
                Notifier.vibrateVerification(this);
                return;
            }
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
        try {
            runAccounts = LoginCredentials.readAccounts(this);
        } catch (Exception error) {
            Notifier.show(this, "자동 출석 실행 불가", "저장된 계정 정보를 읽지 못했습니다. 앱에서 계정 목록을 확인해주세요.");
            return;
        }
        accountIndex = 0;
        accountSuccessCount = 0;
        accountResults.clear();
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
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(Math.max(3, runAccounts.size() * 3) * 60 * 1000L);
        running = true;
        runStartedAt = SystemClock.uptimeMillis();
        accountStartedAt = runStartedAt;
        carouselAttempts = 0;
        detailAttempts = 0;
        detailLoadAttempts = 0;
        targetLaunchRequested = false;
        targetLaunchedAt = 0L;
        foregroundMissingSince = 0L;
        eventProcessScheduled = false;
        stage = runAccounts.isEmpty() ? STAGE_HOME : STAGE_OPEN_LOGIN;
        logoutAttempts = 0;
        logoutBackAttempts = 0;
        logoutStep = 0;
        logoutScrolls = 0;
        adOptOutTapped = false;
        adToggleAttempts = 0;
        adCloseAttempts = 0;
        loginWaitAttempts = 0;
        loginPromptClicked = false;
        loginFieldsFilled = false;
        loginSubmitted = false;
        awaitingHumanVerification = false;
        humanVerificationStartedAt = 0L;
        verificationCurtainSuspended = false;
        captchaResubmitted = false;
        verificationVibrated = false;
        humanVerificationComplete = false;
        loginAttemptedThisRun = false;
        loginKeyboardDismissed = false;
        loginPasswordCenterY = -1;
        loginAutoToggleAttempts = 0;
        loginAutoControlWaits = 0;
        loginOpenAttempts = 0;
        loginBackAttempts = 0;
        stageReadyAt = 0L;

        if (swipeKeyguardLocked) {
            handler.postDelayed(this::requestSystemKeyguardDismiss, 350L);
        } else {
            launchTargetApp();
        }
        handler.postDelayed(timeoutRunnable, 1000L);
    }

    private final Runnable timeoutRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (awaitingHumanVerification) {
                if (SystemClock.uptimeMillis() - humanVerificationStartedAt >= HUMAN_VERIFICATION_TIMEOUT_MS) {
                    fail("직접 인증을 기다린 지 10분이 지나 대기를 종료했습니다. 신세계 앱에서 인증 상태를 확인해주세요.");
                }
                if (running) handler.postDelayed(this, 1000L);
                return;
            }
            if (SystemClock.uptimeMillis() - accountStartedAt >= ACCOUNT_TIMEOUT_MS) {
                fail(timeoutReason());
            }
            if (running) handler.postDelayed(this, 1000L);
        }
    };

    private String timeoutReason() {
        switch (stage) {
            case STAGE_LOGOUT:
                return "계정 전환을 위해 신세계 앱의 설정 또는 로그아웃 버튼을 찾지 못했습니다.";
            case STAGE_LOGIN:
                return "2분 동안 신세계 앱 로그인을 완료하지 못했습니다. 로그인 화면 또는 추가 인증을 확인해주세요.";
            case STAGE_OPEN_LOGIN:
                return "2분 동안 메인 화면의 사람 아이콘에서 로그인 화면을 열지 못했습니다.";
            case STAGE_WALLET:
                return "2분 동안 리워드 캘린더 카드를 열지 못했습니다. 앱 화면이나 로딩 상태를 확인해주세요.";
            case STAGE_DETAIL:
                return "2분 동안 리워드 캘린더 상세 화면에서 참여 버튼을 찾지 못했습니다.";
            case STAGE_RESULT:
                return "참여 버튼을 누른 뒤 2분 안에 완료 표시를 확인하지 못했습니다. 신세계 앱에서 실제 출석 여부를 확인해주세요.";
            default:
                return "2분 동안 신세계 앱 첫 화면에서 출석 절차를 시작하지 못했습니다. 앱 화면이나 로그인 상태를 확인해주세요.";
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
            // Let UnlockActivity finish before bringing the target task forward.
            handler.post(this::launchTargetApp);
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
        if (!running || targetLaunchRequested) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        if (launch == null) {
            fail("신세계백화점 앱을 찾을 수 없습니다.");
            return;
        }
        // Bring the existing task forward once, without clearing/restarting
        // its activity stack each time the accessibility root is loading.
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            targetLaunchRequested = true;
            targetLaunchedAt = SystemClock.uptimeMillis();
            startActivity(launch);
            handler.removeCallbacks(processRunnable);
            scheduleProcess(STARTUP_WAIT_MS);
        } catch (RuntimeException error) {
            fail("신세계백화점 앱을 실행하지 못했습니다.");
        }
    }

    private final Runnable processRunnable = this::processScreen;
    private final Runnable eventProcessRunnable = this::processScreen;

    private void scheduleProcess(long delayMillis) {
        stageReadyAt = SystemClock.uptimeMillis() + delayMillis;
        handler.removeCallbacks(processRunnable);
        handler.postDelayed(processRunnable, delayMillis);
    }

    private void processScreen() {
        handler.removeCallbacks(processRunnable);
        handler.removeCallbacks(eventProcessRunnable);
        eventProcessScheduled = false;
        if (!running || !targetLaunchRequested) return;
        if (stage == STAGE_LOGIN && loginFieldsFilled
                && !loginKeyboardDismissed && isInputMethodVisible()) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            loginKeyboardDismissed = true;
            scheduleProcess(500L);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !TARGET_PACKAGE.contentEquals(root.getPackageName())) {
            if (awaitingHumanVerification) {
                // Let the user switch to a notification or authentication UI
                // without relaunching the app or triggering foreground failure.
                scheduleProcess(1000L);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (foregroundMissingSince == 0L) foregroundMissingSince = now;
            if (now - foregroundMissingSince >= FOREGROUND_TIMEOUT_MS) {
                fail("신세계 앱을 화면에 표시하지 못했습니다. 잠금화면이나 배터리 설정을 확인해주세요.");
                return;
            }
            scheduleProcess(SHORT_WAIT_MS);
            return;
        }
        foregroundMissingSince = 0L;

        // Observe the CAPTCHA entry immediately, even during the wait after
        // clicking Login. Do not wait for an image challenge to appear.
        if (stage == STAGE_LOGIN && loginSubmitted) {
            AccessibilityNodeInfo robot = findRobotControl(root);
            if (robot != null && !captchaResubmitted) {
                beginHumanVerification();
                if (!verificationVibrated) {
                    Notifier.vibrateVerification(this);
                    verificationVibrated = true;
                }
                if (isHumanVerificationComplete(root, robot, findCheckboxForLabel(root, robot))) {
                    humanVerificationComplete = true;
                }
            }
        }

        long waitForStage = stageReadyAt - SystemClock.uptimeMillis();
        if (waitForStage > 0) {
            scheduleProcess(waitForStage);
            return;
        }

        if (awaitingHumanVerification) {
            handleLogin(root);
            return;
        }
        // Handle the launch advertisement before login/account navigation so
        // its overlay cannot redirect taps intended for the home screen.
        if (handleAdvertisement(root)) return;
        if (stage == STAGE_LOGOUT) {
            handleLogout(root);
            return;
        }
        if (handleLogin(root)) return;
        if (stage == STAGE_OPEN_LOGIN) {
            handleOpenLogin(root);
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
            scheduleProcess(SHORT_WAIT_MS);
            return;
        }

        // The same calendar title is shown both on the wallet card and as the
        // detail-page header. Once the card is opened, never click that title
        // again; immediately scroll the detail page toward its apply button.
        if (stage == STAGE_DETAIL
                && !contains(root, "이벤트 기간")
                && (contains(root, "내 리워드") || contains(root, "신백리워드"))) {
            if (detailLoadAttempts++ < 10) {
                scheduleProcess(400L);
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
                scheduleProcess(RESULT_WAIT_MS);
            } else if (detailAttempts == 0) {
                // The CTA sits just below the first viewport. Move the page
                // only once and only a short distance so we never pass it.
                detailAttempts++;
                swipe(0.50f, 0.66f, 0.50f, 0.51f, 280);
                scheduleProcess(700L);
            } else if (detailAttempts++ < 8) {
                // Do not scroll again. Give the WebView a moment to expose the
                // button after the single short movement.
                scheduleProcess(400L);
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
                scheduleProcess(900L);
                return;
            }
            if (carouselAttempts++ < 5) {
                swipe(0.78f, 0.68f, 0.20f, 0.68f, 450);
                scheduleProcess(SHORT_WAIT_MS);
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
            scheduleProcess(900L);
            return;
        }

        scheduleProcess(SHORT_WAIT_MS);
    }

    private boolean handleAdvertisement(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo label = findVisibleAnyContains(root,
                "오늘은 그만보기", "오늘은 그만 보기", "오늘 그만보기",
                "오늘 하루 보지 않기", "오늘 하루 보지않기");
        if (label == null) {
            adOptOutTapped = false;
            adToggleAttempts = 0;
            adCloseAttempts = 0;
            return false;
        }
        AccessibilityNodeInfo checkbox = findCheckboxForLabel(root, label);
        if (checkbox != null && !checkbox.isChecked()) {
            if (++adToggleAttempts > 3) {
                fail("광고의 ‘오늘은 그만보기’ 체크박스를 활성화하지 못했습니다.");
                return true;
            }
            clickNodeOrCenter(checkbox);
            adOptOutTapped = true;
            scheduleProcess(400L);
            return true;
        }
        if (checkbox == null && !adOptOutTapped && !label.isSelected()) {
            // Some WebViews expose only the checkbox label. Tap it once;
            // never toggle it repeatedly when checked state is unavailable.
            clickNodeOrCenter(label);
            adOptOutTapped = true;
            scheduleProcess(400L);
            return true;
        }
        AccessibilityNodeInfo close = findVisibleExact(root, "닫기");
        if (close == null) close = findVisibleContains(root, "닫기");
        if (++adCloseAttempts > 5) {
            fail("광고의 ‘닫기’ 버튼을 찾거나 광고창을 닫지 못했습니다.");
            return true;
        }
        if (close != null) clickNodeOrCenter(close);
        scheduleProcess(500L);
        return true;
    }

    private void handleLogout(AccessibilityNodeInfo root) {
        // The user-provided screens show: calendar bottom-right menu -> all menu (scroll
        // to bottom) -> 설정 -> 환경설정 -> 로그아웃, then immediately begin
        // the next login flow without a separate logout verification pass.
        boolean loginForm = findLoginFields(root) != null;
        boolean loginPrompt = findVisibleContains(root, "로그인이 필요한 서비스") != null;
        boolean loginVisible = findVisibleExact(root, "로그인") != null;
        if (loginForm || loginPrompt || (loginVisible && findVisibleExact(root, "로그아웃") == null
                && findVisibleExact(root, "환경설정") != null)) {
            beginLoginForCurrentAccount();
            return;
        }

        boolean settingsPage = findVisibleExact(root, "환경설정") != null
                && findVisibleExact(root, "로그아웃") != null;
        boolean menuPage = findVisibleContains(root, "검색어를 입력하세요") != null
                || findVisibleExact(root, "나의 선택 점포") != null;
        boolean mainPage = contains(root, "MY SHINSEGAE")
                || (contains(root, "온라인쇼핑") && contains(root, "백화점"));
        if (logoutStep == 6 && !settingsPage && !menuPage) {
            openCalendarMenu(root);
            logoutStep = 2;
            scheduleProcess(450L);
            return;
        }
        if (settingsPage) {
            logoutStep = 4;
        } else if (logoutStep == 6 && menuPage) {
            logoutStep = 2;
        } else if (logoutStep == 0) {
            if (menuPage) logoutStep = 2;
            else if (mainPage) logoutStep = 1;
        }

        if (logoutStep == 4) {
            // The Shinsegae app's own auto-login would silently sign back in
            // to the old account. Turn it off only when its switch state can
            // be confirmed by the accessibility tree.
            AccessibilityNodeInfo autoLoginLabel = findVisibleExact(root, "자동 로그인 사용");
            AccessibilityNodeInfo autoLoginSwitch = autoLoginLabel == null
                    ? null : findCheckableBeside(root, autoLoginLabel);
            if (autoLoginSwitch == null) {
                fail("신세계 앱의 '자동 로그인 사용' 상태를 확인하지 못했습니다. 직접 끈 뒤 다시 실행해주세요.");
                return;
            }
            if (autoLoginSwitch.isChecked()) {
                clickNodeOrCenter(autoLoginSwitch);
                scheduleProcess(500L);
                return;
            }
            AccessibilityNodeInfo logout = findVisibleExact(root, "로그아웃");
            if (logout == null) {
                if (++logoutAttempts > 10) fail("환경설정에서 로그아웃 버튼을 찾지 못했습니다.");
                else scheduleProcess(500L);
                return;
            }
            clickNodeOrCenter(logout);
            beginLoginForCurrentAccount();
            return;
        }

        if (logoutStep == 2) {
            if (!menuPage) {
                if (++logoutAttempts > 8) fail("전체 메뉴 화면이 열리지 않았습니다.");
                else scheduleProcess(500L);
                return;
            }
            logoutAttempts = 0;
            AccessibilityNodeInfo settings = findMenuSettings(root);
            if (settings != null) {
                clickNodeOrCenter(settings);
                logoutStep = 3;
                logoutAttempts = 0;
                scheduleProcess(450L);
                return;
            }
            if (logoutScrolls++ >= 12) {
                fail("전체 메뉴를 내려도 맨 아래의 설정 버튼을 찾지 못했습니다.");
                return;
            }
            scrollMenuToBottom(root);
            scheduleProcess(850L);
            return;
        }

        if (logoutStep == 1) {
            AccessibilityNodeInfo menu = findVisibleAnyContains(root, "전체메뉴", "전체 메뉴");
            if (menu != null) clickNodeOrCenter(menu);
            else tap(0.08f, 0.083f); // Unlabeled hamburger in the provided main-screen image.
            logoutStep = 2;
            logoutScrolls = 0;
            scheduleProcess(850L);
            return;
        }

        if (logoutStep == 3) {
            if (++logoutAttempts > 10) {
                fail("설정 버튼을 누른 뒤 환경설정 화면이 열리지 않았습니다.");
            } else {
                scheduleProcess(500L);
            }
            return;
        }

        // A cold launch may still show a splash screen. Do not back out of
        // the app while its initial accessibility content is loading.
        if (SystemClock.uptimeMillis() - targetLaunchedAt < 4000L) {
            scheduleProcess(400L);
        } else if (logoutBackAttempts++ < 3) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            scheduleProcess(700L);
        } else {
            fail("신세계 앱의 메인 화면으로 돌아가지 못해 계정 전환을 중단했습니다.");
        }
    }

    private AccessibilityNodeInfo findMenuSettings(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence value = node.getText();
            if (TextUtils.isEmpty(value)) value = node.getContentDescription();
            if (node.isVisibleToUser() && value != null
                    && value.toString().trim().startsWith("설정")) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && bounds.centerX() < width * 0.40f
                        && bounds.centerY() > height * 0.24f
                        && bounds.centerY() < height * 0.94f) return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private void scrollMenuToBottom(AccessibilityNodeInfo root) {
        // Recycler/list controls can jump directly to the final item. A
        // WebView usually lacks this action, so use a fast long fling there.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo scroll = null;
        int largestArea = 0;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int area = bounds.width() * bounds.height();
            if (node.isVisibleToUser() && node.isScrollable() && area > largestArea) {
                scroll = node;
                largestArea = area;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        if (scroll != null) {
            AccessibilityNodeInfo.CollectionInfo collection = scroll.getCollectionInfo();
            if (logoutScrolls == 1 && collection != null && collection.getRowCount() > 1
                    && scroll.getActionList().contains(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION)) {
                Bundle position = new Bundle();
                position.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT,
                        collection.getRowCount() - 1);
                position.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
                if (scroll.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId(), position)) return;
            }
            // Alternate native scrolling with gestures: some WebViews report
            // success for an action without actually moving their content.
            if (logoutScrolls % 2 == 0
                    && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return;
        }
        // Start above the persistent bottom navigation bar, not on its icons.
        // Keep both ends inside the scrollable content below the fixed header.
        swipe(0.50f, 0.82f, 0.50f, 0.30f, 240L);
    }

    private void openCalendarMenu(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo menu = findVisibleAnyContains(root, "전체메뉴", "전체 메뉴");
        if (menu == null) menu = findVisibleExact(root, "메뉴");
        if (menu != null) {
            Rect bounds = new Rect();
            menu.getBoundsInScreen(bounds);
            if (bounds.centerX() > getResources().getDisplayMetrics().widthPixels * 0.75f
                    && bounds.centerY() > getResources().getDisplayMetrics().heightPixels * 0.80f) {
                clickNodeOrCenter(menu);
                return;
            }
        }
        // Persistent bottom-right hamburger shown in the provided app screens.
        tap(0.93f, 0.918f);
    }

    private AccessibilityNodeInfo findCheckableBeside(AccessibilityNodeInfo root, AccessibilityNodeInfo label) {
        Rect labelBounds = new Rect();
        label.getBoundsInScreen(labelBounds);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo best = null;
        int bestDistance = Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser() && node.isCheckable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int distance = Math.abs(bounds.centerY() - labelBounds.centerY());
                if (bounds.centerX() > labelBounds.centerX()
                        && distance < getResources().getDisplayMetrics().heightPixels * 0.055f
                        && distance < bestDistance) {
                    best = node;
                    bestDistance = distance;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return best;
    }

    private void beginLoginForCurrentAccount() {
        endHumanVerification();
        captchaResubmitted = false;
        verificationVibrated = false;
        humanVerificationComplete = false;
        stage = STAGE_OPEN_LOGIN;
        carouselAttempts = 0;
        detailAttempts = 0;
        detailLoadAttempts = 0;
        foregroundMissingSince = 0L;
        loginWaitAttempts = 0;
        loginPromptClicked = false;
        loginFieldsFilled = false;
        loginSubmitted = false;
        loginAttemptedThisRun = false;
        loginKeyboardDismissed = false;
        loginPasswordCenterY = -1;
        loginAutoToggleAttempts = 0;
        loginAutoControlWaits = 0;
        loginOpenAttempts = 0;
        loginBackAttempts = 0;
        stageReadyAt = SystemClock.uptimeMillis() + 650L;
        accountStartedAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(processRunnable);
        scheduleProcess(650L);
    }

    private void handleOpenLogin(AccessibilityNodeInfo root) {
        // Start directly at the profile login entry. Only an actually logged-in
        // My page (for example from yesterday's last account) needs logout.
        if (accountIndex == 0 && !loginAttemptedThisRun
                && contains(root, "나의 신세계") && contains(root, "안녕하세요")) {
            beginLogoutForNextAccount();
            return;
        }
        // Some app builds show a logout confirmation dialog. Complete the
        // required action, then continue directly to the profile/login entry.
        if (findVisibleContains(root, "로그아웃") != null) {
            AccessibilityNodeInfo confirm = findVisibleExact(root, "예");
            if (confirm == null) confirm = findVisibleExact(root, "확인");
            if (confirm != null) {
                clickNodeOrCenter(confirm);
                stageReadyAt = SystemClock.uptimeMillis() + 650L;
                scheduleProcess(650L);
                return;
            }
        }
        boolean mainPage = contains(root, "MY SHINSEGAE")
                || (contains(root, "온라인쇼핑") && contains(root, "백화점"));
        if (mainPage) {
            if (++loginOpenAttempts > 5) {
                fail("메인 화면의 사람 아이콘을 눌러도 로그인 요청이 나타나지 않았습니다. 다른 계정의 출석은 진행하지 않았습니다.");
                return;
            }
            tap(0.875f, 0.892f);
            scheduleProcess(850L);
            return;
        }
        if (findVisibleExact(root, "환경설정") != null) {
            AccessibilityNodeInfo home = findVisibleExact(root, "홈");
            if (home != null) clickNodeOrCenter(home);
            else tap(0.63f, 0.918f);
            scheduleProcess(850L);
            return;
        }
        if (SystemClock.uptimeMillis() - targetLaunchedAt < 4000L) {
            scheduleProcess(400L);
        } else if (loginBackAttempts++ < 3) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            scheduleProcess(700L);
        } else {
            fail("신세계 앱 메인 화면으로 돌아가 사람 아이콘에서 로그인을 시작하지 못했습니다.");
        }
    }

    private void beginLogoutForNextAccount() {
        beginLogoutForNextAccount(false);
    }

    private void beginLogoutForNextAccount(boolean fromCalendar) {
        endHumanVerification();
        captchaResubmitted = false;
        verificationVibrated = false;
        humanVerificationComplete = false;
        stage = STAGE_LOGOUT;
        logoutStep = fromCalendar ? 6 : 0;
        logoutAttempts = 0;
        logoutBackAttempts = 0;
        logoutScrolls = 0;
        stageReadyAt = SystemClock.uptimeMillis() + 500L;
        accountStartedAt = SystemClock.uptimeMillis();
        scheduleProcess(fromCalendar ? 0L : 500L);
    }

    private boolean handleLogin(AccessibilityNodeInfo root) {
        boolean prompt = findVisibleContains(root, "로그인이 필요한 서비스") != null
                || findVisibleContains(root, "로그인 하시겠습니까") != null;
        LoginFields fields = findLoginFields(root);
        boolean form = fields != null && findLoginButton(root, fields.password) != null;
        if (stage != STAGE_LOGIN && !prompt && !form) return false;

        if (stage != STAGE_LOGIN) {
            if (loginAttemptedThisRun) {
                fail("로그인 시도 후에도 로그인 화면이 다시 나타났습니다. 계정 정보나 추가 인증을 확인해주세요.");
                return true;
            }
            if (runAccounts.isEmpty()) {
                fail("신세계 앱 로그인이 필요하지만 저장된 계정이 없습니다. 앱에서 계정을 추가해주세요.");
                return true;
            }
            stage = STAGE_LOGIN;
            loginWaitAttempts = 0;
            loginPromptClicked = false;
            loginFieldsFilled = false;
            loginSubmitted = false;
            loginKeyboardDismissed = false;
            loginAutoToggleAttempts = 0;
            loginAutoControlWaits = 0;
        }

        if (loginSubmitted) {
            if (findVisibleAnyContains(root, "아이디 또는 비밀번호", "비밀번호가 일치", "로그인에 실패", "비밀번호를 확인") != null) {
                fail("신세계 앱에서 로그인 정보를 확인하라는 오류가 표시되었습니다. 저장한 아이디와 비밀번호를 확인해주세요.");
                return true;
            }
            if (handleHumanVerification(root)) return true;
            boolean homeVisible = contains(root, "MY SHINSEGAE")
                    || (contains(root, "온라인쇼핑") && contains(root, "백화점"));
            boolean myPageVisible = contains(root, "나의 신세계")
                    && contains(root, "안녕하세요");
            if (fields == null && !prompt && (homeVisible || myPageVisible)) {
                if (myPageVisible && !homeVisible) {
                    AccessibilityNodeInfo home = findVisibleExact(root, "홈");
                    if (home != null) clickNodeOrCenter(home);
                    else tap(0.63f, 0.918f);
                }
                stage = STAGE_HOME;
                stageReadyAt = SystemClock.uptimeMillis() + 700L;
                scheduleProcess(700L);
                return true;
            }
            if (++loginWaitAttempts > 25) {
                fail("로그인 버튼을 누른 뒤에도 로그인 화면이 유지됩니다. 계정 정보 또는 추가 인증을 확인해주세요.");
                return true;
            }
            scheduleProcess(SHORT_WAIT_MS);
            return true;
        }

        // The Shinsegae login-required dialog must be confirmed before the
        // ID/password fields can be used. Prefer its explicit "예" action.
        if (prompt) {
            if (!loginPromptClicked) {
                AccessibilityNodeInfo confirmation = findVisibleExact(root, "예");
                if (confirmation == null) confirmation = findLoginButton(root);
                if (confirmation == null) confirmation = findVisibleExact(root, "확인");
                if (confirmation != null) {
                    clickNodeOrCenter(confirmation);
                    loginPromptClicked = true;
                    stageReadyAt = SystemClock.uptimeMillis() + 650L;
                    scheduleProcess(650L);
                    return true;
                }
            }
            if (++loginWaitAttempts > 25) {
                fail("로그인이 필요하다는 팝업에서 ‘예’를 누른 뒤에도 로그인 화면이 열리지 않았습니다.");
                return true;
            }
            scheduleProcess(SHORT_WAIT_MS);
            return true;
        }

        if (loginFieldsFilled) {
            // Setting the password often rebuilds the WebView. Find the
            // enabled submit button and confirm auto-login is still off on
            // the refreshed accessibility tree before submitting.
            if (ensureLoginAutoOff(root, true)) return true;
            AccessibilityNodeInfo loginButton = findLoginButton(root, loginPasswordCenterY);
            if (loginButton != null) {
                if (!loginButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        && !tapLoginButton(loginButton)) {
                    fail("로그인 버튼을 누르지 못했습니다. 로그인 화면을 확인해주세요.");
                    return true;
                }
                loginFieldsFilled = false;
                loginSubmitted = true;
                loginAttemptedThisRun = true;
                loginWaitAttempts = 0;
                stageReadyAt = SystemClock.uptimeMillis() + 1300L;
                scheduleProcess(1300L);
                return true;
            }
            if (++loginWaitAttempts > 12) {
                fail("아이디·비밀번호 입력 후 로그인 버튼을 찾지 못했습니다. 로그인 화면이 변경되었을 수 있습니다.");
                return true;
            }
            scheduleProcess(400L);
            return true;
        }

        if (form) {
            if (ensureLoginAutoOff(root, false)) return true;
            LoginCredentials.Account account = runAccounts.get(accountIndex);
            if (account == null || account.username.isEmpty() || account.password.isEmpty()) {
                fail("저장된 로그인 정보가 비어 있습니다. 앱에서 다시 저장해주세요.");
                return true;
            }
            if (!setNodeText(fields.username, account.username)
                    || !setNodeText(fields.password, account.password)) {
                fail("신세계 앱 로그인 입력칸에 정보를 입력하지 못했습니다. 로그인 화면이 변경되었을 수 있습니다.");
                return true;
            }
            Rect passwordBounds = new Rect();
            fields.password.getBoundsInScreen(passwordBounds);
            loginPasswordCenterY = passwordBounds.centerY();
            loginFieldsFilled = true;
            loginKeyboardDismissed = false;
            loginWaitAttempts = 0;
            stageReadyAt = SystemClock.uptimeMillis() + 700L;
            scheduleProcess(700L);
            return true;
        }

        if (++loginWaitAttempts > 25) {
            fail("로그인이 필요한 화면은 확인했지만 아이디·비밀번호 입력칸을 찾지 못했습니다. 직접 로그인해주세요.");
            return true;
        }
        scheduleProcess(SHORT_WAIT_MS);
        return true;
    }

    private boolean handleHumanVerification(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo robotLabel = findRobotControl(root);
        AccessibilityNodeInfo robotCheckbox = robotLabel == null
                ? null : findCheckboxForLabel(root, robotLabel);
        boolean additionalAuth = findVisibleAnyContains(root,
                "인증번호", "추가 인증", "보안문자", "reCAPTCHA") != null;
        boolean checked = isHumanVerificationComplete(root, robotLabel, robotCheckbox);
        boolean explicitlyUnchecked = !checked && robotCheckbox != null
                && robotCheckbox.isCheckable() && !robotCheckbox.isChecked();
        if (explicitlyUnchecked) {
            humanVerificationComplete = false;
            if (captchaResubmitted) verificationVibrated = false;
            captchaResubmitted = false;
        }
        if (checked) humanVerificationComplete = true;
        if (!awaitingHumanVerification && robotLabel == null && !additionalAuth) {
            captchaResubmitted = false;
            humanVerificationComplete = false;
            return false;
        }
        if (captchaResubmitted && !awaitingHumanVerification) return false;

        beginHumanVerification();
        if (robotLabel != null && !verificationVibrated && !checked) {
            Notifier.vibrateVerification(this);
            verificationVibrated = true;
        }

        // A user may finish the entire login themselves. Only a recognized
        // logged-in screen is sufficient to leave the manual wait here.
        boolean loggedInPage = (contains(root, "나의 신세계") && contains(root, "안녕하세요"))
                || contains(root, "MY SHINSEGAE")
                || (contains(root, "온라인쇼핑") && contains(root, "백화점"));
        if (robotLabel == null && !additionalAuth && findLoginFields(root) == null && loggedInPage) {
            endHumanVerification();
            captchaResubmitted = false;
            loginWaitAttempts = 0;
            return false;
        }
        if (humanVerificationComplete && !captchaResubmitted) {
            AccessibilityNodeInfo login = findVerificationLoginButton(root);
            if (login != null && login.isEnabled()) {
                // The human completed CAPTCHA; interact only with the app's
                // login button, never with the CAPTCHA or its challenge.
                // Custom WebView buttons can acknowledge ACTION_CLICK without
                // submitting. Tap the confirmed app submit control directly.
                if (!tapLoginButton(login)) {
                    fail("직접 인증 후 로그인 버튼을 누르지 못했습니다.");
                    return true;
                }
                captchaResubmitted = true;
                endHumanVerification();
                loginWaitAttempts = 0;
                scheduleProcess(1300L);
                return true;
            }
        }
        // An unreadable/missing checkbox is not proof of verification. Keep
        // waiting so the user can finish authentication/login themselves.
        scheduleProcess(1000L);
        return true;
    }

    private void beginHumanVerification() {
        if (awaitingHumanVerification) return;
        awaitingHumanVerification = true;
        humanVerificationStartedAt = SystemClock.uptimeMillis();
        verificationCurtainSuspended = curtainView != null;
        removePrivacyCurtain();
        if (wakeLock != null) wakeLock.acquire(HUMAN_VERIFICATION_TIMEOUT_MS
                + Math.max(3, runAccounts.size() * 3) * 60000L);
    }

    private AccessibilityNodeInfo findRobotControl(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String id = node.getViewIdResourceName();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && !bounds.isEmpty()
                    && bounds.height() < getResources().getDisplayMetrics().heightPixels * 0.20f
                    && (isRobotLabel(node.getText())
                    || isRobotLabel(node.getContentDescription())
                    || (id != null && id.endsWith("recaptcha-anchor")))) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private boolean isRobotLabel(CharSequence value) {
        if (value == null) return false;
        String label = value.toString().replaceAll("\\s+", "").replace("’", "'")
                .toLowerCase(java.util.Locale.ROOT);
        return label.contains("로봇이아닙니다") || label.contains("i'mnotarobot");
    }

    private AccessibilityNodeInfo findVerificationLoginButton(AccessibilityNodeInfo root) {
        // Select a wide submit control below the header. CAPTCHA can change
        // field positions or add editable controls, invalidating saved bounds.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo fallback = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString().trim();
            String description = node.getContentDescription() == null
                    ? "" : node.getContentDescription().toString().trim();
            if (node.isVisibleToUser() && ("로그인".equals(text) || "로그인하기".equals(text)
                    || "로그인".equals(description) || "로그인하기".equals(description))) {
                AccessibilityNodeInfo candidate = node;
                for (int i = 0; i < 4 && candidate != null; i++, candidate = candidate.getParent()) {
                    Rect bounds = new Rect();
                    candidate.getBoundsInScreen(bounds);
                    if (candidate.isVisibleToUser() && candidate.isEnabled()
                            && bounds.width() > width * 0.40f && bounds.height() > 0
                            && bounds.height() < height * 0.15f && bounds.centerY() > height * 0.25f) {
                        if (candidate.isClickable()) return candidate;
                        fallback = candidate;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return fallback;
    }

    private boolean isHumanVerificationComplete(AccessibilityNodeInfo root,
            AccessibilityNodeInfo label, AccessibilityNodeInfo checkbox) {
        if (checkbox != null && checkbox.isChecked()) return true;
        if (hasVerifiedState(checkbox) || hasVerifiedState(label)) return true;
        if (label != null) {
            Rect labelBounds = new Rect();
            label.getBoundsInScreen(labelBounds);
            for (AccessibilityNodeInfo parent = label.getParent(); parent != null; parent = parent.getParent()) {
                Rect bounds = new Rect();
                parent.getBoundsInScreen(bounds);
                if (bounds.height() > getResources().getDisplayMetrics().heightPixels * 0.15f) break;
                if (hasVerifiedState(parent)) return true;
            }
            // WebView controls can expose checked state in their description
            // even when Android does not mark the node as checkable.
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo node = queue.removeFirst();
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                boolean besideLabel = !bounds.isEmpty()
                        && bounds.height() < getResources().getDisplayMetrics().heightPixels * 0.09f
                        && Math.abs(bounds.centerY() - labelBounds.centerY())
                            < getResources().getDisplayMetrics().heightPixels * 0.035f
                        && bounds.centerX() <= labelBounds.right
                        && bounds.centerX() >= labelBounds.left
                            - getResources().getDisplayMetrics().widthPixels * 0.18f;
                if (node.isVisibleToUser() && besideLabel && hasVerifiedState(node)) return true;
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            }
        }
        // Some CAPTCHA widgets replace their checkbox with a completion text.
        // Its disappearance or an enabled login button alone is not proof.
        return awaitingHumanVerification && findVisibleAnyContains(root,
                "reCAPTCHA 인증 완료", "로봇 인증 완료", "로봇이 아님이 확인",
                "Verification complete", "You are verified", "You have been verified") != null;
    }

    private boolean hasVerifiedState(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return false;
        if (node.isChecked()) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && VerificationState.isComplete(node.getStateDescription())) return true;
        return VerificationState.isComplete(node.getText())
                || VerificationState.isComplete(node.getContentDescription());
    }

    private void endHumanVerification() {
        if (!awaitingHumanVerification) return;
        accountStartedAt += SystemClock.uptimeMillis() - humanVerificationStartedAt;
        awaitingHumanVerification = false;
        humanVerificationStartedAt = 0L;
        foregroundMissingSince = 0L;
        if (verificationCurtainSuspended && running) showPrivacyCurtain();
        verificationCurtainSuspended = false;
    }

    private boolean ensureLoginAutoOff(AccessibilityNodeInfo root, boolean afterFields) {
        AccessibilityNodeInfo autoLogin = findLoginAutoCheckbox(root);
        if (autoLogin == null) {
            if (++loginAutoControlWaits > 12) {
                fail("로그인 화면에서 '자동 로그인' 체크 상태를 확인하지 못했습니다. 직접 체크를 끈 뒤 다시 실행해주세요.");
            } else {
                scheduleProcess(400L);
            }
            return true;
        }
        loginAutoControlWaits = 0;
        if (autoLogin.isChecked()) {
            if (++loginAutoToggleAttempts > 3) {
                fail("로그인 화면의 '자동 로그인' 체크를 해제하지 못했습니다.");
                return true;
            }
            clickNodeOrCenter(autoLogin);
            if (afterFields) loginFieldsFilled = false;
            scheduleProcess(500L);
            return true;
        }
        loginAutoToggleAttempts = 0;
        return false;
    }

    private static final class LoginFields {
        final AccessibilityNodeInfo username;
        final AccessibilityNodeInfo password;

        LoginFields(AccessibilityNodeInfo username, AccessibilityNodeInfo password) {
            this.username = username;
            this.password = password;
        }
    }

    private LoginFields findLoginFields(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        ArrayList<AccessibilityNodeInfo> usernames = new ArrayList<>();
        AccessibilityNodeInfo password = null;
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser() && node.isEditable()) {
                if (node.isPassword()) {
                    if (password != null) return null;
                    password = node;
                } else {
                    usernames.add(node);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        if (password == null || usernames.size() != 1) return null;
        Rect userBounds = new Rect();
        Rect passwordBounds = new Rect();
        usernames.get(0).getBoundsInScreen(userBounds);
        password.getBoundsInScreen(passwordBounds);
        if (userBounds.isEmpty() || passwordBounds.isEmpty()
                || userBounds.centerY() >= passwordBounds.centerY()) return null;
        return new LoginFields(usernames.get(0), password);
    }

    private AccessibilityNodeInfo findLoginAutoCheckbox(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo label = findVisibleExact(root, "자동 로그인");
        if (label == null) label = findVisibleExact(root, "자동로그인");
        if (label == null) return null;
        return findCheckboxForLabel(root, label);
    }

    private AccessibilityNodeInfo findCheckboxForLabel(AccessibilityNodeInfo root, AccessibilityNodeInfo label) {
        if (label.isCheckable()) return label;

        Rect labelBounds = new Rect();
        label.getBoundsInScreen(labelBounds);
        if (labelBounds.isEmpty()) return null;
        for (AccessibilityNodeInfo parent = label.getParent(); parent != null; parent = parent.getParent()) {
            Rect bounds = new Rect();
            parent.getBoundsInScreen(bounds);
            if (parent.isCheckable() && bounds.height() < getResources().getDisplayMetrics().heightPixels * 0.09f) {
                return parent;
            }
            if (bounds.height() > getResources().getDisplayMetrics().heightPixels * 0.16f) break;
        }

        // Find only the checkable control alongside this label, avoiding
        // unrelated controls elsewhere on the page or behind a popup.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo closest = null;
        int closestDistance = Integer.MAX_VALUE;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser() && node.isCheckable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int vertical = Math.abs(bounds.centerY() - labelBounds.centerY());
                int horizontal = bounds.centerX() < labelBounds.left
                        ? labelBounds.left - bounds.centerX()
                        : Math.max(0, bounds.centerX() - labelBounds.right);
                if (!bounds.isEmpty() && vertical < height * 0.035f
                        && horizontal < width * 0.18f && horizontal + vertical < closestDistance) {
                    closest = node;
                    closestDistance = horizontal + vertical;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return closest;
    }

    private AccessibilityNodeInfo findLoginButton(AccessibilityNodeInfo root) {
        return findLoginButton(root, null);
    }

    private AccessibilityNodeInfo findLoginButton(AccessibilityNodeInfo root, AccessibilityNodeInfo passwordField) {
        Rect passwordBounds = new Rect();
        if (passwordField != null) passwordField.getBoundsInScreen(passwordBounds);
        return findLoginButton(root, passwordField == null ? -1 : passwordBounds.centerY());
    }

    private AccessibilityNodeInfo findLoginButton(AccessibilityNodeInfo root, int minimumY) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        Rect bounds = new Rect();
        AccessibilityNodeInfo visibleLabel = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence value = node.getText();
            if (TextUtils.isEmpty(value)) value = node.getContentDescription();
            String label = value == null ? "" : value.toString().trim();
            if (("로그인".equals(label) || "로그인하기".equals(label)) && node.isVisibleToUser()) {
                AccessibilityNodeInfo candidate = node;
                for (int i = 0; i < 3 && candidate != null; i++, candidate = candidate.getParent()) {
                    candidate.getBoundsInScreen(bounds);
                    if (candidate.isClickable() && bounds.height() > 0
                            && bounds.centerY() > minimumY
                            && bounds.height() < getResources().getDisplayMetrics().heightPixels * 0.18f) {
                        return candidate;
                    }
                }
                node.getBoundsInScreen(bounds);
                if (minimumY >= 0 && bounds.height() > 0 && bounds.width() > 0
                        && bounds.centerY() > minimumY
                        && bounds.height() < getResources().getDisplayMetrics().heightPixels * 0.18f) {
                    visibleLabel = node;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return visibleLabel;
    }

    private boolean tapLoginButton(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!node.isVisibleToUser() || bounds.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 100L);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private boolean isInputMethodVisible() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String value) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
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
        endHumanVerification();
        if (!runAccounts.isEmpty()) {
            accountSuccessCount++;
            accountResults.add((accountIndex + 1) + "번째 성공");
            if (accountIndex + 1 < runAccounts.size()) {
                accountIndex++;
                beginLogoutForNextAccount(true);
            } else {
                finishBatch(null);
            }
            return;
        }
        running = false;
        handler.removeCallbacks(processRunnable);
        handler.removeCallbacks(timeoutRunnable);
        getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE).edit()
                .putString("last_result", message)
                .putLong("last_result_at", System.currentTimeMillis())
                .apply();
        Notifier.show(this, "신세계 출석 완료", message);
        finishVisualRun();
    }

    private void fail(String message) {
        if (!running) return;
        endHumanVerification();
        if (!runAccounts.isEmpty()) {
            if (stage == STAGE_LOGOUT) {
                finishBatch("계정 전환 중단: " + message);
                return;
            }
            accountResults.add((accountIndex + 1) + "번째 실패: " + message);
            if (accountIndex + 1 < runAccounts.size()) {
                accountIndex++;
                beginLogoutForNextAccount();
            } else {
                finishBatch(null);
            }
            return;
        }
        running = false;
        handler.removeCallbacks(processRunnable);
        handler.removeCallbacks(timeoutRunnable);
        getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE).edit()
                .putString("last_result", message)
                .putLong("last_result_at", System.currentTimeMillis())
                .apply();
        Notifier.show(this, "신세계 자동 출석 실패", message);
        finishVisualRun();
    }

    private void finishBatch(String interruption) {
        running = false;
        handler.removeCallbacks(processRunnable);
        handler.removeCallbacks(timeoutRunnable);
        StringBuilder result = new StringBuilder();
        result.append(runAccounts.size()).append("개 계정 중 ")
                .append(accountSuccessCount).append("개 출석 성공");
        if (interruption != null) result.append(". ").append(interruption);
        for (String accountResult : accountResults) {
            if (accountResult.contains("실패")) result.append(". ").append(accountResult);
        }
        String message = result.toString();
        getSharedPreferences(ScheduleManager.PREFS, MODE_PRIVATE).edit()
                .putString("last_result", message)
                .putLong("last_result_at", System.currentTimeMillis())
                .apply();
        Notifier.show(this,
                interruption == null && accountSuccessCount == runAccounts.size()
                        ? "모든 계정 출석 완료" : "계정별 출석 결과 확인",
                message);
        runAccounts = new ArrayList<>();
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
        // Dismiss Shinsegae's exact-title card in Recents, then remove only
        // our own MainActivity task before restoring the previous display state.
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
                handler.postDelayed(this::removeOwnRecentTaskAndRestore, 300L);
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
                handler.postDelayed(this::removeOwnRecentTaskAndRestore, 700L);
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
        handler.postDelayed(this::removeOwnRecentTaskAndRestore, 800L);
    }

    private void removeOwnRecentTaskAndRestore() {
        // Android exposes only this application's tasks here. Remove the
        // MainActivity card without swiping or guessing a neighboring card.
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null) {
            try {
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info != null && info.baseActivity != null
                            && MainActivity.class.getName().equals(info.baseActivity.getClassName())) {
                        task.finishAndRemoveTask();
                    }
                }
            } catch (RuntimeException ignored) {
                // A missing or already-removed own task must not prevent exit.
            }
        }
        restorePreviousVisualState();
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
