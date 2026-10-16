import Foundation

/// 초망원 배경 압축 효과 및 달-피사체 정렬 계산기
public enum OpticsCalculator {

    public static let defaultMoonAngularDiameterDeg = 0.5181
    public static let supermoonAngularDiameterDeg = 0.558
    public static let micromoonAngularDiameterDeg = 0.490

    /// 피사체와 카메라 간의 필요 촬영 거리(m) 계산: D = H / (2 * tan(theta / 2))
    public static func calculateRequiredDistance(
        subjectHeightMeters: Double,
        scaleRatioToMoon: Double = 1.0,
        moonAngularDiameterDeg: Double = defaultMoonAngularDiameterDeg
    ) -> Double {
        guard subjectHeightMeters > 0, scaleRatioToMoon > 0 else { return 0.0 }
        let targetAngularDiameterRad = (moonAngularDiameterDeg * scaleRatioToMoon) * (Double.pi / 180.0)
        return subjectHeightMeters / (2.0 * tan(targetAngularDiameterRad / 2.0))
    }

    /// 특정 초점거리에서 센서 상에 맺히는 달의 상 크기(mm)
    public static func calculateMoonImageDiameterOnSensorMm(
        focalLengthMm: Double,
        moonAngularDiameterDeg: Double = defaultMoonAngularDiameterDeg
    ) -> Double {
        let angleRad = moonAngularDiameterDeg * (Double.pi / 180.0)
        return 2.0 * focalLengthMm * tan(angleRad / 2.0)
    }
}
