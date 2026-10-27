import SwiftUI
import Combine
import AVFoundation
import CoreMotion
import CoreLocation
#if canImport(UIKit)
import UIKit
#endif

// MARK: - AR Celestial Target Mode
public enum ArTargetMode: String, CaseIterable, Identifiable {
    case moon = "🌙 달 추적"
    case sun = "☀️ 태양 추적"
    case both = "🌗 전체 보기"
    
    public var id: String { rawValue }
}

// MARK: - Motion & Heading Manager with Low-Pass Smoothing & GPS
class ArMotionManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let motionManager = CMMotionManager()
    private let locationManager = CLLocationManager()
    
    // Smoothed Angles for Buttery Smooth AR
    @Published var deviceAzimuth: Double = 0.0 // 0..360 deg
    @Published var devicePitch: Double = 0.0   // -90..+90 deg
    @Published var hasCameraPermission: Bool = false
    @Published var userLatitude: Double = 37.5665 // Default: Seoul
    @Published var userLongitude: Double = 126.9780
    @Published var isLocationAvailable: Bool = false
    
    private var rawAzimuth: Double = 0.0
    private var rawPitch: Double = 0.0
    private var timer: AnyCancellable?
    
    override init() {
        super.init()
        checkCameraPermission()
        setupSensors()
        setupSmoothingLoop()
    }
    
    func checkCameraPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            hasCameraPermission = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    self?.hasCameraPermission = granted
                }
            }
        default:
            hasCameraPermission = false
        }
    }
    
    private func setupSensors() {
        // Location & Heading
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.headingFilter = 0.5
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
        }
        
        // Device Motion for 6-DoF Attitude / Pitch
        if motionManager.isDeviceMotionAvailable {
            motionManager.deviceMotionUpdateInterval = 1.0 / 60.0
            motionManager.startDeviceMotionUpdates(using: .xArbitraryCorrectedZVertical)
        }
    }
    
    private func setupSmoothingLoop() {
        // 60fps smoothing timer
        timer = Timer.publish(every: 1.0 / 60.0, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self = self else { return }
            
            if let motion = self.motionManager.deviceMotion {
                // Pitch: angle in degrees from horizon (-90 to +90)
                let pitchDeg = -motion.attitude.pitch * 180.0 / .pi
                self.rawPitch = pitchDeg
            }
            
            // Low-pass filter for smooth motion without hand jitter
            let alpha = 0.18
            self.devicePitch += (self.rawPitch - self.devicePitch) * alpha
            
            // Angular distance wrapping for azimuth
            let diffAz = ((self.rawAzimuth - self.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
            self.deviceAzimuth = (self.deviceAzimuth + diffAz * alpha + 360.0).truncatingRemainder(dividingBy: 360.0)
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        if heading >= 0 {
            self.rawAzimuth = heading
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let loc = locations.last {
            self.userLatitude = loc.coordinate.latitude
            self.userLongitude = loc.coordinate.longitude
            self.isLocationAvailable = true
        }
    }
    
    deinit {
        motionManager.stopDeviceMotionUpdates()
        locationManager.stopUpdatingHeading()
        locationManager.stopUpdatingLocation()
        timer?.cancel()
    }
}

// MARK: - Camera Background View (AVCaptureSession)
struct CameraPreviewView: UIViewRepresentable {
    class VideoView: UIView {
        override class var layerClass: AnyClass {
            AVCaptureVideoPreviewLayer.self
        }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer {
            return layer as! AVCaptureVideoPreviewLayer
        }
    }
    
    func makeUIView(context: Context) -> VideoView {
        let view = VideoView()
        view.backgroundColor = .black
        
        let session = AVCaptureSession()
        session.sessionPreset = .high
        
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: device),
           session.canAddInput(input) {
            session.addInput(input)
        }
        
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
        
        return view
    }
    
    func updateUIView(_ uiView: VideoView, context: Context) {}
}

// MARK: - High Quality AR Tracker View
public struct ArTrackerView: View {
    @ObservedObject var sharedState: PlannerSharedState
    @StateObject private var motion = ArMotionManager()
    
    @State private var targetMode: ArTargetMode = .moon
    @State private var currentDate = Date()
    @State private var previewDate = Date()
    @State private var isLiveSync: Bool = true
    @State private var selectedHourFloat: Float = EphemerisEngine.currentHourFloat()
    @State private var showTimeSlider: Bool = false
    
    private let clockTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()
    
    public init(sharedState: PlannerSharedState) {
        self.sharedState = sharedState
    }
    
    private var activeLatitude: Double {
        motion.isLocationAvailable ? motion.userLatitude : sharedState.cameraPoint.latitudeDeg
    }
    
    private var activeLongitude: Double {
        motion.isLocationAvailable ? motion.userLongitude : sharedState.cameraPoint.longitudeDeg
    }
    
    private var effectiveDate: Date {
        if isLiveSync {
            return currentDate
        } else {
            let calendar = EphemerisEngine.seoulCalendar
            let startOfDay = calendar.startOfDay(for: previewDate)
            let clampedHours = min(max(selectedHourFloat, 0.0), 23.9833)
            let seconds = Int(clampedHours * 3600.0)
            return calendar.date(byAdding: .second, value: seconds, to: startOfDay) ?? previewDate
        }
    }
    
    private var relativeDayText: String {
        let cal = EphemerisEngine.seoulCalendar
        let startOfToday = cal.startOfDay(for: Date())
        let startOfPreview = cal.startOfDay(for: previewDate)
        let days = cal.dateComponents([.day], from: startOfToday, to: startOfPreview).day ?? 0
        if days == 0 { return "오늘" }
        if days == 1 { return "내일 D+1" }
        if days > 1 { return "D+\(days)일" }
        return "D\(days)일"
    }
    
    private var formattedTimeText: String {
        let totalMinutes = min(1439, max(0, Int((Double(selectedHourFloat) * 60.0).rounded())))
        return String(format: "%02d:%02d", totalMinutes / 60, totalMinutes % 60)
    }
    
    private var moonPosition: MoonDetails {
        EphemerisEngine.calculateMoon(date: effectiveDate, latitudeDeg: activeLatitude, longitudeDeg: activeLongitude)
    }
    
    private var sunPosition: SunDetails {
        EphemerisEngine.calculateSun(date: effectiveDate, latitudeDeg: activeLatitude, longitudeDeg: activeLongitude)
    }
    
    private var sunDayTimes: SunDayTimes {
        EphemerisEngine.calculateSunDayTimes(date: effectiveDate, latitudeDeg: activeLatitude, longitudeDeg: activeLongitude)
    }
    
    // 24-hour celestial trajectory arcs
    private var hourlyTracks: [(hour: Int, sun: SunDetails, moon: MoonDetails)] {
        let calendar = EphemerisEngine.seoulCalendar
        let startOfDay = calendar.startOfDay(for: effectiveDate)
        return (0...24).map { h in
            let date = calendar.date(byAdding: .hour, value: h % 24, to: startOfDay) ?? effectiveDate
            let s = EphemerisEngine.calculateSun(date: date, latitudeDeg: activeLatitude, longitudeDeg: activeLongitude)
            let m = EphemerisEngine.calculateMoon(date: date, latitudeDeg: activeLatitude, longitudeDeg: activeLongitude)
            return (hour: h, sun: s, moon: m)
        }
    }
    
    // Angular offsets from camera center reticle
    private var dAzMoon: Double {
        ((moonPosition.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
    }
    private var dPitchMoon: Double {
        moonPosition.altitudeDeg - motion.devicePitch
    }
    private var moonAngularDist: Double {
        sqrt(dAzMoon * dAzMoon + dPitchMoon * dPitchMoon)
    }
    
    private var dAzSun: Double {
        ((sunPosition.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
    }
    private var dPitchSun: Double {
        sunPosition.altitudeDeg - motion.devicePitch
    }
    private var sunAngularDist: Double {
        sqrt(dAzSun * dAzSun + dPitchSun * dPitchSun)
    }
    
    // Target Lock-On status (< 3.0 degrees alignment)
    private var isTargetLocked: Bool {
        switch targetMode {
        case .moon: return moonAngularDist <= 3.0
        case .sun: return sunAngularDist <= 3.0
        case .both: return moonAngularDist <= 3.0 || sunAngularDist <= 3.0
        }
    }
    
    public var body: some View {
        ZStack {
            // 1. Camera Feed or Cosmic Virtual Sky Dome Fallback
            if motion.hasCameraPermission {
                CameraPreviewView()
                    .edgesIgnoringSafeArea(.all)
            } else {
                ZStack {
                    LinearGradient(
                        gradient: Gradient(colors: [
                            Color(hex: 0x020617),
                            Color(hex: 0x0F172A),
                            Color(hex: 0x1E293B)
                        ]),
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .edgesIgnoringSafeArea(.all)
                    
                    // Virtual Stars
                    GeometryReader { geo in
                        Canvas { ctx, size in
                            var rng = 42
                            for _ in 0..<80 {
                                rng = (rng * 1103515245 + 12345) & 0x7fffffff
                                let rx = CGFloat(rng % Int(size.width))
                                rng = (rng * 1103515245 + 12345) & 0x7fffffff
                                let ry = CGFloat(rng % Int(size.height * 0.75))
                                let r = CGFloat((rng % 3) + 1)
                                ctx.fill(
                                    Path(ellipseIn: CGRect(x: rx, y: ry, width: r, height: r)),
                                    with: .color(.white.opacity(Double((rng % 70) + 30) / 100.0))
                                )
                            }
                        }
                    }
                }
            }
            
            // 2. AR Canvas Overlay: Horizon, Compass, Arcs, Celestial Bodies, Reticle
            GeometryReader { geometry in
                let size = geometry.size
                let center = CGPoint(x: size.width / 2.0, y: size.height / 2.0)
                let pxPerDegX = size.width / 60.0
                let pxPerDegY = size.height / 45.0
                
                Canvas { context, _ in
                    // (A) Artificial Horizon Line
                    let horizonY = center.y + CGFloat(motion.devicePitch * pxPerDegY)
                    if horizonY >= -100 && horizonY <= size.height + 100 {
                        var horizonPath = Path()
                        horizonPath.move(to: CGPoint(x: 0, y: horizonY))
                        horizonPath.addLine(to: CGPoint(x: size.width, y: horizonY))
                        context.stroke(
                            horizonPath,
                            with: .color(Color(hex: 0x38BDF8, alpha: 0.45)),
                            style: StrokeStyle(lineWidth: 1.5, dash: [10, 8])
                        )
                        
                        // 8-Cardinal Compass Markers
                        let cardinals: [(Double, String)] = [
                            (0, "북(N)"), (45, "북동(NE)"), (90, "동(E)"), (135, "남동(SE)"),
                            (180, "남(S)"), (225, "남서(SW)"), (270, "서(W)"), (315, "북서(NW)")
                        ]
                        
                        for (deg, label) in cardinals {
                            let dAz = ((deg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                            let cardX = center.x + CGFloat(dAz * pxPerDegX)
                            if cardX >= 0 && cardX <= size.width {
                                var tickPath = Path()
                                tickPath.move(to: CGPoint(x: cardX, y: horizonY - 8))
                                tickPath.addLine(to: CGPoint(x: cardX, y: horizonY + 8))
                                context.stroke(tickPath, with: .color(.white.opacity(0.85)), lineWidth: 1.5)
                                
                                let text = Text(label)
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.white.opacity(0.9))
                                context.draw(text, at: CGPoint(x: cardX, y: horizonY - 18))
                            }
                        }
                    }
                    
                    // (B) 24-Hour Sun & Moon Celestial Arcs
                    let showSun = (targetMode == .sun || targetMode == .both)
                    let showMoon = (targetMode == .moon || targetMode == .both)
                    
                    var sunPoints: [CGPoint] = []
                    var moonPoints: [CGPoint] = []
                    
                    for track in hourlyTracks {
                        if showSun {
                            let dAzS = ((track.sun.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                            let dPitchS = track.sun.altitudeDeg - motion.devicePitch
                            let sx = center.x + CGFloat(dAzS * pxPerDegX)
                            let sy = center.y - CGFloat(dPitchS * pxPerDegY)
                            if dAzS >= -90 && dAzS <= 90 {
                                sunPoints.append(CGPoint(x: sx, y: sy))
                            }
                        }
                        
                        if showMoon {
                            let dAzM = ((track.moon.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                            let dPitchM = track.moon.altitudeDeg - motion.devicePitch
                            let mx = center.x + CGFloat(dAzM * pxPerDegX)
                            let my = center.y - CGFloat(dPitchM * pxPerDegY)
                            if dAzM >= -90 && dAzM <= 90 {
                                moonPoints.append(CGPoint(x: mx, y: my))
                            }
                        }
                    }
                    
                    if showSun && sunPoints.count >= 2 {
                        var sunPath = Path()
                        sunPath.move(to: sunPoints[0])
                        for pt in sunPoints.dropFirst() {
                            sunPath.addLine(to: pt)
                        }
                        context.stroke(
                            sunPath,
                            with: .color(ColorTheme.sunGold.opacity(0.55)),
                            style: StrokeStyle(lineWidth: 2.5, dash: [10, 8])
                        )
                    }
                    
                    if showMoon && moonPoints.count >= 2 {
                        var moonPath = Path()
                        moonPath.move(to: moonPoints[0])
                        for pt in moonPoints.dropFirst() {
                            moonPath.addLine(to: pt)
                        }
                        context.stroke(
                            moonPath,
                            with: .color(ColorTheme.moonGlow.opacity(0.55)),
                            style: StrokeStyle(lineWidth: 2.5, dash: [10, 8])
                        )
                    }
                    
                    // (C) Moon Body & Off-screen Indicator
                    if showMoon {
                        let moonX = center.x + CGFloat(dAzMoon * pxPerDegX)
                        let moonY = center.y - CGFloat(dPitchMoon * pxPerDegY)
                        let isMoonOnScreen = moonX >= 40 && moonX <= size.width - 40 && moonY >= 40 && moonY <= size.height - 40
                        
                        if isMoonOnScreen {
                            // Lunar Halo & Core
                            context.fill(
                                Path(ellipseIn: CGRect(x: moonX - 36, y: moonY - 36, width: 72, height: 72)),
                                with: .color(ColorTheme.moonGlow.opacity(0.25))
                            )
                            context.fill(
                                Path(ellipseIn: CGRect(x: moonX - 22, y: moonY - 22, width: 44, height: 44)),
                                with: .color(ColorTheme.moonSurface)
                            )
                            context.stroke(
                                Path(ellipseIn: CGRect(x: moonX - 24, y: moonY - 24, width: 48, height: 48)),
                                with: .color(ColorTheme.moonGlow),
                                lineWidth: 2
                            )
                            
                            let phasePercent = Int((moonPosition.illuminationFraction * 100.0).rounded())
                            let label = Text("\(moonPosition.phase.emoji) \(moonPosition.phase.displayName) (\(phasePercent)%) · \(String(format: "%.1f", moonPosition.altitudeDeg))°")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white)
                            context.draw(label, at: CGPoint(x: moonX, y: moonY + 40))
                        } else if targetMode == .moon || targetMode == .both {
                            // Dynamic 360° Off-screen Radar Arrow
                            let edgeMargin: CGFloat = 46.0
                            let clampedX = min(max(moonX, edgeMargin), size.width - edgeMargin)
                            let clampedY = min(max(moonY, edgeMargin + 85), size.height - edgeMargin - 100)
                            
                            let arrow = dAzMoon > 0 ? "오른쪽 ▶" : "◀ 왼쪽"
                            let dist = Int(round(moonAngularDist))
                            
                            context.fill(
                                Path(roundedRect: CGRect(x: clampedX - 42, y: clampedY - 18, width: 84, height: 36), cornerRadius: 18),
                                with: .color(Color(hex: 0x1E1B4B, alpha: 0.92))
                            )
                            context.stroke(
                                Path(roundedRect: CGRect(x: clampedX - 42, y: clampedY - 18, width: 84, height: 36), cornerRadius: 18),
                                with: .color(ColorTheme.moonGlow),
                                lineWidth: 1.5
                            )
                            
                            let guideText = Text("🌙 \(arrow) \(dist)°")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(ColorTheme.moonGlow)
                            context.draw(guideText, at: CGPoint(x: clampedX, y: clampedY))
                        }
                    }
                    
                    // (D) Sun Body & Off-screen Indicator
                    if showSun {
                        let sunX = center.x + CGFloat(dAzSun * pxPerDegX)
                        let sunY = center.y - CGFloat(dPitchSun * pxPerDegY)
                        let isSunOnScreen = sunX >= 40 && sunX <= size.width - 40 && sunY >= 40 && sunY <= size.height - 40
                        
                        if isSunOnScreen {
                            // Solar Corona & Radiant Core
                            context.fill(
                                Path(ellipseIn: CGRect(x: sunX - 42, y: sunY - 42, width: 84, height: 84)),
                                with: .color(ColorTheme.sunGold.opacity(0.3))
                            )
                            context.fill(
                                Path(ellipseIn: CGRect(x: sunX - 24, y: sunY - 24, width: 48, height: 48)),
                                with: .color(ColorTheme.sunGold)
                            )
                            context.stroke(
                                Path(ellipseIn: CGRect(x: sunX - 26, y: sunY - 26, width: 52, height: 52)),
                                with: .color(Color(hex: 0xFEF08A)),
                                lineWidth: 2
                            )
                            
                            let sunLabel = Text("☀️ 태양 (고도 \(String(format: "%.1f", sunPosition.altitudeDeg))°)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white)
                            context.draw(sunLabel, at: CGPoint(x: sunX, y: sunY + 42))
                        } else if targetMode == .sun || targetMode == .both {
                            let edgeMargin: CGFloat = 46.0
                            let clampedX = min(max(sunX, edgeMargin), size.width - edgeMargin)
                            let clampedY = min(max(sunY, edgeMargin + 85), size.height - edgeMargin - 100)
                            
                            let arrow = dAzSun > 0 ? "오른쪽 ▶" : "◀ 왼쪽"
                            let dist = Int(round(sunAngularDist))
                            
                            context.fill(
                                Path(roundedRect: CGRect(x: clampedX - 42, y: clampedY - 18, width: 84, height: 36), cornerRadius: 18),
                                with: .color(Color(hex: 0x78350F, alpha: 0.92))
                            )
                            context.stroke(
                                Path(roundedRect: CGRect(x: clampedX - 42, y: clampedY - 18, width: 84, height: 36), cornerRadius: 18),
                                with: .color(ColorTheme.sunGold),
                                lineWidth: 1.5
                            )
                            
                            let guideText = Text("☀️ \(arrow) \(dist)°")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.white)
                            context.draw(guideText, at: CGPoint(x: clampedX, y: clampedY))
                        }
                    }
                    
                    // (E) Center Crosshair Reticle & Target Lock-on Effect
                    let crosshair: CGFloat = 20.0
                    var chPath = Path()
                    chPath.move(to: CGPoint(x: center.x - crosshair, y: center.y))
                    chPath.addLine(to: CGPoint(x: center.x - 6, y: center.y))
                    chPath.move(to: CGPoint(x: center.x + 6, y: center.y))
                    chPath.addLine(to: CGPoint(x: center.x + crosshair, y: center.y))
                    chPath.move(to: CGPoint(x: center.x, y: center.y - crosshair))
                    chPath.addLine(to: CGPoint(x: center.x, y: center.y - 6))
                    chPath.move(to: CGPoint(x: center.x, y: center.y + 6))
                    chPath.addLine(to: CGPoint(x: center.x, y: center.y + crosshair))
                    
                    let reticleColor: Color = isTargetLocked ? Color(hex: 0x22C55E) : Color.white.opacity(0.7)
                    context.stroke(chPath, with: .color(reticleColor), lineWidth: 2)
                    
                    // Reticle Circle
                    let ringRadius: CGFloat = isTargetLocked ? 32.0 : 24.0
                    let ringStyle = isTargetLocked ? StrokeStyle(lineWidth: 2.5) : StrokeStyle(lineWidth: 1.2, dash: [4, 4])
                    context.stroke(
                        Path(ellipseIn: CGRect(x: center.x - ringRadius, y: center.y - ringRadius, width: ringRadius * 2, height: ringRadius * 2)),
                        with: .color(reticleColor),
                        style: ringStyle
                    )
                    
                    // Lock-On Badge
                    if isTargetLocked {
                        let lockedTarget = (targetMode == .sun) ? "☀️ 태양" : "🌙 달"
                        let errDist = (targetMode == .sun) ? sunAngularDist : moonAngularDist
                        let lockText = Text("🎯 \(lockedTarget) 정렬 일치 (오차 \(String(format: "%.1f", errDist))°)")
                            .font(.system(size: 11, weight: .black))
                            .foregroundColor(Color(hex: 0x22C55E))
                        context.draw(lockText, at: CGPoint(x: center.x, y: center.y + ringRadius + 18))
                    }
                }
            }
            
            // 3. Top HUD: Target Switcher & Quick Compass Bar
            VStack(spacing: 8) {
                // (A) Target Mode Switcher (Moon vs Sun vs Both)
                HStack(spacing: 6) {
                    ForEach(ArTargetMode.allCases) { mode in
                        let isSelected = targetMode == mode
                        Button(action: {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                targetMode = mode
                                sharedState.celestialTarget = (mode == .sun) ? .sun : .moon
                            }
                        }) {
                            Text(mode.rawValue)
                                .font(.system(size: 12, weight: isSelected ? .bold : .medium))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 7)
                                .background(
                                    isSelected
                                        ? (mode == .sun ? ColorTheme.sunGold : (mode == .moon ? Color.cardBackground : Color(hex: 0x38BDF8)))
                                        : Color.surfaceDark.opacity(0.85)
                                )
                                .foregroundColor(isSelected ? (mode == .sun ? ColorTheme.nightSky : ColorTheme.moonGlow) : ColorTheme.textSecondary)
                                .cornerRadius(20)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 20)
                                        .stroke(isSelected ? Color.white.opacity(0.4) : Color.white.opacity(0.1), lineWidth: 1)
                                )
                        }
                    }
                }
                .padding(4)
                .background(Color.nightSky.opacity(0.6))
                .cornerRadius(24)
                
                // (B) Direction & Attitude Status Banner
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text("🧭 조준 방향: \(Int(round(motion.deviceAzimuth)))°")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(ColorTheme.textPrimary)
                            Text("고도 \(Int(round(motion.devicePitch)))°")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(ColorTheme.accentBlue)
                        }
                        
                        let targetInfo: String = {
                            if targetMode == .sun {
                                let stage = sunPosition.altitudeDeg >= -4.0 && sunPosition.altitudeDeg <= 2.0 ? "🌅 골든아워" : (sunPosition.altitudeDeg >= -8.0 && sunPosition.altitudeDeg < -4.0 ? "🌟 블루아워" : (sunPosition.altitudeDeg > 2.0 ? "☀️ 주간" : "🌙 야간"))
                                return "☀️ 태양 방위 \(Int(round(sunPosition.azimuthDeg)))° · 고도 \(String(format: "%.1f", sunPosition.altitudeDeg))° (\(stage))"
                            } else {
                                let phasePct = Int((moonPosition.illuminationFraction * 100.0).rounded())
                                return "🌙 달 방위 \(Int(round(moonPosition.azimuthDeg)))° · 고도 \(String(format: "%.1f", moonPosition.altitudeDeg))° (\(phasePct)%)"
                            }
                        }()
                        
                        Text(targetInfo)
                            .font(.system(size: 11))
                            .foregroundColor(ColorTheme.moonGlow)
                    }
                    
                    Spacer()
                    
                    // Time travel toggle button
                    Button(action: {
                        withAnimation { showTimeSlider.toggle() }
                    }) {
                        HStack(spacing: 3) {
                            Image(systemName: showTimeSlider ? "clock.fill" : "clock")
                                .font(.system(size: 11))
                            Text(isLiveSync ? "실시간" : "\(formattedTimeText) (\(relativeDayText))")
                                .font(.system(size: 11, weight: .bold))
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(isLiveSync ? ColorTheme.surfaceDark.opacity(0.85) : ColorTheme.sunGold)
                        .foregroundColor(isLiveSync ? ColorTheme.moonGlow : ColorTheme.nightSky)
                        .cornerRadius(8)
                    }
                }
                .padding(12)
                .background(ColorTheme.surfaceDark.opacity(0.92))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.white.opacity(0.12), lineWidth: 1)
                )
                
                // (C) Rich Date & Time Scrubbing Simulator
                if showTimeSlider {
                    VStack(spacing: 8) {
                        // 1. Planner Date/Time Sync Chip (if plannedDate available)
                        if let planned = sharedState.plannedDate {
                            let df = DateFormatter()
                            df.dateFormat = "M/d HH:mm"
                            df.timeZone = EphemerisEngine.seoulCalendar.timeZone
                            Button(action: {
                                previewDate = planned
                                selectedHourFloat = EphemerisEngine.currentHourFloat(for: planned)
                                isLiveSync = false
                            }) {
                                HStack(spacing: 5) {
                                    Image(systemName: "calendar.badge.clock")
                                        .font(.system(size: 11, weight: .bold))
                                    Text("📋 플래너 추천 일시 (\(df.string(from: planned))) 적용")
                                        .font(.system(size: 11, weight: .bold))
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                                .background(ColorTheme.sunGold.opacity(0.18))
                                .foregroundColor(ColorTheme.sunGold)
                                .cornerRadius(8)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(ColorTheme.sunGold.opacity(0.6), lineWidth: 1)
                                )
                            }
                        }
                        
                        // 2. Date Navigation Bar: [ ◀ -1일 ] [ 📅 DatePicker ] [ +1일 ▶ ] [ 🟢 실시간 ]
                        HStack(spacing: 5) {
                            Button(action: {
                                let cal = EphemerisEngine.seoulCalendar
                                if let prev = cal.date(byAdding: .day, value: -1, to: previewDate) {
                                    previewDate = prev
                                    isLiveSync = false
                                }
                            }) {
                                Text("◀ -1일")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 5)
                                    .background(ColorTheme.surfaceDark)
                                    .cornerRadius(6)
                            }
                            
                            // Native Compact DatePicker
                            DatePicker(
                                "",
                                selection: Binding(
                                    get: { previewDate },
                                    set: { newDate in
                                        previewDate = newDate
                                        isLiveSync = false
                                    }
                                ),
                                displayedComponents: [.date]
                            )
                            .labelsHidden()
                            .datePickerStyle(.compact)
                            .accentColor(ColorTheme.sunGold)
                            
                            Button(action: {
                                let cal = EphemerisEngine.seoulCalendar
                                if let next = cal.date(byAdding: .day, value: 1, to: previewDate) {
                                    previewDate = next
                                    isLiveSync = false
                                }
                            }) {
                                Text("+1일 ▶")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 5)
                                    .background(ColorTheme.surfaceDark)
                                    .cornerRadius(6)
                            }
                            
                            Spacer()
                            
                            Button(action: {
                                isLiveSync = true
                                let now = Date()
                                currentDate = now
                                previewDate = now
                                selectedHourFloat = EphemerisEngine.currentHourFloat(for: now)
                            }) {
                                Text(isLiveSync ? "🟢 실시간" : "현재로 복귀")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(isLiveSync ? Color(hex: 0x22C55E) : ColorTheme.sunGold)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 5)
                                    .background(ColorTheme.surfaceDark)
                                    .cornerRadius(6)
                            }
                        }
                        
                        // 3. Time Scrubbing Slider
                        VStack(spacing: 2) {
                            HStack {
                                Text("시간 프리뷰:")
                                    .font(.system(size: 11))
                                    .foregroundColor(ColorTheme.textSecondary)
                                Text("\(formattedTimeText) (\(relativeDayText))")
                                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                                    .foregroundColor(ColorTheme.sunGold)
                                Spacer()
                            }
                            
                            Slider(
                                value: $selectedHourFloat,
                                in: 0.0...24.0,
                                onEditingChanged: { editing in
                                    if editing { isLiveSync = false }
                                }
                            )
                            .accentColor(ColorTheme.sunGold)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(ColorTheme.cardBackground.opacity(0.95))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.white.opacity(0.15), lineWidth: 1)
                    )
                }
                
                if !motion.hasCameraPermission {
                    HStack {
                        Image(systemName: "camera.fill")
                            .font(.system(size: 11))
                        Text("실제 카메라 뷰를 보려면 카메라 권한이 필요합니다.")
                            .font(.system(size: 11))
                        Spacer()
                        Button("권한 허용") {
                            #if canImport(UIKit)
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                            #endif
                        }
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(ColorTheme.nightSky)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(ColorTheme.sunGold)
                        .cornerRadius(6)
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(hex: 0x78350F, alpha: 0.92))
                    .cornerRadius(10)
                }
                
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
        }
        .onAppear {
            currentDate = Date()
            targetMode = (sharedState.celestialTarget == .sun) ? .sun : .moon
            if let planned = sharedState.plannedDate {
                previewDate = planned
                selectedHourFloat = EphemerisEngine.currentHourFloat(for: planned)
                isLiveSync = false
            } else {
                previewDate = Date()
                selectedHourFloat = EphemerisEngine.currentHourFloat(for: currentDate)
            }
        }
        .onReceive(clockTimer) { tick in
            if isLiveSync {
                currentDate = tick
                previewDate = tick
                selectedHourFloat = EphemerisEngine.currentHourFloat(for: tick)
            }
        }
    }
}
