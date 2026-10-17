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
        val angularOffsetDeg: Double, // 천체 중심과 피사체 중심 사이의 각거리 오차
        val isAligned: Boolean,       // 오차가 허용 오차 이내인가
        val targetType: String = "MOON"
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
        val targetElevation = calculateTargetElevationAngleDeg(camera, target, targetHeightMeters / 2.0)

        val results = mutableListOf<AlignmentEvent>()
        var current = startInstant
        val end = startInstant.plus(duration)

        var lastEvent: AlignmentEvent? = null

        while (current.isBefore(end)) {
            val moon = EphemerisEngine.calculateMoon(current, camera.latitudeDeg, camera.longitudeDeg)

            val dAz = abs(moon.azimuthDeg - targetBearing)
            val dAzNorm = min(dAz, 360.0 - dAz)
            val dAlt = abs(moon.altitudeDeg - targetElevation)
            val totalOffsetDeg = sqrt(dAzNorm * dAzNorm + dAlt * dAlt)

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
                    isAligned = true,
                    targetType = "MOON"
                )
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

            val stepMinutes = if (dAzNorm > 4.0) 2L else 1L
            current = current.plus(Duration.ofMinutes(stepMinutes))
        }

        return results
    }

    /**
     * 특정 시간 범위 동안 태양과 피사체가 일직선으로 정렬되는 순간(일출/일몰 관통, 주간 첨탑 정렬 등)을 분 단위로 탐색
     */
    fun findSunAlignments(
        camera: GeoPoint,
        target: GeoPoint,
        targetHeightMeters: Double,
        startInstant: Instant,
        duration: Duration = Duration.ofDays(7),
        toleranceDeg: Double = 0.5
    ): List<AlignmentEvent> {
        val targetBearing = calculateBearingDeg(camera, target)
        val targetElevation = calculateTargetElevationAngleDeg(camera, target, targetHeightMeters / 2.0)

        val results = mutableListOf<AlignmentEvent>()
        var current = startInstant
        val end = startInstant.plus(duration)

        var lastEvent: AlignmentEvent? = null

        while (current.isBefore(end)) {
            val sun = EphemerisEngine.calculateSun(current, camera.latitudeDeg, camera.longitudeDeg)

            val dAz = abs(sun.azimuthDeg - targetBearing)
            val dAzNorm = min(dAz, 360.0 - dAz)
            val dAlt = abs(sun.altitudeDeg - targetElevation)
            val totalOffsetDeg = sqrt(dAzNorm * dAzNorm + dAlt * dAlt)

            if (totalOffsetDeg <= toleranceDeg && sun.altitudeDeg > -1.0) {
                val dummyMoon = MoonDetails(
                    azimuthDeg = sun.azimuthDeg,
                    altitudeDeg = sun.altitudeDeg,
                    distanceKm = 149600000.0,
                    angularDiameterDeg = 0.53,
                    phase = com.moonaligner.app.core.astronomy.MoonPhase.FULL_MOON,
                    illuminationFraction = 1.0,
                    ageDays = 14.8
                )
                val event = AlignmentEvent(
                    timestamp = current,
                    moonDetails = dummyMoon,
                    angularOffsetDeg = totalOffsetDeg,
                    isAligned = true,
                    targetType = "SUN"
                )
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
     * 현재 카메라와 피사체 조준선(방위각)에 천체(달 또는 태양)가 1열로 통과하는 가장 가까운 시각(분 단위) 탐색
     */
    fun findNextAzimuthAlignment(
        camera: GeoPoint,
        target: GeoPoint,
        startInstant: Instant,
        duration: Duration = Duration.ofHours(48),
        isSun: Boolean = false
    ): AzimuthAlignmentMatch? {
        val targetBearing = calculateBearingDeg(camera, target)
        var bestInstant: Instant? = null
        var bestMoon: MoonDetails? = null
        var minOffset = 999.0

        var current = startInstant
        val end = startInstant.plus(duration)

        while (current.isBefore(end)) {
            val (azimuth, altitude, details) = if (isSun) {
                val s = EphemerisEngine.calculateSun(current, camera.latitudeDeg, camera.longitudeDeg)
                val dummyM = MoonDetails(
                    azimuthDeg = s.azimuthDeg,
                    altitudeDeg = s.altitudeDeg,
                    distanceKm = 149600000.0,
                    angularDiameterDeg = 0.53,
                    phase = com.moonaligner.app.core.astronomy.MoonPhase.FULL_MOON,
                    illuminationFraction = 1.0,
                    ageDays = 14.8
                )
                Triple(s.azimuthDeg, s.altitudeDeg, dummyM)
            } else {
                val m = EphemerisEngine.calculateMoon(current, camera.latitudeDeg, camera.longitudeDeg)
                Triple(m.azimuthDeg, m.altitudeDeg, m)
            }

            val dAz = abs(azimuth - targetBearing)
            val dAzNorm = min(dAz, 360.0 - dAz)

            if (dAzNorm < 0.5 && altitude > -3.0) {
                return AzimuthAlignmentMatch(current, details, dAzNorm)
            }

            if (dAzNorm < minOffset) {
                minOffset = dAzNorm
                bestInstant = current
                bestMoon = details
            }
            current = current.plus(Duration.ofMinutes(1))
        }

        return if (bestInstant != null && bestMoon != null && minOffset < 2.5) {
            AzimuthAlignmentMatch(bestInstant, bestMoon, minOffset)
        } else null
    }
}
