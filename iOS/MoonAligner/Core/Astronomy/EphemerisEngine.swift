import Foundation

/// 고정밀 천체 역학 엔진 (Jean Meeus Astronomical Algorithms Swift 구현체)
public enum EphemerisEngine {

    private static let degToRad = Double.pi / 180.0
    private static let radToDeg = 180.0 / Double.pi
    private static let moonRadiusKm = 1737.4

    /// 특정 일시 및 GPS 좌표에서의 달 위치 및 위상 계산
    public static func calculateMoon(
        date: Date,
        latitudeDeg: Double,
        longitudeDeg: Double
    ) -> MoonDetails {
        let jd = toJulianDate(date: date)
        let t = (jd - 2451545.0) / 36525.0

        let lPrime = normalizeDeg(218.3164477 + 481267.88128 * t)
        let d = normalizeDeg(297.8501921 + 445267.1114034 * t)
        let m = normalizeDeg(357.5291092 + 35999.0502909 * t)
        let mPrime = normalizeDeg(134.9633964 + 477198.8675055 * t)
        let f = normalizeDeg(93.2720950 + 483202.0175233 * t)

        let dRad = d * degToRad
        let mRad = m * degToRad
        let mPrimeRad = mPrime * degToRad
        let fRad = f * degToRad

        let lambda = lPrime +
            6.288774 * sin(mPrimeRad) +
            1.274027 * sin(2.0 * dRad - mPrimeRad) +
            0.658314 * sin(2.0 * dRad) +
            0.213618 * sin(2.0 * mPrimeRad) -
            0.185116 * sin(mRad) -
            0.114332 * sin(2.0 * fRad)

        let beta = 5.128178 * sin(fRad) +
            0.280602 * sin(mPrimeRad + fRad) +
            0.277693 * sin(mPrimeRad - fRad) +
            0.173237 * sin(2.0 * dRad - fRad)

        let distanceKm = 385000.56 -
            20905.355 * cos(mPrimeRad) -
            3699.111 * cos(2.0 * dRad - mPrimeRad) -
            2955.968 * cos(2.0 * dRad) -
            569.925 * cos(2.0 * mPrimeRad)

        let eps = (23.439291 - 0.0130042 * t) * degToRad
        let lambdaRad = lambda * degToRad
        let betaRad = beta * degToRad

        let sinDec = sin(betaRad) * cos(eps) + cos(betaRad) * sin(eps) * sin(lambdaRad)
        let dec = asin(sinDec)
        let yRa = sin(lambdaRad) * cos(eps) - tan(betaRad) * sin(eps)
        let xRa = cos(lambdaRad)
        let ra = atan2(yRa, xRa)

        let (azimuth, rawAltitude) = equatorialToHorizontal(
            raRad: ra,
            decRad: dec,
            jd: jd,
            latitudeDeg: latitudeDeg,
            longitudeDeg: longitudeDeg
        )

        let correctedAltitude = applyAtmosphericRefraction(trueAltitudeDeg: rawAltitude)
        let angularDiameterDeg = 2.0 * asin(moonRadiusKm / distanceKm) * radToDeg

        // 달의 황도 이각 (Elongation / Moon Age Angle: 0°=신월, 90°=상현, 180°=보름달, 270°=하현, 360°=신월)
        let moonAgeAngleDeg = normalizeDeg(
            d +
            6.289 * sin(mPrimeRad) -
            2.100 * sin(mRad) +
            1.274 * sin(2.0 * dRad - mPrimeRad) +
            0.658 * sin(2.0 * dRad)
        )

        let phase = MoonPhase.fromPhaseAngle(moonAgeAngleDeg)
        let illuminationFraction = (1.0 - cos(moonAgeAngleDeg * degToRad)) / 2.0
        let ageDays = (moonAgeAngleDeg / 360.0) * 29.530588853

        return MoonDetails(
            azimuthDeg: azimuth,
            altitudeDeg: correctedAltitude,
            distanceKm: distanceKm,
            angularDiameterDeg: angularDiameterDeg,
            phase: phase,
            illuminationFraction: illuminationFraction,
            ageDays: ageDays
        )
    }

    /// 특정 일시 및 GPS 좌표에서의 태양 위치 계산
    public static func calculateSun(
        date: Date,
        latitudeDeg: Double,
        longitudeDeg: Double
    ) -> SunDetails {
        let jd = toJulianDate(date: date)
        let t = (jd - 2451545.0) / 36525.0

        let l0 = normalizeDeg(280.46646 + 36000.76983 * t)
        let m = normalizeDeg(357.52911 + 35999.05029 * t) * degToRad
        let c = (1.914602 - 0.004817 * t) * sin(m) + (0.019993 - 0.000101 * t) * sin(2.0 * m)
        let trueLong = (l0 + c) * degToRad

        let eps = (23.439291 - 0.0130042 * t) * degToRad
        let sinDec = sin(eps) * sin(trueLong)
        let dec = asin(sinDec)
        let ra = atan2(cos(eps) * sin(trueLong), cos(trueLong))

        let (azimuth, rawAltitude) = equatorialToHorizontal(
            raRad: ra,
            decRad: dec,
            jd: jd,
            latitudeDeg: latitudeDeg,
            longitudeDeg: longitudeDeg
        )

        let correctedAltitude = applyAtmosphericRefraction(trueAltitudeDeg: rawAltitude)
        let isGolden = correctedAltitude >= -4.0 && correctedAltitude <= 6.0
        let isBlue = correctedAltitude >= -6.0 && correctedAltitude < -4.0

        return SunDetails(
            azimuthDeg: azimuth,
            altitudeDeg: correctedAltitude,
            isGoldenHour: isGolden,
            isBlueHour: isBlue
        )
    }

    public static var seoulCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Seoul") ?? .current
        return cal
    }

    public static func currentHourFloat(in calendar: Calendar = seoulCalendar, for date: Date = Date()) -> Float {
        let comps = calendar.dateComponents([.hour, .minute, .second], from: date)
        let h = Float(comps.hour ?? 0)
        let m = Float(comps.minute ?? 0) / 60.0
        let s = Float(comps.second ?? 0) / 3600.0
        return h + m + s
    }

    /// 하루 동안의 일출/일몰 시각, 방위각 및 일조 시간 계산
    public static func calculateSunDayTimes(
        date: Date,
        calendar: Calendar = seoulCalendar,
        latitudeDeg: Double,
        longitudeDeg: Double
    ) -> SunDayTimes {
        let startOfDay = calendar.startOfDay(for: date)
        var sunriseTime: Date? = nil
        var sunriseAz = 90.0
        var sunsetTime: Date? = nil
        var sunsetAz = 270.0
        var maxAlt = -90.0

        var prevAlt = -90.0
        for minute in stride(from: 0, to: 1440, by: 2) {
            guard let sampleDate = calendar.date(byAdding: .minute, value: minute, to: startOfDay) else { continue }
            let sun = calculateSun(date: sampleDate, latitudeDeg: latitudeDeg, longitudeDeg: longitudeDeg)

            if sun.altitudeDeg > maxAlt {
                maxAlt = sun.altitudeDeg
            }

            if prevAlt <= -0.833 && sun.altitudeDeg > -0.833 && sunriseTime == nil {
                sunriseTime = sampleDate
                sunriseAz = sun.azimuthDeg
            }

            if prevAlt >= -0.833 && sun.altitudeDeg < -0.833 && sunsetTime == nil && minute > 720 {
                sunsetTime = sampleDate
                sunsetAz = sun.azimuthDeg
            }

            prevAlt = sun.altitudeDeg
        }

        let formatter = DateFormatter()
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "HH:mm"
        let riseStr = sunriseTime != nil ? formatter.string(from: sunriseTime!) : "--:--"
        let setStr = sunsetTime != nil ? formatter.string(from: sunsetTime!) : "--:--"

        var durationMin = 0
        if let rise = sunriseTime, let set = sunsetTime {
            durationMin = Int(set.timeIntervalSince(rise) / 60.0)
        }

        return SunDayTimes(
            sunriseTimeStr: riseStr,
            sunsetTimeStr: setStr,
            sunriseAzimuthDeg: sunriseAz,
            sunsetAzimuthDeg: sunsetAz,
            maxAltitudeDeg: maxAlt,
            daylightDurationMinutes: max(0, durationMin)
        )
    }

    private static func toJulianDate(date: Date) -> Double {
        let epochMilli = date.timeIntervalSince1970 * 1000.0
        return 2440587.5 + (epochMilli / 86400000.0)
    }

    private static func equatorialToHorizontal(
        raRad: Double,
        decRad: Double,
        jd: Double,
        latitudeDeg: Double,
        longitudeDeg: Double
    ) -> (Double, Double) {
        let latRad = latitudeDeg * degToRad
        let t = (jd - 2451545.0) / 36525.0
        var gmstDeg = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t - (t * t * t) / 38710000.0
        gmstDeg = normalizeDeg(gmstDeg)

        let lstRad = normalizeDeg(gmstDeg + longitudeDeg) * degToRad
        let haRad = lstRad - raRad

        let sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
        let altRad = asin(min(1.0, max(-1.0, sinAlt)))

        let cosAz = (sin(decRad) - sin(latRad) * sin(altRad)) / (cos(latRad) * cos(altRad))
        let sinAz = -cos(decRad) * sin(haRad) / cos(altRad)
        var azDeg = atan2(sinAz, cosAz) * radToDeg
        azDeg = normalizeDeg(azDeg)

        return (azDeg, altRad * radToDeg)
    }

    private static func applyAtmosphericRefraction(trueAltitudeDeg: Double) -> Double {
        if trueAltitudeDeg < -5.0 { return trueAltitudeDeg }
        let h = max(-2.0, trueAltitudeDeg)
        let rArcmin = 1.02 / tan((h + 10.3 / (h + 5.11)) * degToRad)
        return trueAltitudeDeg + (rArcmin / 60.0)
    }

    private static func normalizeDeg(_ deg: Double) -> Double {
        let res = deg.truncatingRemainder(dividingBy: 360.0)
        return res < 0 ? res + 360.0 : res
    }
}
