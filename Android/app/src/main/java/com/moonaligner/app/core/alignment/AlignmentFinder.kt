package com.moonaligner.app.core.alignment

import com.moonaligner.app.core.astronomy.EphemerisEngine
import com.moonaligner.app.core.astronomy.MoonDetails
import java.time.Duration
import java.time.Instant
import kotlin.math.*

/**
 * 카메라 지점과 피사체 지점 사이의 지형 기하학 및 달 정렬(Alignment) 이벤트 탐색기
 */
object AlignmentFinder {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * 두 지점 간의 대원 거리 (하버사인 공식, 단위: 미터)
     */
    fun calculateDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1Rad = Math.toRadians(p1.latitudeDeg)
        val lat2Rad = Math.toRadians(p2.latitudeDeg)
        val dLat = Math.toRadians(p2.latitudeDeg - p1.latitudeDeg)
        val dLon = Math.toRadians(p2.longitudeDeg - p1.longitudeDeg)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * p1(카메라)에서 p2(피사체)를 바라보는 방위각 (단위: 도, 0~360)
     */
    fun calculateBearingDeg(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1Rad = Math.toRadians(p1.latitudeDeg)
        val lat2Rad = Math.toRadians(p2.latitudeDeg)
        val dLon = Math.toRadians(p2.longitudeDeg - p1.longitudeDeg)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearingRad = atan2(y, x)
        val bearingDeg = Math.toDegrees(bearingRad)
        return (bearingDeg + 360.0) % 360.0
    }

    /**
     * 특정 지점에서 방위각(도)과 거리(미터)만큼 이동했을 때의 좌표 계산 (Great-circle forward calculation)
     */
    fun calculateDestinationPoint(start: GeoPoint, distanceMeters: Double, bearingDeg: Double): GeoPoint {
        val lat1Rad = Math.toRadians(start.latitudeDeg)
        val lon1Rad = Math.toRadians(start.longitudeDeg)
        val bearingRad = Math.toRadians(bearingDeg)
        val angularDist = distanceMeters / EARTH_RADIUS_METERS

        val lat2Rad = asin(
            sin(lat1Rad) * cos(angularDist) +
                    cos(lat1Rad) * sin(angularDist) * cos(bearingRad)
        )
        val lon2Rad = lon1Rad + atan2(
            sin(bearingRad) * sin(angularDist) * cos(lat1Rad),
            cos(angularDist) - sin(lat1Rad) * sin(lat2Rad)
        )

        return GeoPoint(
            latitudeDeg = Math.toDegrees(lat2Rad),
            longitudeDeg = Math.toDegrees(lon2Rad),
            altitudeMeters = start.altitudeMeters
        )
    }

    /**
     * p1(카메라)에서 p2(피사체 상단)을 바라보는 겉보기 앙각 (Elevation Angle, 단위: 도)
     * 지구 곡률(Earth Curvature)에 의한 높이 감쇄 반영
     */
    fun calculateTargetElevationAngleDeg(
        camera: GeoPoint,
        target: GeoPoint,
        targetAdditionalHeightMeters: Double = 0.0
    ): Double {
        val distance = calculateDistanceMeters(camera, target)
        if (distance < 1.0) return 0.0

        // 지구 곡률에 따른 지평선 아래로 가라앉는 높이: drop = d^2 / (2 * R)
        val curvatureDropMeters = (distance * distance) / (2.0 * EARTH_RADIUS_METERS)
        val targetEffectiveAltitude = (target.altitudeMeters + targetAdditionalHeightMeters) - curvatureDropMeters
        val deltaHeight = targetEffectiveAltitude - camera.altitudeMeters

        val angleRad = atan(deltaHeight / distance)
        return Math.toDegrees(angleRad)
    }

    /**
     * 정렬 이벤트 결과 모델
     */
    data class AlignmentEvent(
        val timestamp: Instant,
        val moonDetails: MoonDetails,
        val angularOffsetDeg: Double, // 달 중심과 피사체 중심 사이의 각거리 오차
        val isAligned: Boolean        // 오차가 달 반지름 이내인가
    )

    /**
     * 달 위상 필터 종류
     */
    enum class MoonPhaseFilter(val displayName: String) {
        ALL("전체 위상"),
        FULL_MOON("🌕 보름달 (75%+)"),
        HALF_MOON("🌓 상현/하현 (35~75%)"),
        CRESCENT("🌙 초승/그믐 (35% 미만)")
    }

    /**
     * 특정 시간 범위 동안 달과 피사체가 일직선으로 정렬되는 순간들을 분 단위로 탐색
     *
     * @param camera 카메라 좌표
     * @param target 피사체 좌표
     * @param targetHeightMeters 피사체 자체 높이
     * @param startInstant 탐색 시작 시각
     * @param duration 탐색 지속 시간 (예: 3일, 7일, 30일, 90일)
     * @param toleranceDeg 허용 오차 각도 (기본: 약 0.4도)
     * @param phaseFilter 달의 위상 필터
     */
    fun findMoonAlignments(
        camera: GeoPoint,
        target: GeoPoint,
        targetHeightMeters: Double,
        startInstant: Instant,
        duration: Duration = Duration.ofDays(7),
        toleranceDeg: Double = 0.4,
        phaseFilter: MoonPhaseFilter = MoonPhaseFilter.ALL
    ): List<AlignmentEvent> {
        val targetBearing = calculateBearingDeg(camera, target)
        // 피사체의 정중앙 높이를 타겟으로 삼음
        val targetElevation = calculateTargetElevationAngleDeg(camera, target, targetHeightMeters / 2.0)

        val results = mutableListOf<AlignmentEvent>()
        var current = startInstant
        val end = startInstant.plus(duration)

        var lastEvent: AlignmentEvent? = null

        while (current.isBefore(end)) {
            val moon = EphemerisEngine.calculateMoon(current, camera.latitudeDeg, camera.longitudeDeg)

            // 달의 방위각 차이와 고도각 차이
            val dAz = abs(moon.azimuthDeg - targetBearing)
            val dAzNorm = min(dAz, 360.0 - dAz)
            val dAlt = abs(moon.altitudeDeg - targetElevation)

            // 구면 삼각법에 의한 총 각거리 (Angular distance)
            val totalOffsetDeg = sqrt(dAzNorm * dAzNorm + dAlt * dAlt)

            // 위상 필터 검사
            val matchesPhase = when (phaseFilter) {
                MoonPhaseFilter.ALL -> true
                MoonPhaseFilter.FULL_MOON -> moon.illuminationFraction >= 0.75
                MoonPhaseFilter.HALF_MOON -> moon.illuminationFraction in 0.35..0.75
                MoonPhaseFilter.CRESCENT -> moon.illuminationFraction < 0.35
            }

            if (matchesPhase && totalOffsetDeg <= toleranceDeg && moon.altitudeDeg > -1.0) {
                val event = AlignmentEvent(
                    timestamp = current,
                    moonDetails = moon,
                    angularOffsetDeg = totalOffsetDeg,
                    isAligned = true
                )
                // 연속된 분 중 오차가 가장 작은 극소점(최적 순간)을 선별
                if (lastEvent == null || totalOffsetDeg < lastEvent.angularOffsetDeg) {
                    if (lastEvent != null && Duration.between(lastEvent.timestamp, current).toMinutes() < 10) {
                        results.removeAt(results.lastIndex)
                    }
                    results.add(event)
                    lastEvent = event
                }
            } else {
                lastEvent = null
            }

            // 방위각 차이가 4도 이상으로 멀면 2분 단위 가속, 정렬 인접 구간에서는 1분 단위 초정밀 탐색
            val stepMinutes = if (dAzNorm > 4.0) 2L else 1L
            current = current.plus(Duration.ofMinutes(stepMinutes))
        }

        return results
    }

    data class AzimuthAlignmentMatch(
        val timestamp: Instant,
        val moonDetails: MoonDetails,
        val offsetDeg: Double
    )

    /**
     * 현재 카메라와 피사체 조준선(방위각)에 달이 1열로 통과하는 가장 가까운 시각(분 단위) 탐색
     */
    fun findNextAzimuthAlignment(
        camera: GeoPoint,
        target: GeoPoint,
        startInstant: Instant,
        duration: Duration = Duration.ofHours(48)
    ): AzimuthAlignmentMatch? {
        val targetBearing = calculateBearingDeg(camera, target)
        var bestInstant: Instant? = null
        var bestMoon: MoonDetails? = null
        var minOffset = 999.0

        var current = startInstant
        val end = startInstant.plus(duration)

        while (current.isBefore(end)) {
            val moon = EphemerisEngine.calculateMoon(current, camera.latitudeDeg, camera.longitudeDeg)
            val dAz = abs(moon.azimuthDeg - targetBearing)
            val dAzNorm = min(dAz, 360.0 - dAz)

            if (dAzNorm < 0.5 && moon.altitudeDeg > -3.0) {
                return AzimuthAlignmentMatch(current, moon, dAzNorm)
            }

            if (dAzNorm < minOffset) {
                minOffset = dAzNorm
                bestInstant = current
                bestMoon = moon
            }
            current = current.plus(Duration.ofMinutes(1))
        }

        return if (bestInstant != null && bestMoon != null && minOffset < 2.5) {
            AzimuthAlignmentMatch(bestInstant, bestMoon, minOffset)
        } else null
    }
}
