import Foundation

/// 카메라 센서 포맷 규격 모델
public enum CameraSensor: String, CaseIterable, Identifiable {
    case fullFrame = "풀프레임 (35mm)"
    case apsC = "APS-C (크롭)"
    case microFourThirds = "마이크로 포서즈"
    case mediumFormat = "중형 (44x33)"

    public var id: String { rawValue }
    public var displayName: String { rawValue }

    public var widthMm: Double {
        switch self {
        case .fullFrame: return 36.0
        case .apsC: return 23.6
        case .microFourThirds: return 17.3
        case .mediumFormat: return 43.8
        }
    }

    public var heightMm: Double {
        switch self {
        case .fullFrame: return 24.0
        case .apsC: return 15.6
        case .microFourThirds: return 13.0
        case .mediumFormat: return 32.9
        }
    }

    public var cropFactor: Double {
        switch self {
        case .fullFrame: return 1.0
        case .apsC: return 1.53
        case .microFourThirds: return 2.0
        case .mediumFormat: return 0.79
        }
    }
}
