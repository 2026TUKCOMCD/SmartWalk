package com.navblind.service.voice

import android.util.Log
import com.navblind.domain.model.Instruction
import com.navblind.domain.model.InstructionType
import com.navblind.domain.model.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 턴-바이-턴 음성 안내를 담당하는 서비스입니다.
 *
 * TextToSpeechService와 InstructionToSpeechConverter를 조합하여
 * 네비게이션의 모든 음성 안내 흐름을 관리합니다.
 *
 * 주요 기능:
 * - 경로 시작/종료 안내
 * - 턴 안내 (중복 방지)
 * - 사전 예고 (50m, 30m 전 예고)
 * - 재탐색 안내
 * - 도착 안내
 */
@Singleton
class NavigationGuidanceService @Inject constructor(
    private val textToSpeechService: TextToSpeechService,
    private val instructionToSpeechConverter: InstructionToSpeechConverter
) {
    private var lastAnnouncedStep = STEP_NONE
    private var preWarning50mAnnounced = false
    private var preWarning30mAnnounced = false

    /**
     * 경로 탐색 완료 후 첫 안내를 합니다.
     */
    fun announceRouteStart(
        route: Route,
        destinationName: String,
        currentLat: Double,
        currentLng: Double
    ) {
        val message = instructionToSpeechConverter.generateRouteStartMessage(
            route = route,
            destinationName = destinationName,
            currentLat = currentLat,
            currentLng = currentLng
        )
        textToSpeechService.speak(message, TextToSpeechService.Priority.HIGH)
        lastAnnouncedStep = STEP_NONE
        resetPreWarnings()
        Log.d(TAG, "Route start announced to $destinationName")
    }

    /**
     * 현재 instruction을 안내합니다. 같은 step은 중복 안내하지 않습니다.
     */
    fun announceInstruction(
        instruction: Instruction,
        route: Route? = null,
        currentLat: Double? = null,
        currentLng: Double? = null
    ) {
        if (instruction.step == lastAnnouncedStep) return
        lastAnnouncedStep = instruction.step
        resetPreWarnings()

        val message = instructionToSpeechConverter.convert(
            instruction = instruction,
            route = route,
            currentLat = currentLat,
            currentLng = currentLng
        )
        val priority = if (instruction.type == InstructionType.CROSSWALK) {
            TextToSpeechService.Priority.HIGH
        } else {
            TextToSpeechService.Priority.NORMAL
        }
        textToSpeechService.speak(message, priority)
        Log.d(TAG, "Instruction announced (step ${instruction.step}): $message")
    }

    /**
     * 다음 전환점이 가까워질 때 사전 예고 안내를 합니다.
     * 50m, 30m 각각 1회씩 안내합니다.
     */
    fun announceUpcomingTurn(
        instruction: Instruction,
        distanceMeters: Int,
        currentLat: Double,
        currentLng: Double
    ) {
        val shouldAnnounce = when {
            distanceMeters <= 30 && !preWarning30mAnnounced -> {
                preWarning30mAnnounced = true
                true
            }
            distanceMeters <= 50 && !preWarning50mAnnounced -> {
                preWarning50mAnnounced = true
                true
            }
            else -> false
        }
        if (!shouldAnnounce) return

        val message = instructionToSpeechConverter.generateUpcomingTurnMessage(
            instruction = instruction,
            distanceToInstruction = distanceMeters,
            currentLat = currentLat,
            currentLng = currentLng
        )
        textToSpeechService.speak(message)
        Log.d(TAG, "Upcoming turn announced (${distanceMeters}m): $message")
    }

    /**
     * 경로 이탈 감지 시 재탐색 시작을 안내합니다.
     */
    fun announceRerouting() {
        val message = instructionToSpeechConverter.generateRerouteMessage()
        textToSpeechService.speak(message, TextToSpeechService.Priority.HIGH)
        lastAnnouncedStep = STEP_NONE
        resetPreWarnings()
        Log.d(TAG, "Rerouting announced")
    }

    /**
     * 재탐색 완료 후 새 경로의 첫 안내를 합니다.
     */
    fun announceNewRoute(
        route: Route,
        currentLat: Double,
        currentLng: Double
    ) {
        val message = instructionToSpeechConverter.generateNewRouteMessage(
            route = route,
            currentLat = currentLat,
            currentLng = currentLng
        )
        textToSpeechService.speak(message, TextToSpeechService.Priority.HIGH)
        Log.d(TAG, "New route announced")
    }

    /**
     * 목적지 도착을 안내합니다.
     */
    fun announceArrival() {
        textToSpeechService.speak("목적지에 도착했습니다", TextToSpeechService.Priority.HIGH)
        lastAnnouncedStep = STEP_NONE
        Log.d(TAG, "Arrival announced")
    }

    /**
     * 길안내 종료를 안내합니다.
     */
    fun announceNavigationStopped() {
        textToSpeechService.speak("길안내를 종료합니다", TextToSpeechService.Priority.HIGH)
        lastAnnouncedStep = STEP_NONE
        resetPreWarnings()
        Log.d(TAG, "Navigation stopped announced")
    }

    /**
     * 현재 instruction을 다시 안내합니다 (사용자 요청 시).
     * 중복 방지 로직을 무시하고 강제로 읽습니다.
     */
    fun repeatInstruction(
        instruction: Instruction,
        route: Route? = null,
        currentLat: Double? = null,
        currentLng: Double? = null
    ) {
        val message = instructionToSpeechConverter.convert(
            instruction = instruction,
            route = route,
            currentLat = currentLat,
            currentLng = currentLng
        )
        textToSpeechService.speak(message, TextToSpeechService.Priority.HIGH)
        Log.d(TAG, "Instruction repeated: $message")
    }

    /**
     * 남은 거리를 안내합니다 (사용자 "얼마나 남았어" 명령 응답).
     */
    fun announceRemainingDistance(meters: Int) {
        val message = when {
            meters < 50 -> "거의 다 왔습니다. ${meters}미터 남았습니다."
            meters < 1000 -> "${meters}미터 남았습니다."
            else -> {
                val km = meters / 1000
                val remainder = (meters % 1000) / 100
                if (remainder == 0) "${km}킬로미터 남았습니다."
                else "${km}킬로미터 ${remainder}백 미터 남았습니다."
            }
        }
        textToSpeechService.speak(message)
        Log.d(TAG, "Remaining distance announced: ${meters}m")
    }

    /**
     * GPS 신호 소실/복구 안내를 합니다.
     */
    fun announceGpsLost() {
        textToSpeechService.speak("GPS 신호를 찾고 있습니다", TextToSpeechService.Priority.HIGH)
    }

    fun announceGpsRecovered() {
        textToSpeechService.speak("GPS 신호가 연결되었습니다")
    }

    fun announceError(message: String) {
        textToSpeechService.speak(message, TextToSpeechService.Priority.HIGH)
    }

    fun stop() {
        textToSpeechService.stop()
    }

    private fun resetPreWarnings() {
        preWarning50mAnnounced = false
        preWarning30mAnnounced = false
    }

    companion object {
        private const val TAG = "NavigationGuidanceService"
        private const val STEP_NONE = -1
    }
}
