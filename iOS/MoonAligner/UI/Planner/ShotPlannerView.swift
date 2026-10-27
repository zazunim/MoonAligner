import SwiftUI
import Combine
import MapKit
import CoreLocation
import UIKit

// MARK: - Pin Selection Mode (One-Tap Select & One-Tap Move)
public enum PinMoveMode: Equatable {
    case none
    case camera
    case target
}

// MARK: - Distance Alignment Anchor Mode (Target-Fixed vs Camera-Fixed)
public enum AlignmentAnchorMode: Equatable {
    case targetFixed // 🗼 피사체 고정 -> 📷 카메라 위치 정렬
    case cameraFixed // 📷 카메라 고정 -> 🗼 피사체 위치 정렬
}

// MARK: - Shared State between Shot Planner & Calculator
public class PlannerSharedState: ObservableObject {
    @Published public var selectedTab: Int = 0
    @Published public var selectedPreset: PresetSpot = PresetSpot.defaults[0]
    @Published public var cameraPoint: GeoPoint = PresetSpot.defaults[0].camera
    @Published public var targetPoint: GeoPoint = PresetSpot.defaults[0].target
    @Published public var targetHeight: Double = PresetSpot.defaults[0].targetHeightMeters

    // 거리/화각 계산기 연동 상태
    @Published public var celestialTarget: CelestialTargetType = .moon
    @Published public var selectedSubjectType: SubjectSilhouetteType = .lotteTop
    @Published public var subjectHeight: Float = Float(PresetSpot.defaults[0].targetHeightMeters)
    @Published public var scaleRatio: Float = 1.0
    @Published public var focalLength: Float = 600.0
    @Published public var selectedSensor: CameraSensor = .fullFrame

    // 지도 핀 원탭 이동 모드 & 거리 고정 기준 옵션
    @Published public var pinMoveMode: PinMoveMode = .camera
    @Published public var anchorMode: AlignmentAnchorMode = .targetFixed
    @Published public var isDistanceLockedToCalculator: Bool = false
    @Published public var centerTrigger: CLLocationCoordinate2D? = nil

    // 플래너 추천 일시 (AR 뷰파인더 연동용)
    @Published public var plannedDate: Date? = nil

    public var calculatorRequiredDistance: Double {
        OpticsCalculator.calculateRequiredDistance(
            subjectHeightMeters: Double(subjectHeight),
            scaleRatioToMoon: Double(scaleRatio)
        )
    }

    public init() {}

    /// 현재 선택된 기준점 모드(피사체 고정 or 카메라 고정)에 따라 거리 자동 정렬
    public func alignToCalculatorDistance() {
        switch anchorMode {
        case .targetFixed:
            alignCameraToCalculatorDistance()
        case .cameraFixed:
            alignTargetToCalculatorDistance()
        }
    }

    /// 🗼 피사체 위치를 고정하고, 거리/화각 탭 거리값에 맞춰 📷 카메라 위치를 자동 정렬
    public func alignCameraToCalculatorDistance() {
        let reqDist = calculatorRequiredDistance
        guard reqDist > 1.0 else { return }
        targetHeight = Double(subjectHeight)

        // 피사체(🗼)에서 현재 카메라(📷)를 바라보는 방위각을 유지한 채 거리만 정확히 reqDist로 맞춤
        let reverseBearing = AlignmentFinder.calculateBearingDeg(p1: targetPoint, p2: cameraPoint)
        let alignedCam = AlignmentFinder.calculateDestinationPoint(
            start: targetPoint,
            distanceMeters: reqDist,
            bearingDeg: reverseBearing
        )
        cameraPoint = GeoPoint(
            latitudeDeg: alignedCam.latitudeDeg,
            longitudeDeg: alignedCam.longitudeDeg,
            altitudeMeters: cameraPoint.altitudeMeters
        )
    }

    /// 📷 카메라 위치를 고정하고, 거리/화각 탭 거리값에 맞춰 🗼 피사체 위치를 자동 정렬
    public func alignTargetToCalculatorDistance() {
        let reqDist = calculatorRequiredDistance
        guard reqDist > 1.0 else { return }
        targetHeight = Double(subjectHeight)

        // 카메라(📷)에서 현재 피사체(🗼)를 바라보는 방위각을 유지한 채 거리만 정확히 reqDist로 맞춤
        let forwardBearing = AlignmentFinder.calculateBearingDeg(p1: cameraPoint, p2: targetPoint)
        let alignedTarget = AlignmentFinder.calculateDestinationPoint(
            start: cameraPoint,
            distanceMeters: reqDist,
            bearingDeg: forwardBearing
        )
        targetPoint = GeoPoint(
            latitudeDeg: alignedTarget.latitudeDeg,
            longitudeDeg: alignedTarget.longitudeDeg,
            altitudeMeters: targetPoint.altitudeMeters
        )
    }

    /// 🌕 피사체(🗼) 위치를 고정하고, 달(Moon) 방위각과 일직선이 되도록 카메라(📷) 위치를 자동 정렬
    /// 카메라 -> 피사체 -> 달 일직선 배치 (피사체 기준 달의 반대 방향으로 카메라 배치)
    public func alignCameraToMoonLine(moonAzimuthDeg: Double) {
        let currentDist = AlignmentFinder.calculateDistanceMeters(p1: cameraPoint, p2: targetPoint)
        let dist: Double = isDistanceLockedToCalculator && calculatorRequiredDistance > 1.0
            ? calculatorRequiredDistance
            : (currentDist > 5.0 ? currentDist : (calculatorRequiredDistance > 1.0 ? calculatorRequiredDistance : 1000.0))
        targetHeight = Double(subjectHeight)
        anchorMode = .targetFixed
        pinMoveMode = .camera

        let normMoonAz = (moonAzimuthDeg.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
        var camBearing = (normMoonAz + 180.0).truncatingRemainder(dividingBy: 360.0)
        var alignedCam = AlignmentFinder.calculateDestinationPoint(
            start: targetPoint,
            distanceMeters: dist,
            bearingDeg: camBearing
        )

        // 미세 구면 오차 정밀 보정 (0.001도 이내)
        let err = AlignmentFinder.calculateBearingDeg(p1: alignedCam, p2: targetPoint) - normMoonAz
        if abs(err) > 0.001 {
            camBearing = ((camBearing - err).truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
            alignedCam = AlignmentFinder.calculateDestinationPoint(
                start: targetPoint,
                distanceMeters: dist,
                bearingDeg: camBearing
            )
        }

        cameraPoint = GeoPoint(
            latitudeDeg: alignedCam.latitudeDeg,
            longitudeDeg: alignedCam.longitudeDeg,
            altitudeMeters: cameraPoint.altitudeMeters
        )
    }

    /// 🌕 카메라(📷) 위치를 고정하고, 달(Moon) 방위각과 일직선이 되도록 피사체(🗼) 위치를 자동 정렬
    /// 카메라 -> 피사체 -> 달 일직선 배치 (카메라 기준 달의 방향으로 피사체 배치)
    public func alignTargetToMoonLine(moonAzimuthDeg: Double) {
        let currentDist = AlignmentFinder.calculateDistanceMeters(p1: cameraPoint, p2: targetPoint)
        let dist: Double = isDistanceLockedToCalculator && calculatorRequiredDistance > 1.0
            ? calculatorRequiredDistance
            : (currentDist > 5.0 ? currentDist : (calculatorRequiredDistance > 1.0 ? calculatorRequiredDistance : 1000.0))
        targetHeight = Double(subjectHeight)
        anchorMode = .cameraFixed
        pinMoveMode = .target

        let normMoonAz = (moonAzimuthDeg.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
        let alignedTarget = AlignmentFinder.calculateDestinationPoint(
            start: cameraPoint,
            distanceMeters: dist,
            bearingDeg: normMoonAz
        )

        targetPoint = GeoPoint(
            latitudeDeg: alignedTarget.latitudeDeg,
            longitudeDeg: alignedTarget.longitudeDeg,
            altitudeMeters: targetPoint.altitudeMeters
        )
    }

    /// 🌕 현재 선택된 기준점 모드(피사체 고정 or 카메라 고정)에 맞춰 달과 일직선 자동 정렬
    public func alignToMoonLine(moonAzimuthDeg: Double) {
        switch anchorMode {
        case .targetFixed:
            alignCameraToMoonLine(moonAzimuthDeg: moonAzimuthDeg)
        case .cameraFixed:
            alignTargetToMoonLine(moonAzimuthDeg: moonAzimuthDeg)
        }
    }
}

// MARK: - CoreLocation Manager for Shot Planner
public class ShotPlannerLocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    @Published public var userLocation: GeoPoint?
    @Published public var userHeading: Double?
    @Published public var hasPermission: Bool = false

    public override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        if CLLocationManager.headingAvailable() {
            manager.headingFilter = 1.0
        }
        checkAuth()
    }

    public func checkAuth() {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        switch status {
        case .authorizedWhenInUse, .authorizedAlways:
            hasPermission = true
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() {
                manager.headingFilter = 1.0
                manager.startUpdatingHeading()
            }
        default:
            hasPermission = false
        }
    }

    public func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        checkAuth()
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        userLocation = GeoPoint(
            latitudeDeg: loc.coordinate.latitude,
            longitudeDeg: loc.coordinate.longitude,
            altitudeMeters: loc.altitude
        )
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        if newHeading.headingAccuracy >= 0 {
            let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
            userHeading = heading
        }
    }
}

// MARK: - Real-Time 120fps Synchronous Overlay Canvas View
final class OverlayCanvasView: UIView {
    weak var mapView: MKMapView?

    var cameraCoord: CLLocationCoordinate2D?
    var targetCoord: CLLocationCoordinate2D?
    var moonTipCoord: CLLocationCoordinate2D?
    var sunTipCoord: CLLocationCoordinate2D?
    var riseTipCoord: CLLocationCoordinate2D?
    var setTipCoord: CLLocationCoordinate2D?
    var shadowTipCoord: CLLocationCoordinate2D?
    var userCoord: CLLocationCoordinate2D?

    var isSunMode: Bool = false
    var isAligned: Bool = false
    var anchorMode: AlignmentAnchorMode = .cameraFixed
    var distanceMeters: Double = 0.0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        backgroundColor = .clear
        isUserInteractionEnabled = false
    }

    override func draw(_ rect: CGRect) {
        guard let mapView = mapView, let ctx = UIGraphicsGetCurrentContext() else { return }

        // 1. 촬영 거리 반경 원 (대시 원)
        if let cam = cameraCoord, let target = targetCoord, distanceMeters > 5.0 {
            let centerCoord = (anchorMode == .cameraFixed) ? cam : target
            let centerPt = mapView.convert(centerCoord, toPointTo: self)

            let centerGeo = GeoPoint(latitudeDeg: centerCoord.latitude, longitudeDeg: centerCoord.longitude)
            let edgeGeo = AlignmentFinder.calculateDestinationPoint(start: centerGeo, distanceMeters: distanceMeters, bearingDeg: 90.0)
            let edgePt = mapView.convert(CLLocationCoordinate2D(latitude: edgeGeo.latitudeDeg, longitude: edgeGeo.longitudeDeg), toPointTo: self)
            let radius = hypot(edgePt.x - centerPt.x, edgePt.y - centerPt.y)

            if radius > 2.0 && radius < 20000.0 {
                ctx.saveGState()
                ctx.setStrokeColor(UIColor(hex: 0x38BDF8, alpha: 0.65).cgColor)
                ctx.setFillColor(UIColor(hex: 0x38BDF8, alpha: 0.04).cgColor)
                ctx.setLineWidth(2.0)
                let dashes: [CGFloat] = [8, 6]
                ctx.setLineDash(phase: 0, lengths: dashes)
                ctx.addArc(center: centerPt, radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: true)
                ctx.drawPath(using: .fillStroke)
                ctx.restoreGState()
            }
        }

        // 2. 피사체 조준선 (카메라 ➔ 피사체, 오렌지 라인)
        if let cam = cameraCoord, let target = targetCoord {
            let p1 = mapView.convert(cam, toPointTo: self)
            let p2 = mapView.convert(target, toPointTo: self)
            ctx.saveGState()
            ctx.setStrokeColor(UIColor(red: 1.0, green: 0.34, blue: 0.13, alpha: 1.0).cgColor)
            ctx.setLineWidth(6.0)
            ctx.setLineCap(.round)
            ctx.strokeLineSegments(between: [p1, p2])
            ctx.restoreGState()
        }

        // 3. 천체 궤적선 (달 & 태양)
        if isSunMode {
            // ☀️ 태양 플래너 모드: 카메라 ➔ 피사체 ➔ 태양 관통선이 메인
            if let cam = cameraCoord, let sunTip = sunTipCoord {
                let p1 = mapView.convert(cam, toPointTo: self)
                let p2 = mapView.convert(sunTip, toPointTo: self)
                ctx.saveGState()
                let color = isAligned ? UIColor(hex: 0xF59E0B) : UIColor(hex: 0xFBBF24)
                ctx.setStrokeColor(color.cgColor)
                ctx.setLineWidth(isAligned ? 7.0 : 4.5)
                ctx.setLineCap(.round)
                ctx.strokeLineSegments(between: [p1, p2])
                ctx.restoreGState()
            }
            // 보조: 달 광선
            if let cam = cameraCoord, let moonTip = moonTipCoord {
                let p1 = mapView.convert(cam, toPointTo: self)
                let p2 = mapView.convert(moonTip, toPointTo: self)
                ctx.saveGState()
                ctx.setStrokeColor(UIColor(hex: 0xFDE047, alpha: 0.7).cgColor)
                ctx.setLineWidth(3.5)
                ctx.setLineCap(.round)
                ctx.strokeLineSegments(between: [p1, p2])
                ctx.restoreGState()
            }
        } else {
            // 🌕 달 플래너 모드: 카메라 ➔ 피사체 ➔ 달 관통선이 메인
            if let cam = cameraCoord, let moonTip = moonTipCoord {
                let p1 = mapView.convert(cam, toPointTo: self)
                let p2 = mapView.convert(moonTip, toPointTo: self)
                ctx.saveGState()
                let color = isAligned ? UIColor(hex: 0xFACC15) : UIColor(hex: 0xFDE047)
                ctx.setStrokeColor(color.cgColor)
                ctx.setLineWidth(isAligned ? 7.0 : 4.5)
                ctx.setLineCap(.round)
                ctx.strokeLineSegments(between: [p1, p2])
                ctx.restoreGState()
            }
            // 보조: 태양 광선 (피사체 기준)
            if let target = targetCoord, let sunTip = sunTipCoord {
                let p1 = mapView.convert(target, toPointTo: self)
                let p2 = mapView.convert(sunTip, toPointTo: self)
                ctx.saveGState()
                ctx.setStrokeColor(UIColor(hex: 0xF59E0B).cgColor)
                ctx.setLineWidth(4.0)
                ctx.setLineCap(.round)
                ctx.strokeLineSegments(between: [p1, p2])
                ctx.restoreGState()
            }
        }

        // 4. 일출 & 일몰 광선 (피사체 기준)
        if let target = targetCoord, let riseTip = riseTipCoord {
            let p1 = mapView.convert(target, toPointTo: self)
            let p2 = mapView.convert(riseTip, toPointTo: self)
            ctx.saveGState()
            ctx.setStrokeColor(UIColor(hex: 0xFF7043).cgColor)
            ctx.setLineWidth(3.0)
            ctx.setLineCap(.round)
            ctx.strokeLineSegments(between: [p1, p2])
            ctx.restoreGState()
        }
        if let target = targetCoord, let setTip = setTipCoord {
            let p1 = mapView.convert(target, toPointTo: self)
            let p2 = mapView.convert(setTip, toPointTo: self)
            ctx.saveGState()
            ctx.setStrokeColor(UIColor(hex: 0xAB47BC).cgColor)
            ctx.setLineWidth(3.0)
            ctx.setLineCap(.round)
            ctx.strokeLineSegments(between: [p1, p2])
            ctx.restoreGState()
        }

        // 5. 그림자 투영선
        if let target = targetCoord, let shadowTip = shadowTipCoord {
            let p1 = mapView.convert(target, toPointTo: self)
            let p2 = mapView.convert(shadowTip, toPointTo: self)
            ctx.saveGState()
            ctx.setStrokeColor(UIColor(hex: 0x0F172A, alpha: 0.75).cgColor)
            ctx.setLineWidth(16.0)
            ctx.setLineCap(.round)
            ctx.strokeLineSegments(between: [p1, p2])
            ctx.restoreGState()
        }

        // 6. 현장 삼각대 위치 유도선 (내 위치 ➔ 계획된 카메라 핀 연결)
        if let uCoord = userCoord, let cam = cameraCoord {
            let p1 = mapView.convert(uCoord, toPointTo: self)
            let p2 = mapView.convert(cam, toPointTo: self)
            ctx.saveGState()
            ctx.setStrokeColor(UIColor(hex: 0x38BDF8).cgColor)
            ctx.setLineWidth(4.0)
            ctx.setLineCap(.round)
            ctx.strokeLineSegments(between: [p1, p2])
            ctx.restoreGState()
        }
    }
}

// MARK: - Custom Polyline with Color & Width
class ColoredPolyline: MKPolyline {
    var color: UIColor = .white
    var lineWidth: CGFloat = 3.0
    var isDashed: Bool = false
}

// MARK: - Custom Annotation for MKMapView
enum MapItemType {
    case camera
    case target
    case user
    case moon
    case sun
    case sunrise
    case sunset
    case shadow
    case label
}

final class CustomMapAnnotation: NSObject, MKAnnotation {
    let key: String
    @objc dynamic var coordinate: CLLocationCoordinate2D
    @objc dynamic var title: String?
    @objc dynamic var subtitle: String?
    var itemType: MapItemType
    var renderedImage: UIImage?

    init(key: String, coordinate: CLLocationCoordinate2D, title: String?, subtitle: String? = nil, itemType: MapItemType, renderedImage: UIImage? = nil) {
        self.key = key
        self.coordinate = coordinate
        self.title = title
        self.subtitle = subtitle
        self.itemType = itemType
        self.renderedImage = renderedImage
    }
}

// MARK: - Helper Functions to Draw Custom Badges & Icons
enum MapBadgeDrawer {
    static func createCircularBadge(
        symbol: String,
        bgColor: UIColor,
        size: CGFloat = 44,
        borderColor: UIColor = .white,
        isSelected: Bool = false
    ) -> UIImage {
        let canvasSize = isSelected ? size + 14 : size
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: canvasSize, height: canvasSize))
        return renderer.image { _ in
            if isSelected {
                // 선택된 핀 외곽 네온 황금빛 후광 링
                let glowRect = CGRect(x: 2, y: 2, width: canvasSize - 4, height: canvasSize - 4)
                UIColor(hex: 0xFDE047, alpha: 0.35).setFill()
                UIBezierPath(ovalIn: glowRect).fill()

                UIColor(hex: 0xFDE047).setStroke()
                let glowStroke = UIBezierPath(ovalIn: glowRect)
                glowStroke.lineWidth = 3.0
                glowStroke.stroke()
            }

            let offset = (canvasSize - size) / 2.0
            let rect = CGRect(x: offset + 2, y: offset + 2, width: size - 4, height: size - 4)
            bgColor.setFill()
            UIBezierPath(ovalIn: rect).fill()

            borderColor.setStroke()
            let strokePath = UIBezierPath(ovalIn: rect)
            strokePath.lineWidth = isSelected ? 3.0 : 2.5
            strokePath.stroke()

            let font = UIFont.systemFont(ofSize: size * 0.44)
            let string = symbol as NSString
            let textSize = string.size(withAttributes: [.font: font])
            let textRect = CGRect(
                x: (canvasSize - textSize.width) / 2.0,
                y: (canvasSize - textSize.height) / 2.0,
                width: textSize.width,
                height: textSize.height
            )
            string.draw(in: textRect, withAttributes: [.font: font])
        }
    }

    static func createTextBadge(text: String, bgColor: UIColor, textColor: UIColor) -> UIImage {
        let font = UIFont.systemFont(ofSize: 11, weight: .bold)
        let string = text as NSString
        let textSize = string.size(withAttributes: [.font: font])
        let padH: CGFloat = 8
        let padV: CGFloat = 4
        let totalW = textSize.width + padH * 2
        let totalH = textSize.height + padV * 2

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: totalW, height: totalH))
        return renderer.image { _ in
            let rect = CGRect(x: 0, y: 0, width: totalW, height: totalH)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: totalH / 2.0)
            bgColor.setFill()
            path.fill()

            let textRect = CGRect(x: padH, y: padV, width: textSize.width, height: textSize.height)
            string.draw(in: textRect, withAttributes: [.font: font, .foregroundColor: textColor])
        }
    }

    /// 🧭 스마트폰이 바라보는 방향 시야각 콘(Vision Cone) + 위치 퍽 렌더러
    static func createUserDirectionBadge(headingDeg: Double?) -> UIImage {
        let size: CGFloat = 84
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        return renderer.image { ctx in
            let cgCtx = ctx.cgContext
            let center = CGPoint(x: size / 2.0, y: size / 2.0)

            if let heading = headingDeg {
                // 스마트폰이 바라보는 방향 시야각 콘 (Vision Cone / Flashlight Beam)
                cgCtx.saveGState()
                cgCtx.translateBy(x: center.x, y: center.y)
                cgCtx.rotate(by: CGFloat((heading - 90.0) * .pi / 180.0))

                let coneAngle: CGFloat = 52.0 * .pi / 180.0
                let coneRadius: CGFloat = 38.0

                let conePath = UIBezierPath()
                conePath.move(to: .zero)
                conePath.addArc(
                    withCenter: .zero,
                    radius: coneRadius,
                    startAngle: -coneAngle / 2.0,
                    endAngle: coneAngle / 2.0,
                    clockwise: true
                )
                conePath.close()

                let colors = [
                    UIColor(hex: 0x38BDF8, alpha: 0.75).cgColor,
                    UIColor(hex: 0x0EA5E9, alpha: 0.30).cgColor,
                    UIColor(hex: 0x0284C7, alpha: 0.0).cgColor
                ] as CFArray
                let colorSpace = CGColorSpaceCreateDeviceRGB()
                let locations: [CGFloat] = [0.0, 0.55, 1.0]
                if let gradient = CGGradient(colorsSpace: colorSpace, colors: colors, locations: locations) {
                    cgCtx.addPath(conePath.cgPath)
                    cgCtx.clip()
                    cgCtx.drawRadialGradient(
                        gradient,
                        startCenter: .zero,
                        startRadius: 0,
                        endCenter: .zero,
                        endRadius: coneRadius,
                        options: []
                    )
                }
                cgCtx.restoreGState()

                // 정밀 지향 화살촉
                cgCtx.saveGState()
                cgCtx.translateBy(x: center.x, y: center.y)
                cgCtx.rotate(by: CGFloat((heading - 90.0) * .pi / 180.0))
                let arrowPath = UIBezierPath()
                let arrowDist: CGFloat = 19.0
                arrowPath.move(to: CGPoint(x: arrowDist + 8, y: 0))
                arrowPath.addLine(to: CGPoint(x: arrowDist, y: -4.5))
                arrowPath.addLine(to: CGPoint(x: arrowDist + 2.5, y: 0))
                arrowPath.addLine(to: CGPoint(x: arrowDist, y: 4.5))
                arrowPath.close()
                UIColor(hex: 0x38BDF8).setFill()
                arrowPath.fill()
                cgCtx.restoreGState()
            }

            // 중앙 위치 퍽 (애플 지도 스타일 블루 퍽)
            // 1. 외곽 은은한 블루 펄스
            let haloRect = CGRect(x: center.x - 13, y: center.y - 13, width: 26, height: 26)
            UIColor(hex: 0x0EA5E9, alpha: 0.28).setFill()
            UIBezierPath(ovalIn: haloRect).fill()

            // 2. 화이트 외곽 테두리
            let puckBorderRect = CGRect(x: center.x - 9, y: center.y - 9, width: 18, height: 18)
            UIColor.white.setFill()
            UIBezierPath(ovalIn: puckBorderRect).fill()

            // 3. 블루 코어
            let puckCoreRect = CGRect(x: center.x - 7, y: center.y - 7, width: 14, height: 14)
            UIColor(hex: 0x0284C7).setFill()
            UIBezierPath(ovalIn: puckCoreRect).fill()

            // 4. 하이라이트 중심 점
            let centerDotRect = CGRect(x: center.x - 2.5, y: center.y - 2.5, width: 5, height: 5)
            UIColor.white.setFill()
            UIBezierPath(ovalIn: centerDotRect).fill()
        }
    }
}

// MARK: - Native MKMapView UIViewRepresentable (One-Tap Select & One-Tap Move, Zero Flicker)
struct PlannerMapView: UIViewRepresentable {
    @Binding var cameraPoint: GeoPoint
    @Binding var targetPoint: GeoPoint
    @Binding var pinMoveMode: PinMoveMode
    var celestialTarget: CelestialTargetType
    var anchorMode: AlignmentAnchorMode
    var isDistanceLocked: Bool
    var lockedDistanceMeters: Double
    var targetHeight: Double
    var userLocation: GeoPoint?
    var userHeading: Double?
    var moon: MoonDetails
    var sun: SunDetails
    var sunDayTimes: SunDayTimes
    var shadow: ShadowCastResult
    var distance: Double
    var bearing: Double
    var isAligned: Bool
    @Binding var centerTrigger: CLLocationCoordinate2D?
    @Binding var selectedMarkerMessage: String?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator
        mapView.showsCompass = true
        mapView.showsScale = true
        mapView.mapType = .standard

        let initialCoord = CLLocationCoordinate2D(latitude: cameraPoint.latitudeDeg, longitude: cameraPoint.longitudeDeg)
        let region = MKCoordinateRegion(center: initialCoord, span: MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08))
        mapView.setRegion(region, animated: false)

        let canvas = OverlayCanvasView(frame: mapView.bounds)
        canvas.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        canvas.mapView = mapView
        mapView.addSubview(canvas)
        context.coordinator.overlayCanvasView = canvas

        context.coordinator.setupGestures(mapView: mapView)
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.updateOverlaysAndAnnotations(mapView: mapView)

        if let trigger = centerTrigger {
            mapView.setCenter(trigger, animated: true)
            DispatchQueue.main.async {
                self.centerTrigger = nil
            }
        }
    }

    class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        weak var overlayCanvasView: OverlayCanvasView?

        func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
            overlayCanvasView?.setNeedsDisplay()
        }
        struct GeometrySnapshot: Equatable {
            let cameraPoint: GeoPoint
            let targetPoint: GeoPoint
            let anchorMode: AlignmentAnchorMode
            let celestialTarget: CelestialTargetType
            let isAligned: Bool
            let moonAzimuth: Double
            let moonAltitude: Double
            let moonPhase: MoonPhase
            let sunAzimuth: Double
            let sunAltitude: Double
            let targetHeight: Double
            let sunriseStr: String
            let sunsetStr: String
            let userLocLatE4: Int
            let userLocLonE4: Int
            let userHeadingDeg: Int?
        }

        struct AnnotationSnapshot: Equatable {
            let geometry: GeometrySnapshot
            let pinMoveMode: PinMoveMode
            let userLocation: GeoPoint?
            let userHeadingDeg: Int?
        }

        var parent: PlannerMapView
        private var lastGeometrySnapshot: GeometrySnapshot?
        private var lastAnnotationSnapshot: AnnotationSnapshot?
        private var annotationsByKey: [String: CustomMapAnnotation] = [:]

        init(_ parent: PlannerMapView) {
            self.parent = parent
        }

        func setupGestures(mapView: MKMapView) {
            let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleMapTap(_:)))
            singleTap.numberOfTapsRequired = 1
            singleTap.cancelsTouchesInView = false
            singleTap.delegate = self
            mapView.addGestureRecognizer(singleTap)
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            return true
        }

        @objc func handleMapTap(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended, let mapView = gesture.view as? MKMapView else { return }
            let tapPoint = gesture.location(in: mapView)

            // 1. 먼저 📷 카메라 핀이나 🗼 피사체 핀 자체를 터치했는지 화면 좌표 거리(34pt 이내)로 판별
            let camCoord = CLLocationCoordinate2D(latitude: parent.cameraPoint.latitudeDeg, longitude: parent.cameraPoint.longitudeDeg)
            let targetCoord = CLLocationCoordinate2D(latitude: parent.targetPoint.latitudeDeg, longitude: parent.targetPoint.longitudeDeg)

            let camScreenPt = mapView.convert(camCoord, toPointTo: mapView)
            let targetScreenPt = mapView.convert(targetCoord, toPointTo: mapView)

            let distToCam = hypot(tapPoint.x - camScreenPt.x, tapPoint.y - camScreenPt.y)
            let distToTarget = hypot(tapPoint.x - targetScreenPt.x, tapPoint.y - targetScreenPt.y)

            if distToCam < 34.0 && distToCam <= distToTarget {
                parent.pinMoveMode = .camera
                return
            }
            if distToTarget < 34.0 {
                parent.pinMoveMode = .target
                return
            }

            // 2. 일출(🌅), 일몰(🌇), 태양(☀️), 달(🌕) 등 천체 아이콘 터치 판별 ➔ 시각/정보 팝업 및 배너 표시 (카메라/피사체 이동 방지!)
            for (_, anno) in annotationsByKey {
                guard anno.itemType != .camera && anno.itemType != .target && anno.itemType != .label else { continue }
                let annoPt = mapView.convert(anno.coordinate, toPointTo: mapView)
                if hypot(tapPoint.x - annoPt.x, tapPoint.y - annoPt.y) < 34.0 {
                    mapView.selectAnnotation(anno, animated: true)
                    if let t = anno.title {
                        let sub = anno.subtitle ?? ""
                        parent.selectedMarkerMessage = sub.isEmpty ? t : "\(t) • \(sub)"
                    }
                    return
                }
            }

            // 3. 지도 빈 곳 터치: 선택된 아이콘이 있으면 해당 위치로 즉시 이동!
            let tappedCoord = mapView.convert(tapPoint, toCoordinateFrom: mapView)
            let tappedGeo = GeoPoint(latitudeDeg: tappedCoord.latitude, longitudeDeg: tappedCoord.longitude)

            switch parent.pinMoveMode {
            case .camera:
                if parent.isDistanceLocked && parent.lockedDistanceMeters > 1.0 {
                    // 🗼 피사체 고정 -> 터치한 방향으로 📷 카메라를 계산기 거리만큼 배치
                    let dirBearing = AlignmentFinder.calculateBearingDeg(p1: parent.targetPoint, p2: tappedGeo)
                    let lockedCam = AlignmentFinder.calculateDestinationPoint(
                        start: parent.targetPoint,
                        distanceMeters: parent.lockedDistanceMeters,
                        bearingDeg: dirBearing
                    )
                    parent.cameraPoint = GeoPoint(
                        latitudeDeg: lockedCam.latitudeDeg,
                        longitudeDeg: lockedCam.longitudeDeg,
                        altitudeMeters: parent.cameraPoint.altitudeMeters
                    )
                } else {
                    parent.cameraPoint = GeoPoint(
                        latitudeDeg: tappedCoord.latitude,
                        longitudeDeg: tappedCoord.longitude,
                        altitudeMeters: parent.cameraPoint.altitudeMeters
                    )
                }
            case .target:
                if parent.isDistanceLocked && parent.lockedDistanceMeters > 1.0 {
                    // 📷 카메라 고정 -> 터치한 방향으로 🗼 피사체를 계산기 거리만큼 배치!
                    let dirBearing = AlignmentFinder.calculateBearingDeg(p1: parent.cameraPoint, p2: tappedGeo)
                    let lockedTarget = AlignmentFinder.calculateDestinationPoint(
                        start: parent.cameraPoint,
                        distanceMeters: parent.lockedDistanceMeters,
                        bearingDeg: dirBearing
                    )
                    parent.targetPoint = GeoPoint(
                        latitudeDeg: lockedTarget.latitudeDeg,
                        longitudeDeg: lockedTarget.longitudeDeg,
                        altitudeMeters: parent.targetPoint.altitudeMeters
                    )
                } else {
                    parent.targetPoint = GeoPoint(
                        latitudeDeg: tappedCoord.latitude,
                        longitudeDeg: tappedCoord.longitude,
                        altitudeMeters: parent.targetPoint.altitudeMeters
                    )
                }
            case .none:
                break
            }
        }

        private func upsertAnnotation(
            mapView: MKMapView,
            key: String,
            coordinate: CLLocationCoordinate2D,
            title: String,
            subtitle: String? = nil,
            itemType: MapItemType,
            image: UIImage,
            activeKeys: inout Set<String>
        ) {
            activeKeys.insert(key)
            if let existing = annotationsByKey[key] {
                let coordChanged = abs(existing.coordinate.latitude - coordinate.latitude) > 1e-7 ||
                                   abs(existing.coordinate.longitude - coordinate.longitude) > 1e-7
                if coordChanged {
                    existing.coordinate = coordinate
                }
                existing.title = title
                existing.subtitle = subtitle
                existing.renderedImage = image
                if let annoView = mapView.view(for: existing) {
                    annoView.image = image
                    // 🚀 화면 픽셀 좌표 실시간 즉각 동기화 (superview 기준 변환으로 슬라이더 스크러빙 시 60fps 오차 0 동기화)
                    if coordChanged, let superview = annoView.superview {
                        annoView.center = mapView.convert(coordinate, toPointTo: superview)
                    }
                }
            } else {
                let anno = CustomMapAnnotation(
                    key: key,
                    coordinate: coordinate,
                    title: title,
                    subtitle: subtitle,
                    itemType: itemType,
                    renderedImage: image
                )
                annotationsByKey[key] = anno
                mapView.addAnnotation(anno)
            }
        }

        func updateOverlaysAndAnnotations(mapView: MKMapView) {
            let userLatE4 = Int(((parent.userLocation?.latitudeDeg ?? 0.0) * 10000.0).rounded())
            let userLonE4 = Int(((parent.userLocation?.longitudeDeg ?? 0.0) * 10000.0).rounded())
            let userHeadingDeg = parent.userHeading != nil ? Int(parent.userHeading!.rounded()) : nil
            let currentGeometry = GeometrySnapshot(
                cameraPoint: parent.cameraPoint,
                targetPoint: parent.targetPoint,
                anchorMode: parent.anchorMode,
                celestialTarget: parent.celestialTarget,
                isAligned: parent.isAligned,
                moonAzimuth: parent.moon.azimuthDeg,
                moonAltitude: parent.moon.altitudeDeg,
                moonPhase: parent.moon.phase,
                sunAzimuth: parent.sun.azimuthDeg,
                sunAltitude: parent.sun.altitudeDeg,
                targetHeight: parent.targetHeight,
                sunriseStr: parent.sunDayTimes.sunriseTimeStr,
                sunsetStr: parent.sunDayTimes.sunsetTimeStr,
                userLocLatE4: userLatE4,
                userLocLonE4: userLonE4,
                userHeadingDeg: userHeadingDeg
            )
            let currentAnnotationSnap = AnnotationSnapshot(
                geometry: currentGeometry,
                pinMoveMode: parent.pinMoveMode,
                userLocation: parent.userLocation,
                userHeadingDeg: userHeadingDeg
            )

            // 1초 타이머 틱마다 좌표/각도/선택 상태가 변하지 않았으면 아무 작업도 하지 않음 (깜빡임 원천 차단!)
            guard currentAnnotationSnap != lastAnnotationSnapshot else { return }

            let camCoord = CLLocationCoordinate2D(latitude: parent.cameraPoint.latitudeDeg, longitude: parent.cameraPoint.longitudeDeg)
            let targetCoord = CLLocationCoordinate2D(latitude: parent.targetPoint.latitudeDeg, longitude: parent.targetPoint.longitudeDeg)

            let isSunMode = parent.celestialTarget == .sun
            let rayDist = max(parent.distance * 1.6, parent.distance + 3500.0, 6000.0)

            // 주 천체와 보조 천체의 끝점 계산 (주 천체는 카메라 기준, 보조 천체는 피사체 기준)
            let moonTip = AlignmentFinder.calculateDestinationPoint(
                start: parent.cameraPoint,
                distanceMeters: rayDist,
                bearingDeg: parent.moon.azimuthDeg
            )
            let moonTipCoord = CLLocationCoordinate2D(latitude: moonTip.latitudeDeg, longitude: moonTip.longitudeDeg)

            let sunTip = AlignmentFinder.calculateDestinationPoint(
                start: isSunMode ? parent.cameraPoint : parent.targetPoint,
                distanceMeters: isSunMode ? rayDist : 5000.0,
                bearingDeg: parent.sun.azimuthDeg
            )
            let sunTipCoord = CLLocationCoordinate2D(latitude: sunTip.latitudeDeg, longitude: sunTip.longitudeDeg)

            let riseTip = AlignmentFinder.calculateDestinationPoint(start: parent.targetPoint, distanceMeters: 4200.0, bearingDeg: parent.sunDayTimes.sunriseAzimuthDeg)
            let riseTipCoord = CLLocationCoordinate2D(latitude: riseTip.latitudeDeg, longitude: riseTip.longitudeDeg)

            let setTip = AlignmentFinder.calculateDestinationPoint(start: parent.targetPoint, distanceMeters: 4200.0, bearingDeg: parent.sunDayTimes.sunsetAzimuthDeg)
            let setTipCoord = CLLocationCoordinate2D(latitude: setTip.latitudeDeg, longitude: setTip.longitudeDeg)

            // 1. 기존 MapKit 벡터 타일 오버레이가 남아있다면 모두 제거 (실시간 스크러빙 시 고스트 선 잔상 영구 박멸)
            if !mapView.overlays.isEmpty {
                mapView.removeOverlays(mapView.overlays)
            }

            // 2. 120fps 실시간 동기화 오버레이 캔버스에 최신 좌표 및 기하 정보 전달
            let canvas = overlayCanvasView ?? (mapView.subviews.compactMap { $0 as? OverlayCanvasView }.first)
            if let canvas = canvas {
                overlayCanvasView = canvas
                if let ac = mapView.subviews.first(where: { String(describing: type(of: $0)).contains("AnnotationContainer") }) {
                    mapView.insertSubview(canvas, belowSubview: ac)
                }

                canvas.cameraCoord = camCoord
                canvas.targetCoord = targetCoord
                canvas.moonTipCoord = moonTipCoord
                canvas.sunTipCoord = sunTipCoord
                canvas.riseTipCoord = riseTipCoord
                canvas.setTipCoord = setTipCoord
                if parent.shadow.isSunAboveHorizon, let tip = parent.shadow.shadowTipPoint {
                    canvas.shadowTipCoord = CLLocationCoordinate2D(latitude: tip.latitudeDeg, longitude: tip.longitudeDeg)
                } else {
                    canvas.shadowTipCoord = nil
                }
                if let uLoc = parent.userLocation {
                    let distToCam = AlignmentFinder.calculateDistanceMeters(p1: uLoc, p2: parent.cameraPoint)
                    canvas.userCoord = (distToCam > 3.0) ? CLLocationCoordinate2D(latitude: uLoc.latitudeDeg, longitude: uLoc.longitudeDeg) : nil
                } else {
                    canvas.userCoord = nil
                }
                canvas.isSunMode = isSunMode
                canvas.isAligned = parent.isAligned
                canvas.anchorMode = parent.anchorMode
                canvas.distanceMeters = max(10.0, parent.distance)
                canvas.setNeedsDisplay()
            }

            lastAnnotationSnapshot = currentAnnotationSnap
            var activeKeys = Set<String>()

            // 조준선 중간 거리/방위각 뱃지 (In-place 업데이트)
            let midLat = (parent.cameraPoint.latitudeDeg + parent.targetPoint.latitudeDeg) / 2.0
            let midLon = (parent.cameraPoint.longitudeDeg + parent.targetPoint.longitudeDeg) / 2.0
            let distStr = parent.distance >= 1000 ? String(format: "%.2f km", parent.distance / 1000.0) : "\(Int(parent.distance.rounded())) m"
            let distBadgeImage = MapBadgeDrawer.createTextBadge(
                text: "📏 \(distStr) (\(Int(parent.bearing))°)",
                bgColor: UIColor(hex: 0x0F172A, alpha: 0.9),
                textColor: UIColor(hex: 0x38BDF8)
            )
            upsertAnnotation(
                mapView: mapView,
                key: "midBadge",
                coordinate: CLLocationCoordinate2D(latitude: midLat, longitude: midLon),
                title: "거리: \(distStr)",
                itemType: .label,
                image: distBadgeImage,
                activeKeys: &activeKeys
            )

            // 🌕 달 마커 & 달 궤적 뱃지 (현재 위상 이모지 반영 🌑🌒🌓🌔🌕🌖🌗🌘)
            let moonEmoji = parent.moon.phase.emoji
            let moonImage = MapBadgeDrawer.createCircularBadge(
                symbol: moonEmoji,
                bgColor: UIColor(hex: 0x1E1B4B),
                size: 44,
                borderColor: UIColor(hex: 0xFDE047)
            )
            upsertAnnotation(
                mapView: mapView,
                key: "moon",
                coordinate: moonTipCoord,
                title: "\(moonEmoji) 달 (\(parent.moon.phase.displayName))",
                subtitle: "고도: \(String(format: "%.1f", parent.moon.altitudeDeg))° | 방위각: \(Int(parent.moon.azimuthDeg))°",
                itemType: .moon,
                image: moonImage,
                activeKeys: &activeKeys
            )

            if !isSunMode {
                let moonMidLat = parent.cameraPoint.latitudeDeg + (moonTip.latitudeDeg - parent.cameraPoint.latitudeDeg) * 0.45
                let moonMidLon = parent.cameraPoint.longitudeDeg + (moonTip.longitudeDeg - parent.cameraPoint.longitudeDeg) * 0.45
                let moonBadgeImage = MapBadgeDrawer.createTextBadge(
                    text: parent.isAligned
                        ? "🎯 3점 1열 정렬 [📷➔🗼➔\(moonEmoji)] (\(Int(parent.moon.azimuthDeg))°)"
                        : "\(moonEmoji) 달 궤적 (\(Int(parent.moon.azimuthDeg))° / \(String(format: "%.1f", parent.moon.altitudeDeg))°)",
                    bgColor: parent.isAligned ? UIColor(hex: 0x14532D, alpha: 0.95) : UIColor(hex: 0x1E1B4B, alpha: 0.9),
                    textColor: UIColor(hex: 0xFEF08A)
                )
                upsertAnnotation(
                    mapView: mapView,
                    key: "moonBadge",
                    coordinate: CLLocationCoordinate2D(latitude: moonMidLat, longitude: moonMidLon),
                    title: "달 궤적",
                    itemType: .label,
                    image: moonBadgeImage,
                    activeKeys: &activeKeys
                )
            }

            // ☀️ 태양 마커
            let sunImage = MapBadgeDrawer.createCircularBadge(symbol: "☀️", bgColor: UIColor(hex: 0xD97706), size: 44, borderColor: UIColor(hex: 0xFEF08A))
            upsertAnnotation(
                mapView: mapView,
                key: "sun",
                coordinate: sunTipCoord,
                title: "☀️ 현재 태양",
                subtitle: "고도: \(String(format: "%.1f", parent.sun.altitudeDeg))° | 방위각: \(Int(parent.sun.azimuthDeg))°",
                itemType: .sun,
                image: sunImage,
                activeKeys: &activeKeys
            )

            if isSunMode {
                let sunMidLat = parent.cameraPoint.latitudeDeg + (sunTip.latitudeDeg - parent.cameraPoint.latitudeDeg) * 0.45
                let sunMidLon = parent.cameraPoint.longitudeDeg + (sunTip.longitudeDeg - parent.cameraPoint.longitudeDeg) * 0.45
                let sunBadgeImage = MapBadgeDrawer.createTextBadge(
                    text: parent.isAligned
                        ? "🎯 3점 1열 정렬 [📷➔🗼➔☀️] (\(Int(parent.sun.azimuthDeg))°)"
                        : "☀️ 태양 궤적 (\(Int(parent.sun.azimuthDeg))° / \(String(format: "%.1f", parent.sun.altitudeDeg))°)",
                    bgColor: parent.isAligned ? UIColor(hex: 0x9A3412, alpha: 0.95) : UIColor(hex: 0x451A03, alpha: 0.9),
                    textColor: UIColor(hex: 0xFEF08A)
                )
                upsertAnnotation(
                    mapView: mapView,
                    key: "sunBadge",
                    coordinate: CLLocationCoordinate2D(latitude: sunMidLat, longitude: sunMidLon),
                    title: "태양 궤적",
                    itemType: .label,
                    image: sunBadgeImage,
                    activeKeys: &activeKeys
                )
            }

            // 🌅 일출 & 🌇 일몰 마커 (시각 및 방위각 콜아웃/배너 완벽 지원)
            let riseImage = MapBadgeDrawer.createCircularBadge(symbol: "🌅", bgColor: UIColor(hex: 0xEA580C), size: 36)
            upsertAnnotation(
                mapView: mapView,
                key: "sunrise",
                coordinate: riseTipCoord,
                title: "🌅 오늘 일출 시각: \(parent.sunDayTimes.sunriseTimeStr)",
                subtitle: "일출 방위각: \(Int(parent.sunDayTimes.sunriseAzimuthDeg))°",
                itemType: .sunrise,
                image: riseImage,
                activeKeys: &activeKeys
            )

            let setImage = MapBadgeDrawer.createCircularBadge(symbol: "🌇", bgColor: UIColor(hex: 0x9333EA), size: 36)
            upsertAnnotation(
                mapView: mapView,
                key: "sunset",
                coordinate: setTipCoord,
                title: "🌇 오늘 일몰 시각: \(parent.sunDayTimes.sunsetTimeStr)",
                subtitle: "일몰 방위각: \(Int(parent.sunDayTimes.sunsetAzimuthDeg))°",
                itemType: .sunset,
                image: setImage,
                activeKeys: &activeKeys
            )

            // 👥 그림자 마커
            if parent.shadow.isSunAboveHorizon, let tip = parent.shadow.shadowTipPoint {
                let shadowTipCoord = CLLocationCoordinate2D(latitude: tip.latitudeDeg, longitude: tip.longitudeDeg)
                let shadowImage = MapBadgeDrawer.createCircularBadge(symbol: "👥", bgColor: UIColor(hex: 0x334155), size: 34, borderColor: UIColor(hex: 0x94A3B8))
                upsertAnnotation(
                    mapView: mapView,
                    key: "shadow",
                    coordinate: shadowTipCoord,
                    title: "👥 그림자 끝단",
                    itemType: .shadow,
                    image: shadowImage,
                    activeKeys: &activeKeys
                )
            }

            // 📷 카메라 핀 (삭제 없이 좌표와 이미지만 즉시 교체!)
            let isCamSelected = parent.pinMoveMode == .camera
            let camImage = MapBadgeDrawer.createCircularBadge(
                symbol: "📷",
                bgColor: UIColor(hex: 0x0284C7),
                size: 48,
                borderColor: isCamSelected ? UIColor(hex: 0xFDE047) : .white,
                isSelected: isCamSelected
            )
            upsertAnnotation(
                mapView: mapView,
                key: "camera",
                coordinate: camCoord,
                title: "📷 카메라",
                itemType: .camera,
                image: camImage,
                activeKeys: &activeKeys
            )

            // 🗼 피사체 핀 (삭제 없이 좌표와 이미지만 즉시 교체!)
            let isTargetSelected = parent.pinMoveMode == .target
            let targetImage = MapBadgeDrawer.createCircularBadge(
                symbol: "🗼",
                bgColor: UIColor(hex: 0xE11D48),
                size: 48,
                borderColor: isTargetSelected ? UIColor(hex: 0xFDE047) : .white,
                isSelected: isTargetSelected
            )
            upsertAnnotation(
                mapView: mapView,
                key: "target",
                coordinate: targetCoord,
                title: "🗼 피사체",
                itemType: .target,
                image: targetImage,
                activeKeys: &activeKeys
            )

            // 📍 내 실시간 GPS 위치 및 스마트폰 시선 방향
            if let uLoc = parent.userLocation {
                let userCoord = CLLocationCoordinate2D(latitude: uLoc.latitudeDeg, longitude: uLoc.longitudeDeg)
                let userImage = MapBadgeDrawer.createUserDirectionBadge(headingDeg: parent.userHeading)
                let headingStr = parent.userHeading != nil ? " (\(Int(parent.userHeading!.rounded()))°)" : ""
                upsertAnnotation(
                    mapView: mapView,
                    key: "user",
                    coordinate: userCoord,
                    title: "내 현재 위치\(headingStr)",
                    itemType: .user,
                    image: userImage,
                    activeKeys: &activeKeys
                )
            }

            // 더 이상 표시되지 않는 마커만 선별 제거
            for (key, anno) in annotationsByKey where !activeKeys.contains(key) {
                mapView.removeAnnotation(anno)
                annotationsByKey.removeValue(forKey: key)
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let circle = overlay as? MKCircle {
                let renderer = MKCircleRenderer(circle: circle)
                renderer.strokeColor = UIColor(hex: 0x38BDF8, alpha: 0.65)
                renderer.fillColor = UIColor(hex: 0x38BDF8, alpha: 0.04)
                renderer.lineWidth = 2.0
                renderer.lineDashPattern = [8, 6]
                return renderer
            }
            if let polyline = overlay as? ColoredPolyline {
                let renderer = MKPolylineRenderer(polyline: polyline)
                renderer.strokeColor = polyline.color
                renderer.lineWidth = polyline.lineWidth
                if polyline.isDashed {
                    renderer.lineDashPattern = [10, 8]
                }
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let customAnno = annotation as? CustomMapAnnotation else { return nil }
            let id = "PlannerMapAnno_\(customAnno.key)"
            var view = mapView.dequeueReusableAnnotationView(withIdentifier: id)
            if view == nil {
                view = MKAnnotationView(annotation: customAnno, reuseIdentifier: id)
            } else {
                view?.annotation = customAnno
            }
            view?.isDraggable = false
            view?.canShowCallout = (customAnno.itemType != .camera && customAnno.itemType != .target && customAnno.itemType != .label)
            view?.image = customAnno.renderedImage
            view?.centerOffset = CGPoint(x: 0, y: 0)
            return view
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let customAnno = view.annotation as? CustomMapAnnotation {
                if customAnno.itemType == .camera {
                    parent.pinMoveMode = .camera
                    mapView.deselectAnnotation(view.annotation, animated: false)
                } else if customAnno.itemType == .target {
                    parent.pinMoveMode = .target
                    mapView.deselectAnnotation(view.annotation, animated: false)
                } else if customAnno.itemType != .label {
                    if let t = customAnno.title {
                        let sub = customAnno.subtitle ?? ""
                        parent.selectedMarkerMessage = sub.isEmpty ? t : "\(t) • \(sub)"
                    }
                }
            }
        }
    }
}

// MARK: - Preset Spot Data
public struct PresetSpot: Identifiable, Equatable {
    public let id = UUID()
    public let name: String
    public let camera: GeoPoint
    public let target: GeoPoint
    public let targetHeightMeters: Double

    public static let defaults: [PresetSpot] = [
        PresetSpot(
            name: "노들섬 ➔ N서울타워",
            camera: GeoPoint(latitudeDeg: 37.5172, longitudeDeg: 126.9582, altitudeMeters: 15.0),
            target: GeoPoint(latitudeDeg: 37.5512, longitudeDeg: 126.9882, altitudeMeters: 445.0),
            targetHeightMeters: 35.0
        ),
        PresetSpot(
            name: "선유도공원 ➔ 국회의사당 돔",
            camera: GeoPoint(latitudeDeg: 37.5425, longitudeDeg: 126.9015, altitudeMeters: 16.0),
            target: GeoPoint(latitudeDeg: 37.5318, longitudeDeg: 126.9142, altitudeMeters: 20.0),
            targetHeightMeters: 30.0
        ),
        PresetSpot(
            name: "올림픽공원 ➔ 롯데월드타워",
            camera: GeoPoint(latitudeDeg: 37.5208, longitudeDeg: 127.1215, altitudeMeters: 30.0),
            target: GeoPoint(latitudeDeg: 37.5126, longitudeDeg: 127.1025, altitudeMeters: 535.0),
            targetHeightMeters: 45.0
        )
    ]
}

// MARK: - Main Shot Planner View
public struct ShotPlannerView: View {
    @ObservedObject var sharedState: PlannerSharedState
    @StateObject private var locManager = ShotPlannerLocationManager()

    @State private var now: Date = Date()
    @State private var selectedHourFloat: Float = EphemerisEngine.currentHourFloat()
    @State private var isLiveSync: Bool = true
    @State private var selectedMarkerMessage: String? = nil

    private let clockTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    // 정렬 최적 일시 검색 상태 (기간 및 달 위상 필터)
    @State private var searchDays: Int = 7
    @State private var selectedPhaseFilter: AlignmentFinder.MoonPhaseFilter = .all
    @State private var showSearchSheet: Bool = false
    @State private var showOpportunitySheet: Bool = false
    @State private var alignments: [AlignmentFinder.AlignmentEvent] = []

    // 날짜 선택 및 촬영 계획 저장/불러오기 상태
    @State private var showSavePlanSheet: Bool = false
    @State private var showSavedPlansSheet: Bool = false
    @State private var planNameInput: String = ""
    @State private var savedPlans: [ShotPlan] = []
    @State private var activePlanName: String? = nil

    private var selectedDate: Date {
        let calendar = EphemerisEngine.seoulCalendar
        let startOfDay = calendar.startOfDay(for: now)
        let clampedHours = min(max(selectedHourFloat, 0.0), 23.9833)
        let seconds = Int(clampedHours * 3600.0)
        return calendar.date(byAdding: .second, value: seconds, to: startOfDay) ?? now
    }

    private func runSearch(days: Int? = nil, filter: AlignmentFinder.MoonPhaseFilter? = nil) {
        if let d = days { searchDays = d }
        if let f = filter { selectedPhaseFilter = f }
        if sharedState.celestialTarget == .sun {
            alignments = AlignmentFinder.findSunAlignments(
                camera: sharedState.cameraPoint,
                target: sharedState.targetPoint,
                targetHeightMeters: sharedState.targetHeight,
                startDate: selectedDate,
                days: searchDays,
                toleranceDeg: 0.5
            )
        } else {
            alignments = AlignmentFinder.findMoonAlignments(
                camera: sharedState.cameraPoint,
                target: sharedState.targetPoint,
                targetHeightMeters: sharedState.targetHeight,
                startDate: selectedDate,
                days: searchDays,
                toleranceDeg: 0.4,
                phaseFilter: selectedPhaseFilter
            )
        }
        showSearchSheet = true
    }

    private var moon: MoonDetails {
        EphemerisEngine.calculateMoon(date: selectedDate, latitudeDeg: sharedState.cameraPoint.latitudeDeg, longitudeDeg: sharedState.cameraPoint.longitudeDeg)
    }

    private var sun: SunDetails {
        EphemerisEngine.calculateSun(date: selectedDate, latitudeDeg: sharedState.cameraPoint.latitudeDeg, longitudeDeg: sharedState.cameraPoint.longitudeDeg)
    }

    private var isSunTarget: Bool {
        sharedState.celestialTarget == .sun
    }

    private var targetAzimuth: Double {
        isSunTarget ? sun.azimuthDeg : moon.azimuthDeg
    }

    private var targetAltitude: Double {
        isSunTarget ? sun.altitudeDeg : moon.altitudeDeg
    }

    private var sunDayTimes: SunDayTimes {
        EphemerisEngine.calculateSunDayTimes(date: selectedDate, latitudeDeg: sharedState.cameraPoint.latitudeDeg, longitudeDeg: sharedState.cameraPoint.longitudeDeg)
    }

    private var shadow: ShadowCastResult {
        ShadowSimulator.calculateShadow(
            sunAltitudeDeg: sun.altitudeDeg,
            sunAzimuthDeg: sun.azimuthDeg,
            objectHeightMeters: sharedState.targetHeight,
            objectLocation: sharedState.targetPoint
        )
    }

    private var distance: Double {
        AlignmentFinder.calculateDistanceMeters(p1: sharedState.cameraPoint, p2: sharedState.targetPoint)
    }

    private var bearing: Double {
        AlignmentFinder.calculateBearingDeg(p1: sharedState.cameraPoint, p2: sharedState.targetPoint)
    }

    private var azDiff: Double {
        let diff = abs(targetAzimuth - bearing)
        return min(diff, 360.0 - diff)
    }

    private var isAligned: Bool {
        azDiff < 1.0 && targetAltitude > -2.0
    }

    public init(sharedState: PlannerSharedState) {
        self.sharedState = sharedState
    }

    /// 🎯 피사체를 고정하고, 현재 시각 천체(달/태양) 방향에 맞춰 카메라를 1열 일직선상으로 즉시 이동
    private func alignCameraToColinear() {
        let isSun = sharedState.celestialTarget == .sun
        let azimuth = isSun ? sun.azimuthDeg : moon.azimuthDeg
        sharedState.alignCameraToMoonLine(moonAzimuthDeg: azimuth)
        let label = isSun ? "☀️태양" : "\(moon.phase.emoji)달"
        selectedMarkerMessage = "🎯 3점 1열 카메라 정렬 완료! [📷카메라 ➔ 🗼피사체 ➔ \(label)]"
    }

    /// ⏱️ 현재 카메라-피사체 조준선에 달/태양이 1열로 통과하는 가장 가까운 시각으로 자동 이동
    private func alignTimeToCelestialTarget() {
        let isSun = sharedState.celestialTarget == .sun
        let label = isSun ? "태양" : "\(moon.phase.emoji)달"
        if let match = AlignmentFinder.findNextAzimuthAlignment(
            camera: sharedState.cameraPoint,
            target: sharedState.targetPoint,
            startDate: selectedDate,
            isSun: isSun
        ) {
            now = match.date
            selectedHourFloat = EphemerisEngine.currentHourFloat(for: match.date)
            isLiveSync = false
            selectedMarkerMessage = "🎯 3점 1열 \(label) 정렬 시각으로 이동! (각도차 \(String(format: "%.2f", match.offsetDeg))°)"
        } else {
            selectedMarkerMessage = "⚠️ 향후 48시간 내에 일직선으로 정렬되는 \(label) 궤적이 없습니다."
        }
    }

    public var body: some View {
        VStack(spacing: 0) {
            // 1. 네이티브 지도 영역 (원탭 핀 선택 & 원탭 이동 지원)
            ZStack(alignment: .top) {
                PlannerMapView(
                    cameraPoint: $sharedState.cameraPoint,
                    targetPoint: $sharedState.targetPoint,
                    pinMoveMode: $sharedState.pinMoveMode,
                    celestialTarget: sharedState.celestialTarget,
                    anchorMode: sharedState.anchorMode,
                    isDistanceLocked: sharedState.isDistanceLockedToCalculator,
                    lockedDistanceMeters: sharedState.calculatorRequiredDistance,
                    targetHeight: sharedState.targetHeight,
                    userLocation: locManager.userLocation,
                    userHeading: locManager.userHeading,
                    moon: moon,
                    sun: sun,
                    sunDayTimes: sunDayTimes,
                    shadow: shadow,
                    distance: distance,
                    bearing: bearing,
                    isAligned: isAligned,
                    centerTrigger: $sharedState.centerTrigger,
                    selectedMarkerMessage: $selectedMarkerMessage
                )
                .edgesIgnoringSafeArea(.top)

                // 상단: 미니멀 글래스 컨트롤 바 & 상태 배너
                VStack(spacing: 6) {
                    // 🌟 최상단 중앙: 큼직하고 직관적인 [ 🌕 달 플래너 | ☀️ 태양 플래너 ] 세그먼트 스위치
                    HStack(spacing: 4) {
                        let isMoon = sharedState.celestialTarget == .moon
                        Button(action: { sharedState.celestialTarget = .moon }) {
                            Text("\(moon.phase.emoji) 달 플래너")
                                .font(.system(size: 12, weight: isMoon ? .bold : .medium))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 6)
                                .background(isMoon ? Color.sunGold : Color.clear)
                                .foregroundColor(isMoon ? .nightSky : .textSecondary)
                                .cornerRadius(14)
                        }

                        let isSun = sharedState.celestialTarget == .sun
                        Button(action: { sharedState.celestialTarget = .sun }) {
                            Text("☀️ 태양 플래너")
                                .font(.system(size: 12, weight: isSun ? .bold : .medium))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 6)
                                .background(isSun ? Color(hex: 0xF59E0B) : Color.clear)
                                .foregroundColor(isSun ? .nightSky : .textSecondary)
                                .cornerRadius(14)
                        }
                    }
                    .padding(3)
                    .background(Color(hex: 0x0F172A, alpha: 0.92))
                    .cornerRadius(18)
                    .overlay(
                        RoundedRectangle(cornerRadius: 18)
                            .stroke(Color.sunGold.opacity(0.5), lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.35), radius: 6, x: 0, y: 2)
                    .padding(.top, 4)

                    // 상단 툴바: [ 좌측 핀 모드 세그먼트 | 우측 빠른 액션 아이콘들 ]
                    HStack {
                        // 1) 핀 이동 모드 스위치
                        HStack(spacing: 2) {
                            Button(action: {
                                sharedState.pinMoveMode = .camera
                                sharedState.anchorMode = .targetFixed
                            }) {
                                HStack(spacing: 3) {
                                    Image(systemName: "camera.fill")
                                    Text("카메라")
                                }
                                .font(.system(size: 11, weight: sharedState.pinMoveMode == .camera ? .bold : .medium))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 5)
                                .background(sharedState.pinMoveMode == .camera ? Color.accentBlue : Color.clear)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                            }

                            Button(action: {
                                sharedState.pinMoveMode = .target
                                sharedState.anchorMode = .cameraFixed
                            }) {
                                HStack(spacing: 3) {
                                    Image(systemName: "mappin.and.ellipse")
                                    Text("피사체")
                                }
                                .font(.system(size: 11, weight: sharedState.pinMoveMode == .target ? .bold : .medium))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 5)
                                .background(sharedState.pinMoveMode == .target ? Color(hex: 0xE11D48) : Color.clear)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                            }

                            Button(action: { sharedState.pinMoveMode = .none }) {
                                Image(systemName: sharedState.pinMoveMode == .none ? "lock.fill" : "lock.open")
                                    .font(.system(size: 11, weight: .semibold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 5)
                                    .background(sharedState.pinMoveMode == .none ? Color.sunGold : Color.clear)
                                    .foregroundColor(sharedState.pinMoveMode == .none ? .nightSky : .textSecondary)
                                    .cornerRadius(12)
                            }
                        }
                        .padding(3)
                        .background(Color(hex: 0x0F172A, alpha: 0.88))
                        .cornerRadius(16)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(Color.white.opacity(0.15), lineWidth: 1)
                        )

                        Spacer()

                        // 2) 우측 빠른 액션 아이콘들 (GPS / 저장 / 목록)
                        HStack(spacing: 6) {
                            Button(action: {
                                if !locManager.hasPermission {
                                    locManager.requestPermission()
                                } else if let uLoc = locManager.userLocation {
                                    sharedState.centerTrigger = CLLocationCoordinate2D(latitude: uLoc.latitudeDeg, longitude: uLoc.longitudeDeg)
                                }
                            }) {
                                Image(systemName: locManager.userLocation != nil ? "location.fill" : "location")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(locManager.userLocation != nil ? .accentBlue : .textSecondary)
                                    .padding(7)
                                    .background(Color(hex: 0x0F172A, alpha: 0.88))
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white.opacity(0.15), lineWidth: 1))
                            }

                            if let uLoc = locManager.userLocation {
                                Button(action: {
                                    sharedState.cameraPoint = uLoc
                                    sharedState.centerTrigger = CLLocationCoordinate2D(latitude: uLoc.latitudeDeg, longitude: uLoc.longitudeDeg)
                                }) {
                                    Image(systemName: "camera.badge.ellipsis")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.sunGold)
                                        .padding(7)
                                        .background(Color(hex: 0x0F172A, alpha: 0.88))
                                        .clipShape(Circle())
                                        .overlay(Circle().stroke(Color.sunGold.opacity(0.6), lineWidth: 1))
                                }
                            }

                            Button(action: {
                                let formatter = DateFormatter()
                                formatter.dateFormat = "M/d HH:mm"
                                formatter.timeZone = EphemerisEngine.seoulCalendar.timeZone
                                planNameInput = "\(sharedState.selectedSubjectType.displayName) 정렬 (\(formatter.string(from: selectedDate)))"
                                showSavePlanSheet = true
                            }) {
                                Image(systemName: "square.and.arrow.down")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(7)
                                    .background(Color(hex: 0x0F172A, alpha: 0.88))
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white.opacity(0.15), lineWidth: 1))
                            }

                            Button(action: {
                                savedPlans = ShotPlanStorage.loadPlans()
                                showSavedPlansSheet = true
                            }) {
                                ZStack(alignment: .topTrailing) {
                                    Image(systemName: "folder")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.sunGold)
                                        .padding(7)
                                        .background(Color(hex: 0x0F172A, alpha: 0.88))
                                        .clipShape(Circle())
                                        .overlay(Circle().stroke(Color.white.opacity(0.15), lineWidth: 1))

                                    if !savedPlans.isEmpty {
                                        Circle()
                                            .fill(Color.sunGold)
                                            .frame(width: 6, height: 6)
                                            .offset(x: -2, y: 2)
                                    }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 8)

                    // 활성 계획 배너 (불러온 계획이 있을 때만 미니멀 표시)
                    if let pName = activePlanName {
                        HStack(spacing: 6) {
                            Image(systemName: "bookmark.fill")
                                .font(.system(size: 10))
                                .foregroundColor(.sunGold)
                            Text(pName)
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white)
                            Button(action: { activePlanName = nil }) {
                                Image(systemName: "xmark")
                                    .foregroundColor(.white.opacity(0.8))
                                    .font(.system(size: 9, weight: .bold))
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color(hex: 0x047857, alpha: 0.9))
                        .cornerRadius(12)
                        .padding(.horizontal, 12)
                    }

                    // 현장 삼각대 위치 유도 (GPS 켜짐 시 컴팩트 표시 + 스마트폰 시선 방향)
                    if let uLoc = locManager.userLocation {
                        let distToCam = AlignmentFinder.calculateDistanceMeters(p1: uLoc, p2: sharedState.cameraPoint)
                        let bearingToCam = AlignmentFinder.calculateBearingDeg(p1: uLoc, p2: sharedState.cameraPoint)
                        let isArrived = distToCam < 8.0
                        let headingText = locManager.userHeading != nil ? " · 시선 \(Int(locManager.userHeading!.rounded()))°" : ""
                        HStack(spacing: 6) {
                            Image(systemName: isArrived ? "checkmark.circle.fill" : "location.north.circle.fill")
                                .font(.system(size: 10))
                                .foregroundColor(isArrived ? .sunGold : .accentBlue)
                            Text(isArrived ? "삼각대 도착 (오차 \(Int(distToCam.rounded()))m)\(headingText)" : "삼각대까지 \(distToCam >= 1000 ? String(format: "%.2fkm", distToCam / 1000.0) : "\(Int(distToCam.rounded()))m") · \(Int(bearingToCam.rounded()))°\(headingText)")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.white)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color(hex: 0x0F172A, alpha: 0.88))
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(isArrived ? Color.sunGold.opacity(0.8) : Color.accentBlue.opacity(0.4), lineWidth: 1)
                        )
                        .padding(.horizontal, 12)
                    }

                    // 마커 터치 시 미니멀 토스트 정보
                    if let msg = selectedMarkerMessage {
                        HStack(spacing: 6) {
                            Image(systemName: "info.circle.fill")
                                .foregroundColor(.sunGold)
                                .font(.system(size: 11))
                            Text(msg)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(.white)
                            Spacer()
                            Button(action: { selectedMarkerMessage = nil }) {
                                Image(systemName: "xmark")
                                    .foregroundColor(.textSecondary)
                                    .font(.system(size: 10, weight: .bold))
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color(hex: 0x0F172A, alpha: 0.95))
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.sunGold.opacity(0.6), lineWidth: 1)
                        )
                        .padding(.horizontal, 12)
                    }
                }

                // 지도 좌측 하단: 단일 플로팅 글래스 정렬 캡슐
                HStack(spacing: 6) {
                    let calcDist = sharedState.calculatorRequiredDistance
                    let calcDistStr = calcDist >= 1000 ? String(format: "%.2fkm", calcDist / 1000.0) : "\(Int(calcDist.rounded()))m"

                    Button(action: { alignCameraToColinear() }) {
                        HStack(spacing: 4) {
                            Image(systemName: "camera.viewfinder")
                            Text("카메라 1열")
                                .font(.system(size: 11, weight: .bold))
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.sunGold)
                        .foregroundColor(.nightSky)
                        .cornerRadius(16)
                    }

                    Button(action: { alignTimeToCelestialTarget() }) {
                        HStack(spacing: 4) {
                            Image(systemName: "clock.arrow.circlepath")
                            Text("시각 1열")
                                .font(.system(size: 11, weight: .bold))
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color(hex: 0x6366F1))
                        .foregroundColor(.white)
                        .cornerRadius(16)
                    }

                    Menu {
                        Button("🗼 피사체 고정 ➔ 📷 정렬 (\(calcDistStr))") {
                            sharedState.anchorMode = .targetFixed
                            sharedState.pinMoveMode = .camera
                            sharedState.alignCameraToCalculatorDistance()
                        }
                        Button("📷 카메라 고정 ➔ 🗼 정렬 (\(calcDistStr))") {
                            sharedState.anchorMode = .cameraFixed
                            sharedState.pinMoveMode = .target
                            sharedState.alignTargetToCalculatorDistance()
                        }
                        Divider()
                        Button(sharedState.isDistanceLockedToCalculator ? "거리 고정 해제 (자유 이동)" : "거리 고정 켜기 (\(calcDistStr))") {
                            sharedState.isDistanceLockedToCalculator.toggle()
                            if sharedState.isDistanceLockedToCalculator {
                                sharedState.alignToCalculatorDistance()
                            }
                        }
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: sharedState.isDistanceLockedToCalculator ? "lock.fill" : "lock.open")
                            Text(calcDistStr)
                                .font(.system(size: 11, weight: .bold))
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 6)
                        .background(sharedState.isDistanceLockedToCalculator ? Color.accentBlue : Color(hex: 0x1E293B, alpha: 0.9))
                        .foregroundColor(.white)
                        .cornerRadius(16)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(sharedState.isDistanceLockedToCalculator ? Color.sunGold.opacity(0.8) : Color.white.opacity(0.15), lineWidth: 1)
                        )
                    }
                }
                .padding(4)
                .background(Color(hex: 0x0F172A, alpha: 0.82))
                .cornerRadius(20)
                .overlay(
                    RoundedRectangle(cornerRadius: 20)
                        .stroke(Color.white.opacity(0.12), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.3), radius: 6, x: 0, y: 2)
                .padding(.leading, 12)
                .padding(.bottom, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
            .frame(maxHeight: .infinity)

            // 2. 하단 분석 패널 (미니멀 뷰파인더 HUD)
            VStack(spacing: 8) {
                // 천체 전환 토글 및 프리셋 칩 바
                HStack(spacing: 8) {
                    // [ 🌕 달 | ☀️ 태양 ] 토글 캡슐
                    HStack(spacing: 0) {
                        let isMoon = sharedState.celestialTarget == .moon
                        Button(action: { sharedState.celestialTarget = .moon }) {
                            Text("\(moon.phase.emoji) 달")
                                .font(.system(size: 11, weight: isMoon ? .bold : .medium))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 5)
                                .background(isMoon ? Color.sunGold : Color.clear)
                                .foregroundColor(isMoon ? .nightSky : .textSecondary)
                                .cornerRadius(10)
                        }

                        let isSun = sharedState.celestialTarget == .sun
                        Button(action: { sharedState.celestialTarget = .sun }) {
                            Text("☀️ 태양")
                                .font(.system(size: 11, weight: isSun ? .bold : .medium))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 5)
                                .background(isSun ? Color(hex: 0xF59E0B) : Color.clear)
                                .foregroundColor(isSun ? .nightSky : .textSecondary)
                                .cornerRadius(10)
                        }
                    }
                    .padding(2)
                    .background(Color(hex: 0x1E293B))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.white.opacity(0.12), lineWidth: 1)
                    )

                    // 프리셋 칩
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(PresetSpot.defaults) { spot in
                                let isSelected = spot == sharedState.selectedPreset && (locManager.userLocation == nil || sharedState.cameraPoint != locManager.userLocation)
                                Button(action: {
                                    sharedState.selectedPreset = spot
                                    sharedState.cameraPoint = spot.camera
                                    sharedState.targetPoint = spot.target
                                    sharedState.targetHeight = spot.targetHeightMeters
                                    sharedState.subjectHeight = Float(spot.targetHeightMeters)
                                    sharedState.centerTrigger = CLLocationCoordinate2D(latitude: spot.camera.latitudeDeg, longitude: spot.camera.longitudeDeg)
                                }) {
                                    Text(spot.name.components(separatedBy: "➔").first?.trimmingCharacters(in: .whitespaces) ?? spot.name)
                                        .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(isSelected ? Color.sunGold : Color.cardBackground)
                                        .foregroundColor(isSelected ? Color.nightSky : Color.textPrimary)
                                        .cornerRadius(12)
                                }
                            }
                        }
                    }
                }

                // HUD 카드 (미니멀 전문 뷰파인더)
                VStack(spacing: 6) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .firstTextBaseline, spacing: 4) {
                                Text(distance >= 1000 ? String(format: "%.2f", distance / 1000.0) : "\(Int(distance.rounded()))")
                                    .font(.system(size: 18, weight: .black, design: .rounded))
                                    .foregroundColor(.moonGlow)
                                Text(distance >= 1000 ? "km" : "m")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.textSecondary)
                                Text("· \(Int(bearing.rounded()))°")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundColor(.textSecondary)
                            }
                            let celestialStr = isSunTarget ?
                                "태양 \(String(format: "%.1f", sun.azimuthDeg))° · 고도 \(String(format: "%.1f", sun.altitudeDeg))°" :
                                "달 \(String(format: "%.1f", moon.azimuthDeg))° · 고도 \(String(format: "%.1f", moon.altitudeDeg))°"
                            Text(celestialStr)
                                .font(.system(size: 11))
                                .foregroundColor(.textSecondary)
                        }

                        Spacer()

                        VStack(alignment: .trailing, spacing: 3) {
                            HStack(spacing: 5) {
                                Circle()
                                    .fill(isAligned ? Color.sunGold : (azDiff < 3.0 ? Color(hex: 0xF59E0B) : Color.textSecondary.opacity(0.4)))
                                    .frame(width: 7, height: 7)
                                Text(isAligned ? "1열 일치" : String(format: "오차 %.1f°", azDiff))
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(isAligned ? .sunGold : .textPrimary)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3.5)
                            .background(isAligned ? Color.sunGold.opacity(0.18) : Color.white.opacity(0.06))
                            .cornerRadius(8)

                            if shadow.isSunAboveHorizon {
                                Text("그림자 \(String(format: "%.1f", shadow.shadowLengthMeters))m (\(String(format: "%.1f", shadow.shadowRatio))x)")
                                    .font(.system(size: 10.5))
                                    .foregroundColor(.sunGold.opacity(0.9))
                            } else {
                                Text("일몰 후")
                                    .font(.system(size: 10.5))
                                    .foregroundColor(.textSecondary)
                            }
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(isAligned ? Color(hex: 0x064E3B, alpha: 0.85) : Color.cardBackground)
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(isAligned ? Color.sunGold.opacity(0.6) : Color.white.opacity(0.06), lineWidth: 1)
                )

                // 시간 슬라이더 & 날짜 선택 & 정렬 탐색 버튼
                VStack(spacing: 4) {
                    HStack(spacing: 4) {
                        Button(action: {
                            let cal = EphemerisEngine.seoulCalendar
                            if let prevDay = cal.date(byAdding: .day, value: -1, to: now) {
                                now = prevDay
                                isLiveSync = false
                                sharedState.plannedDate = prevDay
                            }
                        }) {
                            Text("◀")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.textSecondary)
                        }

                        // 📅 네이티브 인라인 캘린더 (탭 시 시스템 팝오버 캘린더 즉시 연동 및 실시간 반영)
                        DatePicker(
                            "",
                            selection: Binding(
                                get: { selectedDate },
                                set: { newDate in
                                    let cal = EphemerisEngine.seoulCalendar
                                    now = cal.startOfDay(for: newDate)
                                    isLiveSync = false
                                    sharedState.plannedDate = newDate
                                    selectedMarkerMessage = "📅 날짜가 변경되었습니다."
                                }
                            ),
                            displayedComponents: [.date]
                        )
                        .labelsHidden()
                        .datePickerStyle(.compact)
                        .accentColor(.sunGold)

                        Button(action: {
                            let cal = EphemerisEngine.seoulCalendar
                            if let nextDay = cal.date(byAdding: .day, value: 1, to: now) {
                                now = nextDay
                                isLiveSync = false
                                sharedState.plannedDate = nextDay
                            }
                        }) {
                            Text("▶")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.textSecondary)
                        }

                        let timeText: String = {
                            if isLiveSync {
                                let formatter = DateFormatter()
                                formatter.timeZone = EphemerisEngine.seoulCalendar.timeZone
                                formatter.dateFormat = "HH:mm"
                                return formatter.string(from: now)
                            } else {
                                let totalMinutes = min(1439, max(0, Int((Double(selectedHourFloat) * 60.0).rounded())))
                                return String(format: "%02d:%02d", totalMinutes / 60, totalMinutes % 60)
                            }
                        }()
                        Text(timeText)
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .foregroundColor(.textPrimary)

                        Button(action: {
                            let current = Date()
                            now = current
                            isLiveSync = true
                            selectedHourFloat = EphemerisEngine.currentHourFloat(for: current)
                        }) {
                            Text(isLiveSync ? "🟢" : "현재")
                                .font(.system(size: 10.5, weight: .semibold))
                                .foregroundColor(.moonGlow)
                        }

                        Spacer()

                        if sharedState.celestialTarget == .moon {
                            Button(action: {
                                showOpportunitySheet = true
                            }) {
                                HStack(spacing: 3) {
                                    Text("🏆 달걸이 골든타임")
                                        .font(.system(size: 11, weight: .bold))
                                }
                                .padding(.horizontal, 9)
                                .padding(.vertical, 5)
                                .background(Color.sunGold)
                                .foregroundColor(.nightSky)
                                .cornerRadius(6)
                                .shadow(color: Color.sunGold.opacity(0.3), radius: 4, x: 0, y: 1)
                            }
                        }

                        Button(action: {
                            runSearch()
                        }) {
                            Text(sharedState.celestialTarget == .moon ? "🔍 일반 탐색" : "🔍 \(searchDays)일 정렬 탐색")
                                .font(.system(size: 10.5, weight: .bold))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 5)
                                .background(sharedState.celestialTarget == .moon ? Color.cardBackground : Color.sunGold)
                                .foregroundColor(sharedState.celestialTarget == .moon ? Color.moonGlow : Color.nightSky)
                                .cornerRadius(6)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 6)
                                        .stroke(Color.gridLine, lineWidth: sharedState.celestialTarget == .moon ? 1 : 0)
                                )
                        }
                    }

                    Slider(
                        value: $selectedHourFloat,
                        in: 0.0...24.0,
                        onEditingChanged: { isEditing in
                            if isEditing {
                                isLiveSync = false
                            }
                        }
                    )
                    .tint(.sunGold)
                }
            }
            .padding(12)
            .background(Color.surfaceDark)
        }
        .background(Color.nightSky)
        .onAppear {
            if isLiveSync {
                let current = Date()
                now = current
                selectedHourFloat = EphemerisEngine.currentHourFloat(for: current)
            }
            savedPlans = ShotPlanStorage.loadPlans()
        }
        .onReceive(clockTimer) { tick in
            if isLiveSync {
                now = tick
                selectedHourFloat = EphemerisEngine.currentHourFloat(for: tick)
            }
        }
        // 💾 계획 저장 모달 시트
        .sheet(isPresented: $showSavePlanSheet) {
            SavePlanSheetView(
                selectedDate: selectedDate,
                cameraPoint: sharedState.cameraPoint,
                targetPoint: sharedState.targetPoint,
                targetHeightMeters: sharedState.targetHeight,
                distanceMeters: distance,
                focalLengthMm: sharedState.focalLength,
                defaultName: planNameInput,
                onSave: { finalName in
                    savedPlans = ShotPlanStorage.loadPlans()
                    activePlanName = finalName
                    selectedMarkerMessage = "💾 '\(finalName)' 계획이 저장되었습니다!"
                }
            )
        }
        // 📋 저장된 촬영 계획 목록 시트
        .sheet(isPresented: $showSavedPlansSheet) {
            SavedPlansSheetView(
                onSelectPlan: { plan in
                    sharedState.cameraPoint = plan.cameraPoint
                    sharedState.targetPoint = plan.targetPoint
                    sharedState.targetHeight = plan.targetHeightMeters
                    now = plan.timestamp
                    selectedHourFloat = EphemerisEngine.currentHourFloat(for: plan.timestamp)
                    isLiveSync = false
                    sharedState.plannedDate = plan.timestamp
                    activePlanName = plan.name
                    sharedState.centerTrigger = CLLocationCoordinate2D(latitude: plan.cameraPoint.latitudeDeg, longitude: plan.cameraPoint.longitudeDeg)
                    showSavedPlansSheet = false
                    selectedMarkerMessage = "📋 '\(plan.name)' 계획을 지도에 불러왔습니다! 현장 가이드가 활성화되었습니다."
                }
            )
        }
        // 🎯 정렬 타이밍 검색 모달 시트 (기간 및 위상 필터 포함)
        .sheet(isPresented: $showSearchSheet) {
            AlignmentResultsSheet(
                events: alignments,
                searchDays: searchDays,
                selectedPhaseFilter: selectedPhaseFilter,
                isSun: sharedState.celestialTarget == .sun,
                onSearch: { days, filter in
                    runSearch(days: days, filter: filter)
                },
                onApplyEvent: { event in
                    now = event.date
                    selectedHourFloat = EphemerisEngine.currentHourFloat(for: event.date)
                    isLiveSync = false
                    sharedState.plannedDate = event.date
                    showSearchSheet = false
                    let df = DateFormatter()
                    df.dateFormat = "M/d HH:mm"
                    df.timeZone = EphemerisEngine.seoulCalendar.timeZone
                    selectedMarkerMessage = "🎯 정렬 시점(\(df.string(from: event.date)))으로 지도가 이동되었습니다."
                },
                onSavePlan: { event in
                    now = event.date
                    selectedHourFloat = EphemerisEngine.currentHourFloat(for: event.date)
                    isLiveSync = false
                    showSearchSheet = false
                    let df = DateFormatter()
                    df.dateFormat = "M/d HH:mm"
                    df.timeZone = EphemerisEngine.seoulCalendar.timeZone
                    let targetLabel = (sharedState.celestialTarget == .sun) ? "태양" : "달"
                    planNameInput = "\(sharedState.selectedSubjectType.displayName) \(targetLabel) 정렬 (\(df.string(from: event.date)))"
                    showSavePlanSheet = true
                }
            )
        }
        // 🏆 달걸이 골든타임 탐색 모달 시트 (Grade S/A/B, 블루아워, 추천 렌즈 등)
        .sheet(isPresented: $showOpportunitySheet) {
            MoonAlignmentOpportunitySheet(
                cameraPoint: sharedState.cameraPoint,
                targetPoint: sharedState.targetPoint,
                targetHeightMeters: sharedState.targetHeight,
                subjectName: sharedState.selectedSubjectType.displayName,
                onSelectOpportunity: { opp in
                    now = opp.date
                    selectedHourFloat = EphemerisEngine.currentHourFloat(for: opp.date)
                    isLiveSync = false
                    sharedState.plannedDate = opp.date
                    let df = DateFormatter()
                    df.locale = Locale(identifier: "ko_KR")
                    df.timeZone = EphemerisEngine.seoulCalendar.timeZone
                    df.dateFormat = "M/d HH:mm"
                    selectedMarkerMessage = "🏆 \(opp.grade.badgeText) 달걸이 골든타임(\(df.string(from: opp.date)), \(opp.skyStage.shortLabel))으로 이동되었습니다!"
                },
                onSaveOpportunity: { opp in
                    now = opp.date
                    selectedHourFloat = EphemerisEngine.currentHourFloat(for: opp.date)
                    isLiveSync = false
                    let df = DateFormatter()
                    df.locale = Locale(identifier: "ko_KR")
                    df.timeZone = EphemerisEngine.seoulCalendar.timeZone
                    df.dateFormat = "M/d HH:mm"
                    planNameInput = "\(sharedState.selectedSubjectType.displayName) 달걸이 (\(df.string(from: opp.date)))"
                    showSavePlanSheet = true
                }
            )
        }
    }
}

// MARK: - Save Plan Sheet View
private struct SavePlanSheetView: View {
    let selectedDate: Date
    let cameraPoint: GeoPoint
    let targetPoint: GeoPoint
    let targetHeightMeters: Double
    let distanceMeters: Double
    let focalLengthMm: Float
    let defaultName: String
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var planName: String = ""

    init(
        selectedDate: Date,
        cameraPoint: GeoPoint,
        targetPoint: GeoPoint,
        targetHeightMeters: Double,
        distanceMeters: Double,
        focalLengthMm: Float,
        defaultName: String,
        onSave: @escaping (String) -> Void
    ) {
        self.selectedDate = selectedDate
        self.cameraPoint = cameraPoint
        self.targetPoint = targetPoint
        self.targetHeightMeters = targetHeightMeters
        self.distanceMeters = distanceMeters
        self.focalLengthMm = focalLengthMm
        self.defaultName = defaultName
        self.onSave = onSave
        _planName = State(initialValue: defaultName)
    }

    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("계획 이름")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.textPrimary)

                    TextField("계획 이름을 입력하세요", text: $planName)
                        .padding(12)
                        .background(Color.cardBackground)
                        .cornerRadius(8)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(Color.sunGold.opacity(0.6), lineWidth: 1)
                        )
                        .foregroundColor(.textPrimary)
                }

                HStack(spacing: 12) {
                    Button(action: { dismiss() }) {
                        Text("취소")
                            .font(.system(size: 14, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.cardBackground)
                            .foregroundColor(.textSecondary)
                            .cornerRadius(8)
                    }

                    Button(action: {
                        let trimmed = planName.trimmingCharacters(in: .whitespacesAndNewlines)
                        let finalName = trimmed.isEmpty ? defaultName : trimmed
                        let plan = ShotPlan(
                            name: finalName,
                            timestamp: selectedDate,
                            cameraPoint: cameraPoint,
                            targetPoint: targetPoint,
                            targetHeightMeters: targetHeightMeters,
                            distanceMeters: distanceMeters,
                            focalLengthMm: focalLengthMm
                        )
                        ShotPlanStorage.savePlan(plan)
                        onSave(finalName)
                        dismiss()
                    }) {
                        Text("저장")
                            .font(.system(size: 14, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.sunGold)
                            .foregroundColor(.nightSky)
                            .cornerRadius(8)
                    }
                }
                .padding(.top, 8)

                Spacer()
            }
            .padding()
            .background(Color.nightSky.edgesIgnoringSafeArea(.all))
            .navigationTitle("촬영 계획 저장")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Saved Plans Sheet View
private struct SavedPlansSheetView: View {
    @State private var plans: [ShotPlan] = []
    let onSelectPlan: (ShotPlan) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Group {
                if plans.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "folder")
                            .font(.system(size: 44))
                            .foregroundColor(.textSecondary)
                        Text("저장된 계획이 없습니다")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.textPrimary)
                    }
                    .padding()
                } else {
                    List {
                        ForEach(plans, id: \.id) { plan in
                            SavedPlanRowView(
                                plan: plan,
                                onSelect: { onSelectPlan(plan) },
                                onDelete: {
                                    ShotPlanStorage.deletePlan(id: plan.id)
                                    plans = ShotPlanStorage.loadPlans()
                                }
                            )
                        }
                    }
                }
            }
            .navigationTitle("저장된 계획 (\(plans.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
            .onAppear {
                plans = ShotPlanStorage.loadPlans()
            }
        }
    }
}

// MARK: - Saved Plan Row View (Extracted to guarantee crisp type-checking)
private struct SavedPlanRowView: View {
    let plan: ShotPlan
    let onSelect: () -> Void
    let onDelete: () -> Void

    private var formattedDate: String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm (E)"
        f.locale = Locale(identifier: "ko_KR")
        f.timeZone = EphemerisEngine.seoulCalendar.timeZone
        return f.string(from: plan.timestamp)
    }

    private var distanceText: String {
        if plan.distanceMeters >= 1000 {
            return String(format: "%.2f km", plan.distanceMeters / 1000.0)
        } else {
            return "\(Int(plan.distanceMeters.rounded())) m"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(plan.name)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.textPrimary)
                Spacer()
                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                        .font(.system(size: 12))
                }
                .buttonStyle(BorderlessButtonStyle())
            }

            Text("\(formattedDate) · \(distanceText)")
                .font(.system(size: 11.5))
                .foregroundColor(.sunGold)

            Button(action: onSelect) {
                HStack(spacing: 4) {
                    Image(systemName: "map.fill")
                    Text("지도에 적용")
                }
                .font(.system(size: 11.5, weight: .bold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(Color.sunGold)
                .foregroundColor(.nightSky)
                .cornerRadius(6)
            }
            .buttonStyle(BorderlessButtonStyle())
            .padding(.top, 2)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Alignment Results Sheet (Enhanced with Duration & Phase Filters)
private struct AlignmentResultsSheet: View {
    let events: [AlignmentFinder.AlignmentEvent]
    let searchDays: Int
    let selectedPhaseFilter: AlignmentFinder.MoonPhaseFilter
    var isSun: Bool = false
    let onSearch: (Int, AlignmentFinder.MoonPhaseFilter) -> Void
    let onApplyEvent: (AlignmentFinder.AlignmentEvent) -> Void
    let onSavePlan: (AlignmentFinder.AlignmentEvent) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // 상단 필터 옵션 바
                VStack(spacing: 8) {
                    // 기간 선택 칩
                    HStack(spacing: 6) {
                        Text("기간:")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.textSecondary)
                        ForEach([(3, "3일"), (7, "7일(1주)"), (30, "30일(1달)"), (90, "90일(3달)")], id: \.0) { days, label in
                            let isSelected = searchDays == days
                            Button(action: {
                                onSearch(days, selectedPhaseFilter)
                            }) {
                                Text(label)
                                    .font(.system(size: 10.5, weight: isSelected ? .bold : .regular))
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 4)
                                    .background(isSelected ? Color.sunGold : Color.cardBackground)
                                    .foregroundColor(isSelected ? .nightSky : .textPrimary)
                                    .cornerRadius(6)
                            }
                        }
                        Spacer()
                    }

                    // 위상 필터 칩 (태양일 때는 불필요하므로 숨김)
                    if !isSun {
                        HStack(spacing: 6) {
                            Text("위상:")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.textSecondary)
                            ForEach(AlignmentFinder.MoonPhaseFilter.allCases) { filter in
                                let isSelected = selectedPhaseFilter == filter
                                Button(action: {
                                    onSearch(searchDays, filter)
                                }) {
                                    Text(filter.rawValue)
                                        .font(.system(size: 10, weight: isSelected ? .bold : .regular))
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 4)
                                        .background(isSelected ? Color(hex: 0x6366F1) : Color.cardBackground)
                                        .foregroundColor(isSelected ? .white : .textPrimary)
                                        .cornerRadius(6)
                                }
                            }
                            Spacer()
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color.surfaceDark)

                Divider()

                if events.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: isSun ? "sun.max.fill" : "moon.stars")
                            .font(.system(size: 40))
                            .foregroundColor(.textSecondary)
                        Text(isSun ? "선택한 기간 동안 태양-피사체 정렬 궤적이 없습니다." : "선택한 조건에 일치하는 정렬 궤적이 없습니다.")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.textPrimary)
                        Text("탐색 기간을 30일/90일로 늘리거나 카메라 핀의 위치를 조정해 보세요.")
                            .font(.system(size: 12))
                            .foregroundColor(.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 30)
                        Spacer()
                    }
                } else {
                    List {
                        ForEach(events, id: \.id) { event in
                            AlignmentEventRowView(
                                event: event,
                                onApply: { onApplyEvent(event) },
                                onSave: { onSavePlan(event) }
                            )
                        }
                    }
                }
            }
            .navigationTitle(isSun ? "🎯 태양 정렬 타이밍 (\(events.count)건)" : "🎯 달 정렬 타이밍 (\(events.count)건)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Alignment Event Row View
private struct AlignmentEventRowView: View {
    let event: AlignmentFinder.AlignmentEvent
    let onApply: () -> Void
    let onSave: () -> Void

    private var formattedDate: String {
        let f = DateFormatter()
        f.dateFormat = "M월 d일 (E) HH:mm"
        f.locale = Locale(identifier: "ko_KR")
        f.timeZone = EphemerisEngine.seoulCalendar.timeZone
        return f.string(from: event.date)
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(formattedDate)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.textPrimary)
                    if event.targetType == "SUN" {
                        Text("☀️ 태양 (일출/일몰 및 랜드마크 정렬)")
                            .font(.system(size: 12))
                            .foregroundColor(.sunGold)
                    } else {
                        Text("\(event.moonDetails.phase.displayName) (\(Int(event.moonDetails.illuminationFraction * 100))%)")
                            .font(.system(size: 12))
                            .foregroundColor(.sunGold)
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 3) {
                    Text("정렬 오차: \(String(format: "%.2f", event.angularOffsetDeg))°")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.accentBlue)
                    Text("고도 \(String(format: "%.1f", event.moonDetails.altitudeDeg))° / 방위 \(String(format: "%.1f", event.moonDetails.azimuthDeg))°")
                        .font(.system(size: 11))
                        .foregroundColor(.textSecondary)
                }
            }

            HStack(spacing: 8) {
                Button(action: onApply) {
                    HStack(spacing: 3) {
                        Image(systemName: "map.fill")
                        Text("이 시점으로 이동")
                    }
                    .font(.system(size: 11, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(Color.sunGold)
                    .foregroundColor(.nightSky)
                    .cornerRadius(6)
                }
                .buttonStyle(BorderlessButtonStyle())

                Button(action: onSave) {
                    HStack(spacing: 3) {
                        Image(systemName: "square.and.arrow.down.fill")
                        Text("계획으로 저장")
                    }
                    .font(.system(size: 11, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(Color(hex: 0x059669))
                    .foregroundColor(.white)
                    .cornerRadius(6)
                }
                .buttonStyle(BorderlessButtonStyle())
            }
        }
        .padding(.vertical, 4)
    }
}
