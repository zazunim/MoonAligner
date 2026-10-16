import SwiftUI
import Combine
import AVFoundation
import CoreMotion
import CoreLocation

// MARK: - Motion & Heading Manager
class ArMotionManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let motionManager = CMMotionManager()
    private let locationManager = CLLocationManager()
    
    @Published var deviceAzimuth: Double = 0.0 // 0..360 deg (True/Magnetic North)
    @Published var devicePitch: Double = 0.0   // -90..+90 deg (Elevation)
    @Published var hasCameraPermission: Bool = false
    
    override init() {
        super.init()
        checkCameraPermission()
        setupSensors()
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
        // Location manager for compass heading
        locationManager.delegate = self
        locationManager.headingFilter = 0.5
        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
        }
        
        // Device motion for tilt/pitch
        if motionManager.isDeviceMotionAvailable {
            motionManager.deviceMotionUpdateInterval = 1.0 / 30.0
            motionManager.startDeviceMotionUpdates(using: .xArbitraryCorrectedZVertical, to: .main) { [weak self] motion, _ in
                guard let self = self, let motion = motion else { return }
                
                // Device pitch angle (-90 to +90 degrees)
                // When device is held vertically upright pointing forward, pitch is ~ 0
                let pitchDeg = motion.attitude.pitch * 180.0 / .pi
                self.devicePitch = -pitchDeg
            }
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        if heading >= 0 {
            self.deviceAzimuth = heading
        }
    }
    
    deinit {
        motionManager.stopDeviceMotionUpdates()
        locationManager.stopUpdatingHeading()
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

// MARK: - AR Tracker Main View
struct ArTrackerView: View {
    @StateObject private var motion = ArMotionManager()
    
    // Seoul Default Coords
    private let latitude = 37.5665
    private let longitude = 126.9780
    
    @State private var currentDate = Date()
    private let clockTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()
    
    private var moonPosition: MoonDetails {
        EphemerisEngine.calculateMoon(date: currentDate, latitudeDeg: latitude, longitudeDeg: longitude)
    }
    
    private var sunPosition: SunDetails {
        EphemerisEngine.calculateSun(date: currentDate, latitudeDeg: latitude, longitudeDeg: longitude)
    }
    
    // 24-hour celestial trajectory arcs
    private var hourlyTracks: [(hour: Int, sun: SunDetails, moon: MoonDetails)] {
        let calendar = EphemerisEngine.seoulCalendar
        let startOfDay = calendar.startOfDay(for: currentDate)
        return (0...24).map { h in
            let date = calendar.date(byAdding: .hour, value: h % 24, to: startOfDay) ?? currentDate
            let s = EphemerisEngine.calculateSun(date: date, latitudeDeg: latitude, longitudeDeg: longitude)
            let m = EphemerisEngine.calculateMoon(date: date, latitudeDeg: latitude, longitudeDeg: longitude)
            return (hour: h, sun: s, moon: m)
        }
    }
    
    var body: some View {
        ZStack {
            // 1. Background: Real Camera or Virtual Sky Dome
            if motion.hasCameraPermission {
                CameraPreviewView()
                    .edgesIgnoringSafeArea(.all)
            } else {
                LinearGradient(
                    gradient: Gradient(colors: [
                        Color(hex: "020617"), // Deep Space
                        Color(hex: "0F172A"), // Midnight Blue
                        Color(hex: "1E293B")  // Horizon Twilight
                    ]),
                    startPoint: .top,
                    endPoint: .bottom
                )
                .edgesIgnoringSafeArea(.all)
            }
            
            // 2. AR Canvas: Celestial Bodies, Arcs, Horizon, Compass
            GeometryReader { geometry in
                let size = geometry.size
                let center = CGPoint(x: size.width / 2.0, y: size.height / 2.0)
                let pxPerDegX = size.width / 60.0
                let pxPerDegY = size.height / 45.0
                
                Canvas { context, _ in
                    // (A) Horizon Line
                    let horizonY = center.y + CGFloat(motion.devicePitch * pxPerDegY)
                    if horizonY >= -100 && horizonY <= size.height + 100 {
                        var horizonPath = Path()
                        horizonPath.move(to: CGPoint(x: 0, y: horizonY))
                        horizonPath.addLine(to: CGPoint(x: size.width, y: horizonY))
                        context.stroke(
                            horizonPath,
                            with: .color(Color(white: 0.6, opacity: 0.5)),
                            style: StrokeStyle(lineWidth: 2, dash: [8, 8])
                        )
                        
                        // 8-Cardinal Compass Markers
                        let cardinals: [(Double, String)] = [
                            (0, "북 (N)"), (45, "북동 (NE)"), (90, "동 (E)"), (135, "남동 (SE)"),
                            (180, "남 (S)"), (225, "남서 (SW)"), (270, "서 (W)"), (315, "북서 (NW)")
                        ]
                        
                        for (deg, label) in cardinals {
                            let dAz = ((deg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                            let cardX = center.x + CGFloat(dAz * pxPerDegX)
                            if cardX >= 0 && cardX <= size.width {
                                var tickPath = Path()
                                tickPath.move(to: CGPoint(x: cardX, y: horizonY - 10))
                                tickPath.addLine(to: CGPoint(x: cardX, y: horizonY + 10))
                                context.stroke(tickPath, with: .color(.white.opacity(0.8)), lineWidth: 2)
                                
                                let text = Text(label).font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                                context.draw(text, at: CGPoint(x: cardX, y: horizonY - 18))
                            }
                        }
                    }
                    
                    // (B) 24-Hour Sun & Moon Arcs
                    var sunPoints: [CGPoint] = []
                    var moonPoints: [CGPoint] = []
                    
                    for track in hourlyTracks {
                        let dAzS = ((track.sun.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                        let dPitchS = track.sun.altitudeDeg - motion.devicePitch
                        let sx = center.x + CGFloat(dAzS * pxPerDegX)
                        let sy = center.y - CGFloat(dPitchS * pxPerDegY)
                        if dAzS >= -90 && dAzS <= 90 {
                            sunPoints.append(CGPoint(x: sx, y: sy))
                        }
                        
                        let dAzM = ((track.moon.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                        let dPitchM = track.moon.altitudeDeg - motion.devicePitch
                        let mx = center.x + CGFloat(dAzM * pxPerDegX)
                        let my = center.y - CGFloat(dPitchM * pxPerDegY)
                        if dAzM >= -90 && dAzM <= 90 {
                            moonPoints.append(CGPoint(x: mx, y: my))
                        }
                    }
                    
                    if sunPoints.count >= 2 {
                        var sunPath = Path()
                        sunPath.move(to: sunPoints[0])
                        for pt in sunPoints.dropFirst() {
                            sunPath.addLine(to: pt)
                        }
                        context.stroke(sunPath, with: .color(ColorTheme.sunGold.opacity(0.5)), style: StrokeStyle(lineWidth: 3, dash: [10, 8]))
                    }
                    
                    if moonPoints.count >= 2 {
                        var moonPath = Path()
                        moonPath.move(to: moonPoints[0])
                        for pt in moonPoints.dropFirst() {
                            moonPath.addLine(to: pt)
                        }
                        context.stroke(moonPath, with: .color(ColorTheme.moonGlow.opacity(0.5)), style: StrokeStyle(lineWidth: 3, dash: [10, 8]))
                    }
                    
                    // (C) Moon Rendering & Off-screen indicator
                    let dAzMoon = ((moonPosition.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                    let dPitchMoon = moonPosition.altitudeDeg - motion.devicePitch
                    let moonX = center.x + CGFloat(dAzMoon * pxPerDegX)
                    let moonY = center.y - CGFloat(dPitchMoon * pxPerDegY)
                    let isMoonOnScreen = moonX >= 40 && moonX <= size.width - 40 && moonY >= 40 && moonY <= size.height - 40
                    
                    if isMoonOnScreen {
                        let moonPoint = CGPoint(x: moonX, y: moonY)
                        context.fill(Path(ellipseIn: CGRect(x: moonPoint.x - 34, y: moonPoint.y - 34, width: 68, height: 68)), with: .color(ColorTheme.moonGlow.opacity(0.35)))
                        context.fill(Path(ellipseIn: CGRect(x: moonPoint.x - 22, y: moonPoint.y - 22, width: 44, height: 44)), with: .color(ColorTheme.moonSurface))
                        context.stroke(Path(ellipseIn: CGRect(x: moonPoint.x - 26, y: moonPoint.y - 26, width: 52, height: 52)), with: .color(.white), lineWidth: 2)
                        
                        let label = Text("🌕 \(moonPosition.phase.displayName) (고도 \(Int(round(moonPosition.altitudeDeg)))°)")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.white)
                        context.draw(label, at: CGPoint(x: moonX, y: moonY + 44))
                    } else {
                        // Off-screen guide arrow
                        let edgeMargin: CGFloat = 45.0
                        let clampedX = min(max(moonX, edgeMargin), size.width - edgeMargin)
                        let clampedY = min(max(moonY, edgeMargin + 90), size.height - edgeMargin)
                        let arrow = dAzMoon > 0 ? "➔" : "◀"
                        let dist = Int(abs(round(dAzMoon)))
                        
                        context.fill(Path(ellipseIn: CGRect(x: clampedX - 22, y: clampedY - 22, width: 44, height: 44)), with: .color(Color(hex: "1E1B4B").opacity(0.9)))
                        context.stroke(Path(ellipseIn: CGRect(x: clampedX - 22, y: clampedY - 22, width: 44, height: 44)), with: .color(ColorTheme.moonGlow), lineWidth: 2)
                        
                        let guideText = Text("🌕 \(arrow) \(dist)°").font(.system(size: 10, weight: .bold)).foregroundColor(.yellow)
                        context.draw(guideText, at: CGPoint(x: clampedX, y: clampedY))
                    }
                    
                    // (D) Sun Rendering & Off-screen indicator
                    let dAzSun = ((sunPosition.azimuthDeg - motion.deviceAzimuth + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
                    let dPitchSun = sunPosition.altitudeDeg - motion.devicePitch
                    let sunX = center.x + CGFloat(dAzSun * pxPerDegX)
                    let sunY = center.y - CGFloat(dPitchSun * pxPerDegY)
                    let isSunOnScreen = sunX >= 40 && sunX <= size.width - 40 && sunY >= 40 && sunY <= size.height - 40
                    
                    if isSunOnScreen {
                        let sunPoint = CGPoint(x: sunX, y: sunY)
                        context.fill(Path(ellipseIn: CGRect(x: sunPoint.x - 38, y: sunPoint.y - 38, width: 76, height: 76)), with: .color(ColorTheme.sunGold.opacity(0.4)))
                        context.fill(Path(ellipseIn: CGRect(x: sunPoint.x - 24, y: sunPoint.y - 24, width: 48, height: 48)), with: .color(ColorTheme.sunGold))
                        context.stroke(Path(ellipseIn: CGRect(x: sunPoint.x - 28, y: sunPoint.y - 28, width: 56, height: 56)), with: .color(Color(hex: "FEF08A")), lineWidth: 2)
                        
                        let label = Text("☀️ 태양 (고도 \(Int(round(sunPosition.altitudeDeg)))°)")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.white)
                        context.draw(label, at: CGPoint(x: sunX, y: sunY + 46))
                    } else {
                        // Off-screen guide arrow
                        let edgeMargin: CGFloat = 45.0
                        let clampedX = min(max(sunX, edgeMargin), size.width - edgeMargin)
                        let clampedY = min(max(sunY, edgeMargin + 90), size.height - edgeMargin)
                        let arrow = dAzSun > 0 ? "➔" : "◀"
                        let dist = Int(abs(round(dAzSun)))
                        
                        context.fill(Path(ellipseIn: CGRect(x: clampedX - 22, y: clampedY - 22, width: 44, height: 44)), with: .color(Color(hex: "78350F").opacity(0.9)))
                        context.stroke(Path(ellipseIn: CGRect(x: clampedX - 22, y: clampedY - 22, width: 44, height: 44)), with: .color(ColorTheme.sunGold), lineWidth: 2)
                        
                        let guideText = Text("☀️ \(arrow) \(dist)°").font(.system(size: 10, weight: .bold)).foregroundColor(.white)
                        context.draw(guideText, at: CGPoint(x: clampedX, y: clampedY))
                    }
                    
                    // (E) Central Crosshair Reticle
                    let crosshair: CGFloat = 22.0
                    var chPath = Path()
                    chPath.move(to: CGPoint(x: center.x - crosshair, y: center.y))
                    chPath.addLine(to: CGPoint(x: center.x + crosshair, y: center.y))
                    chPath.move(to: CGPoint(x: center.x, y: center.y - crosshair))
                    chPath.addLine(to: CGPoint(x: center.x, y: center.y + crosshair))
                    context.stroke(chPath, with: .color(.white.opacity(0.65)), lineWidth: 2)
                }
            }
            
            // 3. Top HUD Status Card
            VStack(spacing: 8) {
                VStack(spacing: 6) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("조준 방향: \(Int(round(motion.deviceAzimuth)))° (고도 \(Int(round(motion.devicePitch)))°)")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(ColorTheme.textPrimary)
                            
                            Text("☀️ 태양 \(Int(round(sunPosition.azimuthDeg)))°(고도 \(Int(round(sunPosition.altitudeDeg)))°) | 🌕 달 \(Int(round(moonPosition.azimuthDeg)))°(고도 \(Int(round(moonPosition.altitudeDeg)))°)")
                                .font(.system(size: 12))
                                .foregroundColor(ColorTheme.moonGlow)
                        }
                        Spacer()
                        Text(sunPosition.altitudeDeg > 0 ? "☀️ 주간" : "🌙 야간")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(sunPosition.altitudeDeg > 0 ? ColorTheme.sunGold : ColorTheme.accentOrange)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(ColorTheme.surfaceDark.opacity(0.6))
                            .cornerRadius(8)
                    }
                }
                .padding(14)
                .background(ColorTheme.surfaceDark.opacity(0.88))
                .cornerRadius(14)
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(Color.white.opacity(0.1), lineWidth: 1)
                )
                
                if !motion.hasCameraPermission {
                    HStack {
                        Text("💡 실제 카메라 배경을 보려면 권한이 필요합니다")
                            .font(.system(size: 11))
                            .foregroundColor(.white)
                        Spacer()
                        Button("권한 설정") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(ColorTheme.nightSky)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(ColorTheme.sunGold)
                        .cornerRadius(6)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(hex: "78350F").opacity(0.9))
                    .cornerRadius(10)
                }
                
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
        }
        .onAppear {
            currentDate = Date()
        }
        .onReceive(clockTimer) { tick in
            currentDate = tick
        }
    }
}
