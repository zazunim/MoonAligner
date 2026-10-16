package com.moonaligner.app

import com.moonaligner.app.core.optics.CameraSensor
import com.moonaligner.app.core.optics.OpticsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticsCalculatorTest {

    @Test
    fun testPersonFullMoonDistance() {
        // 키 1.8m 인물이 보름달과 정확히 같은 크기(k = 1.0)로 보이려면
        val height = 1.8
        val distance = OpticsCalculator.calculateRequiredDistance(height, scaleRatioToMoon = 1.0)

        // D ≈ 110.5 * 1.8 ≈ 198.9m ~ 200m
        assertTrue("거리가 약 195m ~ 205m 범위 내여야 함 (실제: $distance m)", distance in 195.0..205.0)
    }

    @Test
    fun testTowerFullMoonDistance() {
        // 높이 25m 타워가 보름달과 같은 크기(k = 1.0)로 보이려면
        val height = 25.0
        val distance = OpticsCalculator.calculateRequiredDistance(height, scaleRatioToMoon = 1.0)

        // D ≈ 110.5 * 25 ≈ 2762m (약 2.76km)
        assertTrue("거리가 약 2.7km ~ 2.85km 범위 내여야 함 (실제: $distance m)", distance in 2700.0..2850.0)
    }

    @Test
    fun testMoonSizeOnFullFrameSensor() {
        // 600mm 초망원 렌즈에서 센서 상의 달 지름
        val focalLength = 600.0
        val moonDiameterMm = OpticsCalculator.calculateMoonImageDiameterOnSensorMm(focalLength)

        // 600 * tan(0.518도) ≈ 5.42 mm
        assertTrue("달의 상 지름이 약 5.1mm ~ 5.7mm 사이여야 함 (실제: $moonDiameterMm mm)", moonDiameterMm in 5.1..5.7)

        // 풀프레임 세로(24mm) 대비 약 22.5%를 차지함
        val coverage = moonDiameterMm / CameraSensor.FULL_FRAME.heightMm
        assertTrue("세로 프레임 점유율이 약 22% 내외여야 함 (실제: ${coverage * 100}%)", coverage in 0.20..0.25)
    }

    @Test
    fun testRequiredFocalLengthForFiftyPercentCoverage() {
        // 풀프레임 센서 세로 50%를 달로 채우려면
        val requiredFocalLength = OpticsCalculator.calculateFocalLengthForMoonCoverage(
            CameraSensor.FULL_FRAME,
            coverageFraction = 0.50
        )

        // 약 1300mm ~ 1350mm (예: 600mm + 2x 텔레컨버터 = 1200mm 수준)
        assertTrue("필요 초점거리가 약 1300mm대여야 함 (실제: $requiredFocalLength mm)", requiredFocalLength in 1250.0..1400.0)
    }
}
