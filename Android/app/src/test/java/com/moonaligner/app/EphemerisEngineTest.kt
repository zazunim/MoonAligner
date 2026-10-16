package com.moonaligner.app

import com.moonaligner.app.core.alignment.AlignmentFinder
import com.moonaligner.app.core.alignment.GeoPoint
import com.moonaligner.app.core.astronomy.EphemerisEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EphemerisEngineTest {

    @Test
    fun testSeoulMoonPosition() {
        // 서울 시청 좌표: 37.5665° N, 126.9780° E
        val lat = 37.5665
        val lon = 126.9780
        val now = Instant.now()

        val moon = EphemerisEngine.calculateMoon(now, lat, lon)

        // 방위각은 0도 ~ 360도
        assertTrue("방위각 유효 범위: ${moon.azimuthDeg}", moon.azimuthDeg in 0.0..360.0)

        // 고도각은 -90도 ~ +90도
        assertTrue("고도각 유효 범위: ${moon.altitudeDeg}", moon.altitudeDeg in -90.0..90.0)

        // 달의 거리는 약 356,000km ~ 406,700km
        assertTrue("달 거리 유효 범위: ${moon.distanceKm} km", moon.distanceKm in 350000.0..410000.0)

        // 달 시직경은 약 0.49도 ~ 0.56도
        assertTrue("달 시직경 유효 범위: ${moon.angularDiameterDeg}도", moon.angularDiameterDeg in 0.48..0.57)

        // 조도율은 0.0 ~ 1.0
        assertTrue("조도율 유효 범위: ${moon.illuminationFraction}", moon.illuminationFraction in 0.0..1.0)
    }

    @Test
    fun testBearingAndDistanceCalculation() {
        // 카메라: 한강 노들섬 부근 (37.5172, 126.9582)
        // 피사체: 남산 N서울타워 (37.5512, 126.9882, 해발 479m)
        val camera = GeoPoint(37.5172, 126.9582, altitudeMeters = 15.0)
        val tower = GeoPoint(37.5512, 126.9882, altitudeMeters = 479.0)

        val distance = AlignmentFinder.calculateDistanceMeters(camera, tower)
        // 노들섬에서 남산타워 직선 거리는 약 4.5km ~ 4.8km
        assertTrue("노들섬-남산타워 거리가 약 4.5km ~ 5.0km여야 함 (실제: ${distance / 1000} km)", distance in 4400.0..5100.0)

        val bearing = AlignmentFinder.calculateBearingDeg(camera, tower)
        // 노들섬에서 남산타워는 북동쪽(약 30도 ~ 50도 방향)
        assertTrue("노들섬에서 남산타워 방위각은 북동쪽 약 30°~50° (실제: $bearing°)", bearing in 25.0..55.0)

        val elevation = AlignmentFinder.calculateTargetElevationAngleDeg(camera, tower)
        // 표고차 약 464m, 거리 약 4.7km -> 앙각 약 5도 ~ 6도
        assertTrue("남산타워를 올려다보는 앙각이 약 4.5°~7.0° (실제: $elevation°)", elevation in 4.5..7.0)
    }
}
