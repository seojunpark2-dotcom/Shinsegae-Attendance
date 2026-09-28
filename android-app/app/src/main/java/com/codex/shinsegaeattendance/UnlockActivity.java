package com.codex.shinsegaeattendance;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

public class UnlockActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean completed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.postDelayed(this::requestDismiss, 300L);
        handler.postDelayed(() -> complete(false), 4500L);
    }

    private void requestDismiss() {
        if (completed) return;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!keyguard.isKeyguardLocked()) {
            complete(true);
            return;
        }
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                complete(true);
            }

            @Override
            public void onDismissCancelled() {
                complete(false);
            }

            @Override
            public void onDismissError() {
                KeyguardManager current = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                complete(!current.isKeyguardLocked());
            }
        });
    }

    private void complete(boolean dismissed) {
        if (completed) return;
        completed = true;
        handler.removeCallbacksAndMessages(null);
        AttendanceAccessibilityService.onSystemKeyguardDismissResult(dismissed);
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
