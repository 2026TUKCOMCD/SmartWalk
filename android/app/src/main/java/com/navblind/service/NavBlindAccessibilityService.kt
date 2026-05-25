package com.navblind.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 접근성 서비스 통합 (T144).
 * 현재는 기본 구현만 제공하며, 향후 외부 접근성 이벤트 수신·TalkBack 연동에 사용된다.
 */
class NavBlindAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 향후: 외부 앱의 접근성 이벤트를 수신하여 나침반/신호등 정보 보강
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
    }
}
