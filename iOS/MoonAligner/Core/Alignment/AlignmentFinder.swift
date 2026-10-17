import Foundation

/// 두 지점 간의 대원 거리, 방위각, 지구 곡률 보정 앙각 및 보름달 정렬 탐색기
public enum AlignmentFinder {

    private static let earthRadiusMeters = 6371000.0

    /// 하버사인 공식에 의한 두 지점 간 대원 거리 (단위: 미터)
    public static func calculateDistanceMeters(p1: GeoPoint, p2: GeoPoint) -> Double {
        let lat1Rad = p1.latitudeDeg * (Double.pi / 180.0)
        let lat2Rad = p2.latitudeDeg * (Double.pi / 180.0)
        let dLat = (p2.latitudeDeg - p1.latitudeDeg) * (Double.pi / 180.0)
        let dLon = (p2.longitudeDeg - p1.longitudeDeg) * (Double.pi / 180.0)

        let a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2.0) * sin(dLon / 2.0)
        let c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return earthRadiusMeters * c
    }

    /// p1(카메라)에서 p2(피사체)를 바라보는 방위각 (단위: 도, 0~360)
    public static func calculateBearingDeg(p1: GeoPoint, p2: GeoPoint) -> Double {
        let lat1Rad = p1.latitudeDeg * (Double.pi / 180.0)
        let lat2Rad = p2.latitudeDeg * (Double.pi / 180.0)
        let dLon = (p2.longitudeDeg - p1.longitudeDeg) * (Double.pi / 180.0)

        let y = sin(dLon) * cos(lat2Rad)
        let x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        let bearingRad = atan2(y, x)
        let bearingDeg = bearingRad * (180.0 / Double.pi)
        return (bearingDeg.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
    }

    /// 특정 지점에서 방위각과 거리만큼 이동한 목표 좌표 (대원항법 순방향 계산)
    public static func calculateDestinationPoint(
        start: GeoPoint,
        distanceMeters: Double,
        bearingDeg: Double
    ) -> GeoPoint {
        let lat1Rad = start.latitudeDeg * (Double.pi / 180.0)
        let lon1Rad = start.longitudeDeg * (Double.pi / 180.0)
        let bearingRad = bearingDeg * (Double.pi / 180.0)
        let angularDist = distanceMeters / earthRadiusMeters

        let lat2Rad = asin(
            sin(lat1Rad) * cos(angularDist) +
            cos(lat1Rad) * sin(angularDist) * cos(bearingRad)
        )
        let lon2Rad = lon1Rad + atan2(
            sin(bearingRad) * sin(angularDist) * cos(lat1Rad),
            cos(angularDist) - sin(lat1Rad) * sin(lat2Rad)
        )

        return GeoPoint(
            latitudeDeg: lat2Rad * (180.0 / Double.pi),
            longitudeDeg: lon2Rad * (180.0 / Double.pi),
            altitudeMeters: start.altitudeMeters
        )
    }

    /// 지구 곡률(Earth Curvature)을 보정한 피사체 상단 겉보기 앙각 (단위: 도)
    public static func calculateTargetElevationAngleDeg(
        camera: GeoPoint,
        target: GeoPoint,
        targetAdditionalHeightMeters: Double = 0.0
    ) -> Double {
        let distance = calculateDistanceMeters(p1: camera, p2: target)
        if distance < 1.0 { return 0.0 }

        // 곡률에 의한 침하량: drop = d^2 / (2 * R)
        let curvatureDropMeters = (distance * distance) / (2.0 * earthRadiusMeters)
        let targetEffectiveAltitude = (target.altitudeMeters + targetAdditionalHeightMeters) - curvatureDropMeters
        let deltaHeight = targetEffectiveAltitude - camera.altitudeMeters

        let angleRad = atan(deltaHeight / distance)
        return angleRad * (180.0 / Double.pi)
    }

    public struct AlignmentEvent: Identifiable {
        public let id = UUID()
        public let date: Date
        public let moonDetails: MoonDetails
        public let angularOffsetDeg: Double
        public let isAligned: Bool
        public let targetType: String

        public init(date: Date, moonDetails: MoonDetails, angularOffsetDeg: Double, isAligned: Bool, targetType: String = "MOON") {
            self.date = date
            self.moonDetails = moonDetails
            self.angularOffsetDeg = angularOffsetDeg
            self.isAligned = isAligned
            self.targetType = targetType
        }
    }

    /// 달 위상 필터 종류
    public enum MoonPhaseFilter: String, CaseIterable, Identifiable {
        case all = "전체 위상"
        case fullMoon = "🌕 보름달 (75%+)"
        case halfMoon = "🌓 상현/하현 (35~75%)"
        case crescent = "🌙 초승/그믐 (35% 미만)"

        public var id: String { rawValue }
    }

    /// 특정 기간 동안 달이 피사체를 관통하는 최적의 정렬 순간 탐색
    public static func findMoonAlignments(
        camera: GeoPoint,
        target: GeoPoint,
        targetHeightMeters: Double,
        startDate: Date = Date(),
        days: Int = 7,
        toleranceDeg: Double = 0.4,
        phaseFilter: MoonPhaseFilter = .all
    ) -> [AlignmentEvent] {
        let targetBearing = calculateBearingDeg(p1: camera, p2: target)
        let targetElevation = calculateTargetElevationAngleDeg(camera: camera, target: target, targetAdditionalHeightMeters: targetHeightMeters / 2.0)

        var results: [AlignmentEvent] = []
        var currentDate = startDate
        let endDate = Calendar.current.date(byAdding: .day, value: days, to: startDate) ?? startDate

        var lastEvent: AlignmentEvent? = nil

        while currentDate < endDate {
            let moon = EphemerisEngine.calculateMoon(date: currentDate, latitudeDeg: camera.latitudeDeg, longitudeDeg: camera.longitudeDeg)

            let dAz = abs(moon.azimuthDeg - targetBearing)
            let dAzNorm = min(dAz, 360.0 - dAz)
            let dAlt = abs(moon.altitudeDeg - targetElevation)
            let totalOffset = sqrt(dAzNorm * dAzNorm + dAlt * dAlt)

            // 위상 필터 검사
            let matchesPhase: Bool = {
                switch phaseFilter {
                case .all: return true
                case .fullMoon: return moon.illuminationFraction >= 0.75
                case .halfMoon: return moon.illuminationFraction >= 0.35 && moon.illuminationFraction <= 0.75
                case .crescent: return moon.illuminationFraction < 0.35
                }
            }()

            if matchesPhase && totalOffset <= toleranceDeg && moon.altitudeDeg > -1.0 {
                let event = AlignmentEvent(
                    date: currentDate,
                    moonDetails: moon,
                    angularOffsetDeg: totalOffset,
                    isAligned: true,
                    targetType: "MOON"
                )

                if lastEvent == nil || totalOffset < lastEvent!.angularOffsetDeg {
                    if let last = lastEvent, currentDate.timeIntervalSince(last.date) < 600 {
                        results.removeLast()
                    }
                    results.append(event)
                    lastEvent = event
                }
            } else {
                lastEvent = nil
            }

            // 방위각 차이가 4도 이상이면 2분 가속 스텝
            let stepMinutes = (dAzNorm > 4.0) ? 2 : 1
            guard let next = Calendar.current.date(byAdding: .minute, value: stepMinutes, to: currentDate) else { break }
            currentDate = next
        }

        return results
    }

    /// 특정 기간 동안 태양이 피사체를 관통하는 최적의 정렬 순간 탐색 (일출/일몰 및 주간 첨탑 관통)
    public static func findSunAlignments(
        camera: GeoPoint,
        target: GeoPoint,
        targetHeightMeters: Double,
        startDate: Date = Date(),
        days: Int = 7,
        toleranceDeg: Double = 0.5
    ) -> [AlignmentEvent] {
        let targetBearing = calculateBearingDeg(p1: camera, p2: target)
        let targetElevation = calculateTargetElevationAngleDeg(camera: camera, target: target, targetAdditionalHeightMeters: targetHeightMeters / 2.0)

        var results: [AlignmentEvent] = []
        var currentDate = startDate
        let endDate = Calendar.current.date(byAdding: .day, value: days, to: startDate) ?? startDate

        var lastEvent: AlignmentEvent? = nil

        while currentDate < endDate {
            let sun = EphemerisEngine.calculateSun(date: currentDate, latitudeDeg: camera.latitudeDeg, longitudeDeg: camera.longitudeDeg)

            let dAz = abs(sun.azimuthDeg - targetBearing)
            let dAzNorm = min(dAz, 360.0 - dAz)
            let dAlt = abs(sun.altitudeDeg - targetElevation)
            let totalOffset = sqrt(dAzNorm * dAzNorm + dAlt * dAlt)

            if totalOffset <= toleranceDeg && sun.altitudeDeg > -1.0 {
                let dummyMoon = MoonDetails(
                    azimuthDeg: sun.azimuthDeg,
                    altitudeDeg: sun.altitudeDeg,
                    distanceKm: 149600000.0,
                    angularDiameterDeg: 0.53,
                    phase: .fullMoon,
                    illuminationFraction: 1.0,
                    ageDays: 14.8
                )
                let event = AlignmentEvent(
                    date: currentDate,
                    moonDetails: dummyMoon,
                    angularOffsetDeg: totalOffset,
                    isAligned: true,
                    targetType: "SUN"
                )

                if lastEvent == nil || totalOffset < lastEvent!.angularOffsetDeg {
                    if let last = lastEvent, currentDate.timeIntervalSince(last.date) < 600 {
                        results.removeLast()
                    }
                    results.append(event)
                    lastEvent = event
                }
            } else {
                lastEvent = nil
            }

            let stepMinutes = (dAzNorm > 4.0) ? 2 : 1
            guard let next = Calendar.current.date(byAdding: .minute, value: stepMinutes, to: currentDate) else { break }
            currentDate = next
        }

        return results
    }

    public struct AzimuthAlignmentMatch {
        public let date: Date
        public let moonDetails: MoonDetails
        public let offsetDeg: Double
    }

    /// 현재 카메라와 피사체 조준선(방위각)에 천체(달 또는 태양)가 1열로 통과하는 가장 가까운 시각(분 단위) 탐색
    public static func findNextAzimuthAlignment(
        camera: GeoPoint,
        target: GeoPoint,
        startDate: Date = Date(),
        maxHours: Int = 48,
        isSun: Bool = false
    ) -> AzimuthAlignmentMatch? {
        let targetBearing = calculateBearingDeg(p1: camera, p2: target)
        var bestDate: Date? = nil
        var bestMoon: MoonDetails? = nil
        var minOffset: Double = 999.0

        var current = startDate
        let endDate = Calendar.current.date(byAdding: .hour, value: maxHours, to: startDate) ?? startDate

        while current < endDate {
            let (azimuth, altitude, details): (Double, Double, MoonDetails) = {
                if isSun {
                    let s = EphemerisEngine.calculateSun(date: current, latitudeDeg: camera.latitudeDeg, longitudeDeg: camera.longitudeDeg)
                    let dummyM = MoonDetails(
                        azimuthDeg: s.azimuthDeg,
                        altitudeDeg: s.altitudeDeg,
                        distanceKm: 149600000.0,
                        angularDiameterDeg: 0.53,
                        phase: .fullMoon,
                        illuminationFraction: 1.0,
                        ageDays: 14.8
                    )
                    return (s.azimuthDeg, s.altitudeDeg, dummyM)
                } else {
                    let m = EphemerisEngine.calculateMoon(date: current, latitudeDeg: camera.latitudeDeg, longitudeDeg: camera.longitudeDeg)
                    return (m.azimuthDeg, m.altitudeDeg, m)
                }
            }()

            let dAz = abs(azimuth - targetBearing)
            let dAzNorm = min(dAz, 360.0 - dAz)

            if dAzNorm < 0.5 && altitude > -3.0 {
                return AzimuthAlignmentMatch(date: current, moonDetails: details, offsetDeg: dAzNorm)
            }

            if dAzNorm < minOffset {
                minOffset = dAzNorm
                bestDate = current
                bestMoon = details
            }

            guard let next = Calendar.current.date(byAdding: .minute, value: 1, to: current) else { break }
            current = next
        }

        if let bDate = bestDate, let bMoon = bestMoon, minOffset < 2.5 {
            return AzimuthAlignmentMatch(date: bDate, moonDetails: bMoon, offsetDeg: minOffset)
        }
        return nil
    }
}

// MARK: - Shot Plan Model & Local Storage (Offline Planning)
public struct ShotPlan: Identifiable, Codable {
    public var id: UUID = UUID()
    public var name: String
    public var timestamp: Date
    public var cameraPoint: GeoPoint
    public var targetPoint: GeoPoint
    public var targetHeightMeters: Double
    public var distanceMeters: Double
    public var focalLengthMm: Float = 600.0
    public var note: String = ""

    public init(
        id: UUID = UUID(),
        name: String,
        timestamp: Date,
        cameraPoint: GeoPoint,
        targetPoint: GeoPoint,
        targetHeightMeters: Double,
        distanceMeters: Double,
        focalLengthMm: Float = 600.0,
        note: String = ""
    ) {
        self.id = id
        self.name = name
        self.timestamp = timestamp
        self.cameraPoint = cameraPoint
        self.targetPoint = targetPoint
        self.targetHeightMeters = targetHeightMeters
        self.distanceMeters = distanceMeters
        self.focalLengthMm = focalLengthMm
        self.note = note
    }
}

public enum ShotPlanStorage {
    private static let key = "moonaligner_saved_shot_plans_key"

    public static func loadPlans() -> [ShotPlan] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
        do {
            return try JSONDecoder().decode([ShotPlan].self, from: data)
        } catch {
            print("Failed to decode plans: \(error)")
            return []
        }
    }

    public static func savePlan(_ plan: ShotPlan) {
        var existing = loadPlans()
        if let idx = existing.firstIndex(where: { $0.id == plan.id }) {
            existing[idx] = plan
        } else {
            existing.insert(plan, at: 0)
        }
        saveAll(existing)
    }

    public static func deletePlan(id: UUID) {
        var existing = loadPlans()
        existing.removeAll(where: { $0.id == id })
        saveAll(existing)
    }

    private static func saveAll(_ plans: [ShotPlan]) {
        do {
            let data = try JSONEncoder().encode(plans)
            UserDefaults.standard.set(data, forKey: key)
        } catch {
            print("Failed to encode plans: \(error)")
        }
    }
}
