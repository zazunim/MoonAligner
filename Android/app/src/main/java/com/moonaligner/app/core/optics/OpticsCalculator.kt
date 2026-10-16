package com.moonaligner.app.core.optics

import kotlin.math.atan
import kotlin.math.tan

/**
 * 초망원 배경 압축 효과 및 달-피사체 정렬 계산기
 */
object OpticsCalculator {

    /** 평균 달의 시직경 (각도, 약 31.02 분각) */
    const val DEFAULT_MOON_ANGULAR_DIAMETER_DEG = 0.5181

    /** 근지점(수퍼문) 시직경 */
    const val SUPERMOON_ANGULAR_DIAMETER_DEG = 0.558

    /** 원지점(미니문) 시직경 */
    const val MICROMOON_ANGULAR_DIAMETER_DEG = 0.490

    /**
     * 피사체와 카메라 간의 필요 촬영 거리(Distance in meters) 계산
     *
     * @param subjectHeightMeters 피사체의 실제 높이 또는 너비 (단위: 미터)
     * @param scaleRatioToMoon 피사체 크기 대비 달의 크기 비율 (1.0이면 피사체와 달이 같은 높이, 0.5이면 달이 2배 큼)
     * @param moonAngularDiameterDeg 달의 시직경 (기본값: 평균 0.5181도)
     * @return 카메라와 피사체 사이의 물리적 거리 (단위: 미터)
     */
    fun calculateRequiredDistance(
        subjectHeightMeters: Double,
        scaleRatioToMoon: Double = 1.0,
        moonAngularDiameterDeg: Double = DEFAULT_MOON_ANGULAR_DIAMETER_DEG
    ): Double {
        require(subjectHeightMeters > 0) { "피사체 높이는 0보다 커야 합니다." }
        require(scaleRatioToMoon > 0) { "크기 비율은 0보다 커야 합니다." }

        // 목표 피사체 시직경 (라디안)
        val targetAngularDiameterRad = Math.toRadians(moonAngularDiameterDeg * scaleRatioToMoon)

        // 거리 공식: D = H / (2 * tan(theta / 2))
        return subjectHeightMeters / (2.0 * tan(targetAngularDiameterRad / 2.0))
    }

    /**
     * 특정 렌즈 초점거리에서 센서 상에 맺히는 달의 상 크기(mm)
     *
     * @param focalLengthMm 렌즈 초점거리 (mm)
     * @param moonAngularDiameterDeg 달의 시직경 (기본값: 0.5181도)
     * @return 센서에 맺히는 달의 지름 (mm)
     */
    fun calculateMoonImageDiameterOnSensorMm(
        focalLengthMm: Double,
        moonAngularDiameterDeg: Double = DEFAULT_MOON_ANGULAR_DIAMETER_DEG
    ): Double {
        val angleRad = Math.toRadians(moonAngularDiameterDeg)
        return 2.0 * focalLengthMm * tan(angleRad / 2.0)
    }

    /**
     * 특정 거리의 피사체가 센서 상에 맺히는 상 크기(mm)
     *
     * @param focalLengthMm 렌즈 초점거리 (mm)
     * @param distanceMeters 촬영 거리 (m)
     * @param subjectHeightMeters 피사체 실제 높이 (m)
     * @return 센서에 맺히는 피사체의 높이 (mm)
     */
    fun calculateSubjectImageHeightOnSensorMm(
        focalLengthMm: Double,
        distanceMeters: Double,
        subjectHeightMeters: Double
    ): Double {
        require(distanceMeters > 0) { "촬영 거리는 0보다 커야 합니다." }
        val distanceMm = distanceMeters * 1000.0
        val subjectHeightMm = subjectHeightMeters * 1000.0
        // 박막 렌즈 상 공식: h_sensor = H_real * f / D
        return (subjectHeightMm * focalLengthMm) / distanceMm
    }

    /**
     * 달이 센서 세로 높이의 일정 비율(0.0 ~ 1.0)을 채우기 위해 필요한 렌즈 초점거리(mm) 계산
     *
     * @param sensor 카메라 센서 규격
     * @param coverageFraction 세로 프레임 점유율 (예: 0.25 = 화면 세로의 25%)
     * @param moonAngularDiameterDeg 달의 시직경
     * @return 필요한 렌즈 초점거리 (mm)
     */
    fun calculateFocalLengthForMoonCoverage(
        sensor: CameraSensor,
        coverageFraction: Double,
        moonAngularDiameterDeg: Double = DEFAULT_MOON_ANGULAR_DIAMETER_DEG
    ): Double {
        require(coverageFraction in 0.01..1.0) { "점유율은 1% ~ 100% 사이여야 합니다." }
        val targetImageHeightMm = sensor.heightMm * coverageFraction
        val angleRad = Math.toRadians(moonAngularDiameterDeg)
        return targetImageHeightMm / (2.0 * tan(angleRad / 2.0))
    }

    /**
     * 렌즈와 센서에 따른 수평/수직/대각선 화각(FOV, 단위: 도)
     */
    data class FieldOfView(
        val horizontalDeg: Double,
        val verticalDeg: Double,
        val diagonalDeg: Double
    )

    fun calculateFieldOfView(
        sensor: CameraSensor,
        focalLengthMm: Double
    ): FieldOfView {
        require(focalLengthMm > 0) { "초점거리는 0보다 커야 합니다." }
        val hFov = 2.0 * Math.toDegrees(atan(sensor.widthMm / (2.0 * focalLengthMm)))
        val vFov = 2.0 * Math.toDegrees(atan(sensor.heightMm / (2.0 * focalLengthMm)))
        val dFov = 2.0 * Math.toDegrees(atan(sensor.diagonalMm / (2.0 * focalLengthMm)))
        return FieldOfView(hFov, vFov, dFov)
    }
}
