package com.codex.shinsegaeattendance;

import java.util.Locale;

/** Reads explicit accessibility completion states; never solves a challenge. */
final class VerificationState {
    private VerificationState() {}

    static boolean isComplete(CharSequence value) {
        if (value == null) return false;
        String state = value.toString().toLowerCase(Locale.ROOT).trim();
        if (state.contains("unchecked") || state.contains("not checked")
                || state.contains("not verified") || state.contains("unverified")
                || state.contains("checked=false") || state.contains("checked: false")
                || state.contains("체크 안") || state.contains("체크되지")
                || state.contains("선택 안") || state.contains("선택되지")
                || state.contains("확인되지") || state.contains("미확인")
                || state.contains("인증되지") || state.contains("미인증")
                || state.contains("체크 해제") || state.contains("선택 해제")
                || state.contains("미선택") || state.contains("인증 미완료")) return false;
        return state.matches("(?s).*\\b(checked|verified)\\b.*")
                || state.contains("체크됨") || state.contains("체크 됨")
                || state.contains("선택됨") || state.contains("선택 됨")
                || state.contains("확인됨") || state.contains("확인 됨")
                || state.contains("인증 완료") || state.contains("인증완료");
    }
}
