import Foundation

/// 달-랜드마크 정렬(달걸이) 최적 촬영 기회 모델
public struct MoonAlignmentOpportunity: Identifiable {
    public let id = UUID()
    public let date: Date
    public let moonDetails: MoonDetails
    public let sunDetails: SunDetails
    public let targetBearingDeg: Double
    public let targetElevationDeg: Double
    public let angularOffsetDeg: Double
    public let distanceMeters: Double
    
    public let skyStage: SkyLightingStage
    public let score: Int // 0 ~ 100
    public let grade: OpportunityGrade
    public let recommendedFocalLengthMm: Int
    public let isMoonRising: Bool // true: 월출 방향, false: 월몰 방향
    
    public init(
        date: Date,
        moonDetails: MoonDetails,
        sunDetails: SunDetails,
        targetBearingDeg: Double,
        targetElevationDeg: Double,
        angularOffsetDeg: Double,
        distanceMeters: Double,
        skyStage: SkyLightingStage,
        score: Int,
        grade: OpportunityGrade,
        recommendedFocalLengthMm: Int,
        isMoonRising: Bool
    ) {
        self.date = date
        self.moonDetails = moonDetails
        self.sunDetails = sunDetails
        self.targetBearingDeg = targetBearingDeg
        self.targetElevationDeg = targetElevationDeg
        self.angularOffsetDeg = angularOffsetDeg
        self.distanceMeters = distanceMeters
        self.skyStage = skyStage
        self.score = score
        self.grade = grade
        self.recommendedFocalLengthMm = recommendedFocalLengthMm
        self.isMoonRising = isMoonRising
    }
    
    public enum SkyLightingStage: String, CaseIterable {
        case blueHour = "🌟 블루아워 (Blue Hour)"
        case goldenHour = "🌅 골든아워 (Golden Hour)"
        case nauticalTwilight = "🌌 항해박명 (Twilight)"
        case night = "🌙 야간 (Night)"
        case daytime = "☀️ 주간 (Daytime)"
        
        public var shortLabel: String {
            switch self {
            case .blueHour: return "🌟 블루아워"
            case .goldenHour: return "🌅 골든아워"
            case .nauticalTwilight: return "🌌 박명"
            case .night: return "🌙 야간"
            case .daytime: return "☀️ 주간"
            }
        }
        
        public var advice: String {
            switch self {
            case .blueHour: return "하늘빛과 건축물 조명, 달 표면 질감이 가장 완벽하게 조화되는 골든타임"
            case .goldenHour: return "노을빛 황금빛 하늘과 보름달의 극적인 대비 연출"
            case .nauticalTwilight: return "은은한 여명/박명 속 짙은 푸른빛 하늘"
            case .night: return "깊은 밤 랜드마크 실루엣과 밝은 달의 강렬한 명암 대비 (HDR 추천)"
            case .daytime: return "낮 하늘 속 은은한 달의 실루엣 관측"
            }
        }
    }
    
    public enum OpportunityGrade: String, CaseIterable {
        case s = "🌟 Grade S"
        case a = "✨ Grade A"
        case b = "👍 Grade B"
        
        public var badgeText: String { rawValue }
        
        public var title: String {
            switch self {
            case .s: return "🌟 Grade S (최고 추천)"
            case .a: return "✨ Grade A (우수)"
            case .b: return "👍 Grade B (양호)"
            }
        }
    }
}

public enum OpportunityFilter: String, CaseIterable, Identifiable {
    case all = "전체"
    case goldenBlue = "🌟 골든/블루아워"
    case fullMoon = "🌕 보름달 (80%+)"
    
    public var id: String { rawValue }
}

public enum OpportunitySort: String, CaseIterable, Identifiable {
    case byScore = "🏆 점수/추천순"
    case byDate = "📅 일자순"
    
    public var id: String { rawValue }
}

/// 달걸이(보름달-피사체 3점 1열 정렬) 골든타임 탐색 엔진
public enum MoonAlignmentFinder {
    
    /// 향후 N일(기본 60일) 동안 카메라와 피사체 조준선에 달이 일치하는 최적의 달걸이 촬영 기회를 탐색
    public static func findOpportunities(
        camera: GeoPoint,
        target: GeoPoint,
        targetHeightMeters: Double,
        startDate: Date = Date(),
        days: Int = 60,
        toleranceDeg: Double = 0.40
    ) -> [MoonAlignmentOpportunity] {
        let distance = AlignmentFinder.calculateDistanceMeters(p1: camera, p2: target)
        let targetBearing = AlignmentFinder.calculateBearingDeg(p1: camera, p2: target)
        let targetElevation = AlignmentFinder.calculateTargetElevationAngleDeg(
            camera: camera,
            target: target,
            targetAdditionalHeightMeters: targetHeightMeters / 2.0
        )
        
        let recFocalLength: Int = {
            if distance >= 4000.0 { return 800 }
            if distance >= 2000.0 { return 600 }
            if distance >= 1000.0 { return 400 }
            return 200
        }()
        
        var opportunities: [MoonAlignmentOpportunity] = []
        let calendar = Calendar.current
        var currentDate = startDate
        let endDate = calendar.date(byAdding: .day, value: days, to: startDate) ?? startDate
        
        var currentPassBest: (date: Date, moon: MoonDetails, sun: SunDetails, offset: Double)? = nil
        var inAlignmentWindow = false
        
        while currentDate < endDate {
            let moon = EphemerisEngine.calculateMoon(
                date: currentDate,
                latitudeDeg: camera.latitudeDeg,
                longitudeDeg: camera.longitudeDeg
            )
            
            let dAz = abs(moon.azimuthDeg - targetBearing)
            let dAzNorm = min(dAz, 360.0 - dAz)
            let cosEl = cos(targetElevation * .pi / 180.0)
            let dAlt = abs(moon.altitudeDeg - targetElevation)
            let angularOffset = sqrt((dAzNorm * cosEl) * (dAzNorm * cosEl) + dAlt * dAlt)
            
            // 달이 지평선 위에 있고 허용 오차 이내인 경우
            if angularOffset <= toleranceDeg && moon.altitudeDeg > -1.0 {
                inAlignmentWindow = true
                let sun = EphemerisEngine.calculateSun(
                    date: currentDate,
                    latitudeDeg: camera.latitudeDeg,
                    longitudeDeg: camera.longitudeDeg
                )
                
                if currentPassBest == nil || angularOffset < currentPassBest!.offset {
                    currentPassBest = (currentDate, moon, sun, angularOffset)
                }
            } else {
                // 방금 정렬 윈도우를 벗어난 경우 해당 패스의 최고점을 결과에 등록
                if inAlignmentWindow, let best = currentPassBest {
                    let opp = evaluateOpportunity(
                        date: best.date,
                        moon: best.moon,
                        sun: best.sun,
                        targetBearing: targetBearing,
                        targetElevation: targetElevation,
                        angularOffset: best.offset,
                        distanceMeters: distance,
                        recommendedFocalLength: recFocalLength,
                        camera: camera
                    )
                    opportunities.append(opp)
                    currentPassBest = nil
                    inAlignmentWindow = false
                }
            }
            
            // 고속 스텝: 방위각 오차가 4도 이상이면 5분 점프, 가까워지면 1분 정밀 탐색
            let stepMinutes = (dAzNorm > 4.0) ? 5 : 1
            guard let next = calendar.date(byAdding: .minute, value: stepMinutes, to: currentDate) else { break }
            currentDate = next
        }
        
        // 탐색 종료 시 남아있는 기회가 있다면 등록
        if let best = currentPassBest {
            let opp = evaluateOpportunity(
                date: best.date,
                moon: best.moon,
                sun: best.sun,
                targetBearing: targetBearing,
                targetElevation: targetElevation,
                angularOffset: best.offset,
                distanceMeters: distance,
                recommendedFocalLength: recFocalLength,
                camera: camera
            )
            opportunities.append(opp)
        }
        
        return opportunities
    }
    
    private static func evaluateOpportunity(
        date: Date,
        moon: MoonDetails,
        sun: SunDetails,
        targetBearing: Double,
        targetElevation: Double,
        angularOffset: Double,
        distanceMeters: Double,
        recommendedFocalLength: Int,
        camera: GeoPoint
    ) -> MoonAlignmentOpportunity {
        // 1. 태양 고도 기반 하늘 조명 단계(Sky Lighting Stage) 분류
        let sunAlt = sun.altitudeDeg
        let skyStage: MoonAlignmentOpportunity.SkyLightingStage = {
            if sunAlt >= -8.0 && sunAlt <= -4.0 {
                return .blueHour // 블루아워 (건축물 조명 On + 푸른 하늘 + 선명한 달)
            } else if sunAlt > -4.0 && sunAlt <= 2.0 {
                return .goldenHour // 골든아워/일출몰
            } else if sunAlt >= -14.0 && sunAlt < -8.0 {
                return .nauticalTwilight // 항해박명
            } else if sunAlt < -14.0 {
                return .night // 야간
            } else {
                return .daytime // 주간
            }
        }()
        
        // 2. 점수 계산 (100점 만점)
        // A. 위상/조도 점수 (최대 40점): 보름달(1.0)에 가까울수록 고득점
        let illumScore = moon.illuminationFraction * 40.0
        
        // B. 하늘 조명 보너스 (최대 40점)
        let lightingBonus: Double = {
            switch skyStage {
            case .blueHour: return 40.0
            case .goldenHour: return 32.0
            case .nauticalTwilight: return 22.0
            case .night: return 16.0
            case .daytime: return 5.0
            }
        }()
        
        // C. 정렬 정확도 보너스 (최대 20점)
        let accBonus = 20.0 * max(0.0, 1.0 - (angularOffset / 0.40))
        
        let totalScore = Int((illumScore + lightingBonus + accBonus).rounded())
        let clampedScore = min(100, max(0, totalScore))
        
        // 3. 등급(Grade) 판정
        let grade: MoonAlignmentOpportunity.OpportunityGrade = {
            if clampedScore >= 80 {
                return .s
            } else if clampedScore >= 60 {
                return .a
            } else {
                return .b
            }
        }()
        
        // 4. 달의 상승/하강 판정 (5분 후 고도 비교)
        let futureDate = Calendar.current.date(byAdding: .minute, value: 5, to: date) ?? date
        let futureMoon = EphemerisEngine.calculateMoon(
            date: futureDate,
            latitudeDeg: camera.latitudeDeg,
            longitudeDeg: camera.longitudeDeg
        )
        let isRising = futureMoon.altitudeDeg > moon.altitudeDeg
        
        return MoonAlignmentOpportunity(
            date: date,
            moonDetails: moon,
            sunDetails: sun,
            targetBearingDeg: targetBearing,
            targetElevationDeg: targetElevation,
            angularOffsetDeg: angularOffset,
            distanceMeters: distanceMeters,
            skyStage: skyStage,
            score: clampedScore,
            grade: grade,
            recommendedFocalLengthMm: recommendedFocalLength,
            isMoonRising: isRising
        )
    }
}
