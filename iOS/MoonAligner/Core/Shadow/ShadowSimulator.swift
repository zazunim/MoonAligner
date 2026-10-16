import Foundation

public struct ShadowCastResult {
    public let objectHeightMeters: Double
    public let shadowLengthMeters: Double
    public let shadowBearingDeg: Double
    public let shadowRatio: Double
    public let isSunAboveHorizon: Bool
    public let shadowTipPoint: GeoPoint?
}

/// SunOnTrack 스타일 그림자 시뮬레이션 엔진
public enum ShadowSimulator {

    public static func calculateShadow(
        sunAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        objectHeightMeters: Double,
        objectLocation: GeoPoint,
        maxShadowBoundMeters: Double = 3000.0
    ) -> ShadowCastResult {
        let shadowBearing = (sunAzimuthDeg + 180.0).truncatingRemainder(dividingBy: 360.0)

        if sunAltitudeDeg <= 0.1 || objectHeightMeters <= 0.0 {
            return ShadowCastResult(
                objectHeightMeters: objectHeightMeters,
                shadowLengthMeters: 0.0,
                shadowBearingDeg: shadowBearing,
                shadowRatio: 0.0,
                isSunAboveHorizon: false,
                shadowTipPoint: nil
            )
        }

        let altRad = sunAltitudeDeg * (Double.pi / 180.0)
        let rawLength = objectHeightMeters / tan(altRad)
        let clampedLength = min(rawLength, maxShadowBoundMeters)
        let ratio = 1.0 / tan(altRad)

        let tipPoint = AlignmentFinder.calculateDestinationPoint(
            start: objectLocation,
            distanceMeters: clampedLength,
            bearingDeg: shadowBearing
        )

        return ShadowCastResult(
            objectHeightMeters: objectHeightMeters,
            shadowLengthMeters: rawLength,
            shadowBearingDeg: shadowBearing,
            shadowRatio: ratio,
            isSunAboveHorizon: true,
            shadowTipPoint: tipPoint
        )
    }
}
