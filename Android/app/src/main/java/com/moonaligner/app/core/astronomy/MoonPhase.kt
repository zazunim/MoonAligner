package com.moonaligner.app.core.astronomy

/**
 * 달의 위상 (Moon Phase) 정의
 */
enum class MoonPhase(
    val displayName: String,
    val emoji: String,
    val angleRange: ClosedFloatingPointRange<Double>
) {
    NEW_MOON("신월 (삭)", "🌑", 0.0..22.5),
    WAXING_CRESCENT("초승달", "🌒", 22.5..67.5),
    FIRST_QUARTER("상현달", "🌓", 67.5..112.5),
    WAXING_GIBBOUS("차오르는 달", "🌔", 112.5..157.5),
    FULL_MOON("보름달 (망)", "🌕", 157.5..202.5),
    WANING_GIBBOUS("기우는 달", "🌖", 202.5..247.5),
    LAST_QUARTER("하현달", "🌗", 247.5..292.5),
    WANING_CRESCENT("그믐달", "🌘", 292.5..337.5);

    companion object {
        fun fromPhaseAngle(angleDeg: Double): MoonPhase {
            val normalized = (angleDeg % 360.0 + 360.0) % 360.0
            if (normalized >= 337.5 || normalized < 22.5) return NEW_MOON
            return entries.firstOrNull { normalized in it.angleRange } ?: NEW_MOON
        }
    }
}

data class MoonDetails(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val distanceKm: Double,
    val angularDiameterDeg: Double,
    val phase: MoonPhase,
    val illuminationFraction: Double, // 0.0 ~ 1.0 (100% = 완전 보름달)
    val ageDays: Double
)

data class SunDetails(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val isGoldenHour: Boolean, // 태양 고도 -4도 ~ +6도
    val isBlueHour: Boolean    // 태양 고도 -6도 ~ -4도
)
