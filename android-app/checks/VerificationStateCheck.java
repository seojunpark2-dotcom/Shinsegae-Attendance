package com.codex.shinsegaeattendance;

/** Run with javac/java; no Android device or test dependency is needed. */
public final class VerificationStateCheck {
    public static void main(String[] args) {
        String[] completed = {"체크됨", "확인 됨", "선택됨", "인증 완료",
                "로봇이 아닙니다, checked", "You are verified", "CHECKED", "로봇이 아닙니다\nchecked"};
        String[] pending = {null, "", "로봇이 아닙니다", "확인 필요", "unchecked",
                "not checked", "not verified", "unverified", "checked=false", "checked: false",
                "체크 해제됨", "선택 해제됨", "선택되지 않음", "확인되지 않음", "인증 미완료"};
        for (String state : completed) {
            if (!VerificationState.isComplete(state)) throw new AssertionError("Completion missed: " + state);
        }
        for (String state : pending) {
            if (VerificationState.isComplete(state)) throw new AssertionError("Pending accepted: " + state);
        }
        System.out.println("Verification accessibility-state checks passed: "
                + (completed.length + pending.length));
    }
}
