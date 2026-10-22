import Foundation

/// 달의 위상 (Moon Phase) 정의
public enum MoonPhase: String, CaseIterable {
    case newMoon = "신월 (삭)"
    case waxingCrescent = "초승달"
    case firstQuarter = "상현달"
    case waxingGibbous = "차오르는 달"
    case fullMoon = "보름달 (망)"
    case waningGibbous = "기우는 달"
    case lastQuarter = "하현달"
    case waningCrescent = "그믐달"

    public var displayName: String { rawValue }
    public var name: String { rawValue }
    public var koreanName: String { rawValue }

    public var emoji: String {
        switch self {
        case .newMoon: return "🌑"
        case .waxingCrescent: return "🌒"
        case .firstQuarter: return "🌓"
        case .waxingGibbous: return "🌔"
        case .fullMoon: return "🌕"
        case .waningGibbous: return "🌖"
        case .lastQuarter: return "🌗"
        case .waningCrescent: return "🌘"
        }
    }

    public static func fromPhaseAngle(_ angleDeg: Double) -> MoonPhase {
        let normalized = (angleDeg.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
        switch normalized {
        case 337.5...360.0, 0.0..<22.5: return .newMoon
        case 22.5..<67.5: return .waxingCrescent
        case 67.5..<112.5: return .firstQuarter
        case 112.5..<157.5: return .waxingGibbous
        case 157.5..<202.5: return .fullMoon
        case 202.5..<247.5: return .waningGibbous
        case 247.5..<292.5: return .lastQuarter
        case 292.5..<337.5: return .waningCrescent
        default: return .newMoon
        }
    }
}

public struct MoonDetails {
    public let azimuthDeg: Double
    public let altitudeDeg: Double
    public let distanceKm: Double
    public let angularDiameterDeg: Double
    public let phase: MoonPhase
    public let illuminationFraction: Double
    public let ageDays: Double

    public init(
        azimuthDeg: Double,
        altitudeDeg: Double,
        distanceKm: Double,
        angularDiameterDeg: Double,
        phase: MoonPhase,
        illuminationFraction: Double,
        ageDays: Double
    ) {
        self.azimuthDeg = azimuthDeg
        self.altitudeDeg = altitudeDeg
        self.distanceKm = distanceKm
        self.angularDiameterDeg = angularDiameterDeg
        self.phase = phase
        self.illuminationFraction = illuminationFraction
        self.ageDays = ageDays
    }
}

public struct SunDetails {
    public let azimuthDeg: Double
    public let altitudeDeg: Double
    public let isGoldenHour: Bool
    public let isBlueHour: Bool

    public init(
        azimuthDeg: Double,
        altitudeDeg: Double,
        isGoldenHour: Bool,
        isBlueHour: Bool
    ) {
        self.azimuthDeg = azimuthDeg
        self.altitudeDeg = altitudeDeg
        self.isGoldenHour = isGoldenHour
        self.isBlueHour = isBlueHour
    }
}

public struct SunDayTimes {
    public let sunriseTimeStr: String
    public let sunsetTimeStr: String
    public let sunriseAzimuthDeg: Double
    public let sunsetAzimuthDeg: Double
    public let maxAltitudeDeg: Double
    public let daylightDurationMinutes: Int

    public init(
        sunriseTimeStr: String,
        sunsetTimeStr: String,
        sunriseAzimuthDeg: Double,
        sunsetAzimuthDeg: Double,
        maxAltitudeDeg: Double,
        daylightDurationMinutes: Int
    ) {
        self.sunriseTimeStr = sunriseTimeStr
        self.sunsetTimeStr = sunsetTimeStr
        self.sunriseAzimuthDeg = sunriseAzimuthDeg
        self.sunsetAzimuthDeg = sunsetAzimuthDeg
        self.maxAltitudeDeg = maxAltitudeDeg
        self.daylightDurationMinutes = daylightDurationMinutes
    }
}
