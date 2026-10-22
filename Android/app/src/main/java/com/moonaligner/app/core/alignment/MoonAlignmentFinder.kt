package com.moonaligner.app.core.alignment

import com.moonaligner.app.core.astronomy.EphemerisEngine
import com.moonaligner.app.core.astronomy.MoonDetails
import com.moonaligner.app.core.astronomy.SunDetails
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.*

/**
 * 달-랜드마크 정렬(달걸이) 최적 촬영 기회 모델
 */
data class MoonAlignmentOpportunity(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant,
    val moonDetails: MoonDetails,
    val sunDetails: SunDetails,
    val targetBearingDeg: Double,
    val targetElevationDeg: Double,
    val angularOffsetDeg: Double,
    val distanceMeters: Double,
    val skyStage: SkyLightingStage,
    val score: Int, // 0 ~ 100
    val grade: OpportunityGrade,
    val recommendedFocalLengthMm: Int,
    val isMoonRising: Boolean
) {
    enum class SkyLightingStage(val shortLabel: String, val advice: String) {
        BLUE_HOUR("🌟 블루아워", "하늘빛과 건축물 조명, 달 표면 질감이 가장 완벽하게 조화되는 골든타임"),
        GOLDEN_HOUR("🌅 골든아워", "노을빛 황금빛 하늘과 보름달의 극적인 대비 연출"),
        NAUTICAL_TWILIGHT("🌌 박명", "은은한 여명/박명 속 짙은 푸른빛 하늘"),
        NIGHT("🌙 야간", "깊은 밤 랜드마크 실루엣과 밝은 달의 강렬한 명암 대비 (HDR 추천)"),
        DAYTIME("☀️ 주간", "낮 하늘 속 은은한 달의 실루엣 관측")
    }

    enum class OpportunityGrade(val badgeText: String, val title: String) {
        S("🌟 Grade S", "🌟 Grade S (최고 추천)"),
        A("✨ Grade A", "✨ Grade A (우수)"),
        B("👍 Grade B", "👍 Grade B (양호)")
    }
}

enum class OpportunityFilter(val title: String) {
    ALL("전체"),
    GOLDEN_BLUE("🌟 골든/블루아워"),
    FULL_MOON("🌕 보름달 (80%+)")
}

enum class OpportunitySort(val title: String) {
    BY_SCORE("🏆 점수/추천순"),
    BY_DATE("📅 일자순")
}

/**
 * 달걸이(보름달-피사체 3점 1열 정렬) 골든타임 탐색 엔진
 */
object MoonAlignmentFinder {

    /**
     * 향후 N일(기본 60일) 동안 카메라와 피사체 조준선에 달이 일치하는 최적의 달걸이 촬영 기회를 탐색
     */
    fun findOpportunities(
        camera: GeoPoint,
        target: GeoPoint,
        targetHeightMeters: Double,
        startInstant: Instant = Instant.now(),
        days: Int = 60,
        toleranceDeg: Double = 0.40
    ): List<MoonAlignmentOpportunity> {
        val distance = AlignmentFinder.calculateDistanceMeters(camera, target)
        val targetBearing = AlignmentFinder.calculateBearingDeg(camera, target)
        val targetElevation = AlignmentFinder.calculateTargetElevationAngleDeg(
            camera = camera,
            target = target,
            targetAdditionalHeightMeters = targetHeightMeters / 2.0
        )

        val recFocalLength = when {
            distance >= 4000.0 -> 800
            distance >= 2000.0 -> 600
            distance >= 1000.0 -> 400
            else -> 200
        }

        val opportunities = mutableListOf<MoonAlignmentOpportunity>()
        val endInstant = startInstant.plus(Duration.ofDays(days.toLong()))
        var currentInstant = startInstant

        data class PassBest(
            val instant: Instant,
            val moon: MoonDetails,
            val sun: SunDetails,
            val offset: Double
        )

        var currentPassBest: PassBest? = null
        var inAlignmentWindow = false

        while (currentInstant.isBefore(endInstant)) {
            val moon = EphemerisEngine.calculateMoon(
                instant = currentInstant,
                latitudeDeg = camera.latitudeDeg,
                longitudeDeg = camera.longitudeDeg
            )

            val dAz = abs(moon.azimuthDeg - targetBearing)
            val dAzNorm = min(dAz, 360.0 - dAz)
            val cosEl = cos(Math.toRadians(targetElevation))
            val dAlt = abs(moon.altitudeDeg - targetElevation)
            val angularOffset = sqrt((dAzNorm * cosEl).pow(2.0) + dAlt.pow(2.0))

            if (angularOffset <= toleranceDeg && moon.altitudeDeg > -1.0) {
                inAlignmentWindow = true
                val sun = EphemerisEngine.calculateSun(
                    instant = currentInstant,
                    latitudeDeg = camera.latitudeDeg,
                    longitudeDeg = camera.longitudeDeg
                )

                if (currentPassBest == null || angularOffset < currentPassBest.offset) {
                    currentPassBest = PassBest(currentInstant, moon, sun, angularOffset)
                }
            } else {
                if (inAlignmentWindow && currentPassBest != null) {
                    val opp = evaluateOpportunity(
                        instant = currentPassBest.instant,
                        moon = currentPassBest.moon,
                        sun = currentPassBest.sun,
                        targetBearing = targetBearing,
                        targetElevation = targetElevation,
                        angularOffset = currentPassBest.offset,
                        distanceMeters = distance,
                        recommendedFocalLength = recFocalLength,
                        camera = camera
                    )
                    opportunities.add(opp)
                    currentPassBest = null
                    inAlignmentWindow = false
                }
            }

            val stepMinutes = if (dAzNorm > 4.0) 5L else 1L
            currentInstant = currentInstant.plus(Duration.ofMinutes(stepMinutes))
        }

        if (currentPassBest != null) {
            val opp = evaluateOpportunity(
                instant = currentPassBest.instant,
                moon = currentPassBest.moon,
                sun = currentPassBest.sun,
                targetBearing = targetBearing,
                targetElevation = targetElevation,
                angularOffset = currentPassBest.offset,
                distanceMeters = distance,
                recommendedFocalLength = recFocalLength,
                camera = camera
            )
            opportunities.add(opp)
        }

        return opportunities
    }

    private fun evaluateOpportunity(
        instant: Instant,
        moon: MoonDetails,
        sun: SunDetails,
        targetBearing: Double,
        targetElevation: Double,
        angularOffset: Double,
        distanceMeters: Double,
        recommendedFocalLength: Int,
        camera: GeoPoint
    ): MoonAlignmentOpportunity {
        val sunAlt = sun.altitudeDeg
        val skyStage = when {
            sunAlt in -8.0..-4.0 -> MoonAlignmentOpportunity.SkyLightingStage.BLUE_HOUR
            sunAlt > -4.0 && sunAlt <= 2.0 -> MoonAlignmentOpportunity.SkyLightingStage.GOLDEN_HOUR
            sunAlt in -14.0..-8.0 -> MoonAlignmentOpportunity.SkyLightingStage.NAUTICAL_TWILIGHT
            sunAlt < -14.0 -> MoonAlignmentOpportunity.SkyLightingStage.NIGHT
            else -> MoonAlignmentOpportunity.SkyLightingStage.DAYTIME
        }

        val illumScore = moon.illuminationFraction * 40.0

        val lightingBonus = when (skyStage) {
            MoonAlignmentOpportunity.SkyLightingStage.BLUE_HOUR -> 40.0
            MoonAlignmentOpportunity.SkyLightingStage.GOLDEN_HOUR -> 32.0
            MoonAlignmentOpportunity.SkyLightingStage.NAUTICAL_TWILIGHT -> 22.0
            MoonAlignmentOpportunity.SkyLightingStage.NIGHT -> 16.0
            MoonAlignmentOpportunity.SkyLightingStage.DAYTIME -> 5.0
        }

        val accBonus = 20.0 * max(0.0, 1.0 - (angularOffset / 0.40))
        val totalScore = round(illumScore + lightingBonus + accBonus).toInt().coerceIn(0, 100)

        val grade = when {
            totalScore >= 80 -> MoonAlignmentOpportunity.OpportunityGrade.S
            totalScore >= 60 -> MoonAlignmentOpportunity.OpportunityGrade.A
            else -> MoonAlignmentOpportunity.OpportunityGrade.B
        }

        val futureMoon = EphemerisEngine.calculateMoon(
            instant = instant.plus(Duration.ofMinutes(5)),
            latitudeDeg = camera.latitudeDeg,
            longitudeDeg = camera.longitudeDeg
        )
        val isRising = futureMoon.altitudeDeg > moon.altitudeDeg

        return MoonAlignmentOpportunity(
            timestamp = instant,
            moonDetails = moon,
            sunDetails = sun,
            targetBearingDeg = targetBearing,
            targetElevationDeg = targetElevation,
            angularOffsetDeg = angularOffset,
            distanceMeters = distanceMeters,
            skyStage = skyStage,
            score = totalScore,
            grade = grade,
            recommendedFocalLengthMm = recommendedFocalLength,
            isMoonRising = isRising
        )
    }
}
