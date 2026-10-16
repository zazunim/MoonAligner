package com.moonaligner.app.core.astronomy

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * 고정밀 천체 역학 엔진 (Jean Meeus Astronomical Algorithms 간략화 고정밀 모델)
 * 태양과 달의 실시간 방위각, 고도각, 거리, 시직경 및 위상을 계산합니다.
 */
object EphemerisEngine {

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI
    private const val MOON_RADIUS_KM = 1737.4

    /**
     * 특정 시각 및 GPS 위치에서의 달의 상세 정보 계산
     *
     * @param instant 관측 시각 (UTC 기준 Instant)
     * @param latitudeDeg 관측자 위도 (북위 +, 남위 -)
     * @param longitudeDeg 관측자 경도 (동경 +, 서경 -)
     */
    fun calculateMoon(
        instant: Instant,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): MoonDetails {
        val jd = toJulianDate(instant)
        val t = (jd - 2451545.0) / 36525.0

        // 달의 기본 궤도 요소 (도 단위)
        val lPrime = normalizeDeg(218.3164477 + 481267.88128 * t) // 달의 평균 황경
        val d = normalizeDeg(297.8501921 + 445267.1114034 * t) // 평균 이각 (달-태양)
        val m = normalizeDeg(357.5291092 + 35999.0502909 * t) // 태양의 평균 근점이각
        val mPrime = normalizeDeg(134.9633964 + 477198.8675055 * t) // 달의 평균 근점이각
        val f = normalizeDeg(93.2720950 + 483202.0175233 * t) // 달의 승구와의 거리

        val dRad = d * DEG_TO_RAD
        val mRad = m * DEG_TO_RAD
        val mPrimeRad = mPrime * DEG_TO_RAD
        val fRad = f * DEG_TO_RAD

        // 달의 지심 황경 (Ecliptic Longitude) 보정치
        val lambda = lPrime +
                6.288774 * sin(mPrimeRad) +
                1.274027 * sin(2.0 * dRad - mPrimeRad) +
                0.658314 * sin(2.0 * dRad) +
                0.213618 * sin(2.0 * mPrimeRad) -
                0.185116 * sin(mRad) -
                0.114332 * sin(2.0 * fRad)

        // 달의 지심 황위 (Ecliptic Latitude) 보정치
        val beta = 5.128178 * sin(fRad) +
                0.280602 * sin(mPrimeRad + fRad) +
                0.277693 * sin(mPrimeRad - fRad) +
                0.173237 * sin(2.0 * dRad - fRad)

        // 달-지구 거리 (km)
        val distanceKm = 385000.56 -
                20905.355 * cos(mPrimeRad) -
                3699.111 * cos(2.0 * dRad - mPrimeRad) -
                2955.968 * cos(2.0 * dRad) -
                569.925 * cos(2.0 * mPrimeRad)

        // 황도 좌표 -> 적도 좌표 변환 (황도 경사각 epsilon)
        val eps = (23.439291 - 0.0130042 * t) * DEG_TO_RAD
        val lambdaRad = lambda * DEG_TO_RAD
        val betaRad = beta * DEG_TO_RAD

        val sinDec = sin(betaRad) * cos(eps) + cos(betaRad) * sin(eps) * sin(lambdaRad)
        val dec = asin(sinDec) // 적위 (라디안)

        val yRa = sin(lambdaRad) * cos(eps) - tan(betaRad) * sin(eps)
        val xRa = cos(lambdaRad)
        val ra = atan2(yRa, xRa) // 적경 (라디안)

        // 관측지 수평 좌표계(방위각, 고도각) 변환
        val (azimuth, rawAltitude) = equatorialToHorizontal(ra, dec, jd, latitudeDeg, longitudeDeg)
        val correctedAltitude = applyAtmosphericRefraction(rawAltitude)

        // 시직경 (도 단위): angularDiameter = 2 * arcsin(R_moon / distance)
        val angularDiameterDeg = 2.0 * asin(MOON_RADIUS_KM / distanceKm) * RAD_TO_DEG

        // 위상각 및 위상 종류
        val phaseAngleDeg = normalizeDeg(d)
        val phase = MoonPhase.fromPhaseAngle(phaseAngleDeg)
        val illuminationFraction = (1.0 - cos(phaseAngleDeg * DEG_TO_RAD)) / 2.0
        val ageDays = (phaseAngleDeg / 360.0) * 29.530588853

        return MoonDetails(
            azimuthDeg = azimuth,
            altitudeDeg = correctedAltitude,
            distanceKm = distanceKm,
            angularDiameterDeg = angularDiameterDeg,
            phase = phase,
            illuminationFraction = illuminationFraction,
            ageDays = ageDays
        )
    }

    /**
     * 특정 시각 및 GPS 위치에서의 태양의 상세 정보 계산
     */
    fun calculateSun(
        instant: Instant,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): SunDetails {
        val jd = toJulianDate(instant)
        val t = (jd - 2451545.0) / 36525.0

        val l0 = normalizeDeg(280.46646 + 36000.76983 * t)
        val m = normalizeDeg(357.52911 + 35999.05029 * t) * DEG_TO_RAD
        val c = (1.914602 - 0.004817 * t) * sin(m) + (0.019993 - 0.000101 * t) * sin(2.0 * m)
        val trueLong = (l0 + c) * DEG_TO_RAD

        val eps = (23.439291 - 0.0130042 * t) * DEG_TO_RAD
        val sinDec = sin(eps) * sin(trueLong)
        val dec = asin(sinDec)
        val ra = atan2(cos(eps) * sin(trueLong), cos(trueLong))

        val (azimuth, rawAltitude) = equatorialToHorizontal(ra, dec, jd, latitudeDeg, longitudeDeg)
        val correctedAltitude = applyAtmosphericRefraction(rawAltitude)

        val isGolden = correctedAltitude in -4.0..6.0
        val isBlue = correctedAltitude in -6.0..-4.0

        return SunDetails(
            azimuthDeg = azimuth,
            altitudeDeg = correctedAltitude,
            isGoldenHour = isGolden,
            isBlueHour = isBlue
        )
    }

    private fun toJulianDate(instant: Instant): Double {
        val epochMilli = instant.toEpochMilli()
        return 2440587.5 + (epochMilli / 86400000.0)
    }

    private fun equatorialToHorizontal(
        raRad: Double,
        decRad: Double,
        jd: Double,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): Pair<Double, Double> {
        val latRad = latitudeDeg * DEG_TO_RAD

        // 그리니치 평균 항성시 (GMST, 라디안)
        val t = (jd - 2451545.0) / 36525.0
        var gmstDeg = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
                0.000387933 * t * t - (t * t * t) / 38710000.0
        gmstDeg = normalizeDeg(gmstDeg)

        // 지방 항성시 (LST, 라디안)
        val lstRad = normalizeDeg(gmstDeg + longitudeDeg) * DEG_TO_RAD

        // 시각각 (Hour Angle, 라디안)
        val haRad = lstRad - raRad

        // 고도각(Altitude) 계산
        val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
        val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))

        // 방위각(Azimuth) 계산 (북쪽 0도, 동쪽 90도 기준)
        val cosAz = (sin(decRad) - sin(latRad) * sin(altRad)) / (cos(latRad) * cos(altRad))
        val sinAz = -cos(decRad) * sin(haRad) / cos(altRad)
        var azDeg = atan2(sinAz, cosAz) * RAD_TO_DEG
        azDeg = normalizeDeg(azDeg)

        return Pair(azDeg, altRad * RAD_TO_DEG)
    }

    /**
     * 대기 굴절(Atmospheric Refraction) 보정 공식
     * 지평선 근처에서 천체가 대기에 의해 실제보다 더 높이 떠 보이는 현상 보정 (Bennett 1982 공식)
     */
    private fun applyAtmosphericRefraction(trueAltitudeDeg: Double): Double {
        if (trueAltitudeDeg < -5.0) return trueAltitudeDeg
        val h = max(-2.0, trueAltitudeDeg)
        val rArcmin = 1.02 / tan((h + 10.3 / (h + 5.11)) * DEG_TO_RAD)
        return trueAltitudeDeg + (rArcmin / 60.0)
    }

    private fun normalizeDeg(deg: Double): Double {
        val res = deg % 360.0
        return if (res < 0) res + 360.0 else res
    }

    /**
     * 특정 날짜의 일출/일몰 시각, 방위각, 남중고도 및 총 일조시간 정밀 시뮬레이션
     */
    fun calculateSunDayTimes(
        date: LocalDate,
        zoneId: ZoneId,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): SunDayTimes {
        var sunriseTime: LocalTime? = null
        var sunriseAz = 90.0
        var sunsetTime: LocalTime? = null
        var sunsetAz = 270.0
        var maxAlt = -90.0

        var prevAlt = -90.0
        for (minute in 0 until 1440 step 2) {
            val time = LocalTime.of(minute / 60, minute % 60)
            val instant = ZonedDateTime.of(date, time, zoneId).toInstant()
            val sun = calculateSun(instant, latitudeDeg, longitudeDeg)

            if (sun.altitudeDeg > maxAlt) {
                maxAlt = sun.altitudeDeg
            }

            if (prevAlt <= -0.833 && sun.altitudeDeg > -0.833 && sunriseTime == null) {
                sunriseTime = time
                sunriseAz = sun.azimuthDeg
            }

            if (prevAlt >= -0.833 && sun.altitudeDeg < -0.833 && sunsetTime == null && minute > 720) {
                sunsetTime = time
                sunsetAz = sun.azimuthDeg
            }

            prevAlt = sun.altitudeDeg
        }

        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val riseStr = sunriseTime?.format(formatter) ?: "--:--"
        val setStr = sunsetTime?.format(formatter) ?: "--:--"
        val durationMin = if (sunriseTime != null && sunsetTime != null) {
            java.time.Duration.between(sunriseTime, sunsetTime).toMinutes().toInt()
        } else {
            0
        }

        return SunDayTimes(
            sunriseTimeStr = riseStr,
            sunsetTimeStr = setStr,
            sunriseAzimuthDeg = sunriseAz,
            sunsetAzimuthDeg = sunsetAz,
            maxAltitudeDeg = maxAlt,
            daylightDurationMinutes = durationMin
        )
    }
}

data class SunDayTimes(
    val sunriseTimeStr: String,
    val sunsetTimeStr: String,
    val sunriseAzimuthDeg: Double,
    val sunsetAzimuthDeg: Double,
    val maxAltitudeDeg: Double,
    val daylightDurationMinutes: Int
)

