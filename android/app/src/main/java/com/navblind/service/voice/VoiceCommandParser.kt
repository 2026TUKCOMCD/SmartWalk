package com.navblind.service.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 음성 인식 텍스트를 네비게이션 명령으로 파싱합니다.
 *
 * 시각장애인 사용자가 걸으면서 음성으로 네비게이션을 제어할 수 있도록
 * 한국어 키워드 매칭으로 명령을 해석합니다.
 *
 * FR-016: 시작, 중지, 반복 등 공통 동작에 대한 음성 명령 지원
 *
 * 지원 명령:
 * - 반복: "다시", "반복", "뭐라고", "못 들었어" 등
 * - 중지: "멈춰", "그만", "안내 종료" 등
 * - 남은 거리: "얼마나 남았어", "몇 미터" 등
 * - 현재 위치: "지금 어디야", "현재 위치" 등
 */
@Singleton
class VoiceCommandParser @Inject constructor() {

    /**
     * 인식된 텍스트를 네비게이션 명령으로 변환합니다.
     * 공백을 제거한 뒤 키워드 포함 여부로 판단합니다.
     */
    fun parse(text: String): NavigationCommand {
        val normalized = text.trim().replace(" ", "")

        return when {
            REPEAT_KEYWORDS.any { normalized.contains(it) } -> NavigationCommand.Repeat
            STOP_KEYWORDS.any { normalized.contains(it) } -> NavigationCommand.Stop
            REMAINING_KEYWORDS.any { normalized.contains(it) } -> NavigationCommand.RemainingDistance
            WHERE_AM_I_KEYWORDS.any { normalized.contains(it) } -> NavigationCommand.WhereAmI
            else -> NavigationCommand.Unknown(text)
        }
    }

    companion object {
        // "다시", "반복", "뭐라고", "못들었어", "못알아들었어", "다시들려줘"
        private val REPEAT_KEYWORDS = listOf(
            "반복", "다시", "뭐라고", "못들었", "못알아", "다시들려", "다시말해"
        )
        // "멈춰", "중지", "그만해", "안내종료", "안내중지", "취소"
        private val STOP_KEYWORDS = listOf(
            "멈춰", "중지", "그만", "종료", "취소", "안내끝", "안내중지", "길안내종료"
        )
        // "얼마나남았어", "남은거리알려줘", "몇미터남았어", "얼마걸려"
        private val REMAINING_KEYWORDS = listOf(
            "얼마나", "남은거리", "몇미터", "얼마남", "몇킬로", "얼마걸려"
        )
        // "지금어디야", "현재위치알려줘", "위치알려줘", "여기가어디야"
        private val WHERE_AM_I_KEYWORDS = listOf(
            "지금어디", "현재위치", "위치알려", "어디야", "어디있어", "여기가어디"
        )
    }
}

/**
 * 파싱된 네비게이션 명령
 */
sealed class NavigationCommand {
    /** "다시", "반복해줘" → 현재 안내 문장 반복 */
    object Repeat : NavigationCommand()

    /** "멈춰", "그만" → 길안내 종료 */
    object Stop : NavigationCommand()

    /** "얼마나 남았어", "몇 미터" → 남은 거리 음성 안내 */
    object RemainingDistance : NavigationCommand()

    /** "지금 어디야", "현재 위치" → 현재 안내 단계 음성 안내 */
    object WhereAmI : NavigationCommand()

    /** 인식은 됐으나 매칭되는 명령 없음 */
    data class Unknown(val rawText: String) : NavigationCommand()
}
