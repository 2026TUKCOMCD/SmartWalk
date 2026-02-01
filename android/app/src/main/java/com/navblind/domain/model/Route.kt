package com.navblind.domain.model

import java.util.UUID

data class Route(
    val sessionId: UUID,
    val distance: Int,
    val duration: Int,
    val waypoints: List<Waypoint>,
    val instructions: List<Instruction>
) {
    val estimatedTimeMinutes: Int
        get() = duration / 60

    val distanceFormatted: String
        get() = when {
            distance < 1000 -> "${distance}m"
            else -> String.format("%.1fkm", distance / 1000.0)
        }
}

data class Waypoint(
    val lat: Double,
    val lng: Double,
    val name: String? = null
) {
    fun toCoordinate() = Coordinate(lat, lng)
}

data class Instruction(
    val step: Int,
    val type: InstructionType,
    val modifier: TurnModifier?,
    val text: String,
    val distance: Int,
    val location: Waypoint
) {
    val distanceFormatted: String
        get() = when {
            distance < 100 -> "${distance}m"
            distance < 1000 -> "${(distance / 10) * 10}m"
            else -> String.format("%.1fkm", distance / 1000.0)
        }
}

enum class InstructionType {
    DEPART,
    TURN,
    ARRIVE,
    CONTINUE,
    CROSSWALK;

    companion object {
        fun fromString(value: String?): InstructionType {
            return when (value?.lowercase()) {
                "depart" -> DEPART
                "turn" -> TURN
                "arrive" -> ARRIVE
                "crosswalk" -> CROSSWALK
                else -> CONTINUE
            }
        }
    }
}

enum class TurnModifier {
    LEFT,
    RIGHT,
    STRAIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    UTURN;

    companion object {
        fun fromString(value: String?): TurnModifier? {
            return when (value?.lowercase()) {
                "left" -> LEFT
                "right" -> RIGHT
                "straight" -> STRAIGHT
                "slight_left" -> SLIGHT_LEFT
                "slight_right" -> SLIGHT_RIGHT
                "uturn" -> UTURN
                else -> null
            }
        }
    }
}
