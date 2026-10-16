package com.moonaligner.app.core.shadow

import com.moonaligner.app.core.alignment.AlignmentFinder
import com.moonaligner.app.core.alignment.GeoPoint
import kotlin.math.tan

/**
 * SunOnTrack 스타일의 실시간 그림자 시뮬레이션 엔진
 * 태양의 실시간 고도각과 방위각, 그리고 건물의 높이를 기반으로
 * 지표면에 생기는 그림자의 방향, 길이 및 도달 좌표를 정밀 계산합니다.
 */
data class ShadowCastResult(
    val objectHeightMeters: Double,
    val shadowLengthMeters: Double,
    val shadowBearingDeg: Double,
    val shadowRatio: Double,         // 건물 높이 대비 그림자 배율 (L / H)
    val isSunAboveHorizon: Boolean,
    val shadowTipPoint: GeoPoint?
)

object ShadowSimulator {

    /**
     * 특정 시각/위치에서의 건물/피사체 그림자 계산
     *
     * @param sunAltitudeDeg 태양 고도각 (단위: 도)
     * @param sunAzimuthDeg 태양 방위각 (단위: 도, 0=북, 90=동, 180=남, 270=서)
     * @param objectHeightMeters 건물 또는 피사체의 높이 (단위: 미터)
     * @param objectLocation 피사체 위치 (GeoPoint)
     * @param maxShadowBoundMeters 지도 렌더링 상 최대 그림자 허용 길이 (기본: 2,000m)
     */
    fun calculateShadow(
        sunAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        objectHeightMeters: Double,
        objectLocation: GeoPoint,
        maxShadowBoundMeters: Double = 2000.0
    ): ShadowCastResult {
        // 태양 방위각의 정반대(180도 반대편)로 그림자가 투영됨
        val shadowBearing = (sunAzimuthDeg + 180.0) % 360.0

        if (sunAltitudeDeg <= 0.1 || objectHeightMeters <= 0.0) {
            // 태양이 지평선 아래이거나 높이가 0인 경우 그림자 없음
            return ShadowCastResult(
                objectHeightMeters = objectHeightMeters,
                shadowLengthMeters = 0.0,
                shadowBearingDeg = shadowBearing,
                shadowRatio = 0.0,
                isSunAboveHorizon = false,
                shadowTipPoint = null
            )
        }

        val altRad = Math.toRadians(sunAltitudeDeg)
        val rawLength = objectHeightMeters / tan(altRad)
        val clampedLength = rawLength.coerceAtMost(maxShadowBoundMeters)
        val ratio = 1.0 / tan(altRad)

        val tipPoint = AlignmentFinder.calculateDestinationPoint(
            start = objectLocation,
            distanceMeters = clampedLength,
            bearingDeg = shadowBearing
        )

        return ShadowCastResult(
            objectHeightMeters = objectHeightMeters,
            shadowLengthMeters = rawLength,
            shadowBearingDeg = shadowBearing,
            shadowRatio = ratio,
            isSunAboveHorizon = true,
            shadowTipPoint = tipPoint
        )
    }
}
