import SwiftUI

public enum CelestialTargetType: String, CaseIterable, Identifiable {
    case moon = "달 (Moon)"
    case sun = "태양 (Sun)"

    public var id: String { rawValue }
    public var shortName: String {
        switch self {
        case .moon: return "달"
        case .sun: return "태양"
        }
    }
}

public enum SubjectSilhouetteType: String, CaseIterable, Identifiable {
    case person = "인물 (사진작가)"
    case pagoda = "전통 누각 (정자)"
    case lotteTop = "롯데타워 (상단 첨탑)"
    case lotteFull = "롯데타워 (전체 555m)"
    case nSeoulTower = "N서울타워 (상단부)"
    case airplane = "여객기 (태양/달 횡단)"
    case bird = "비상하는 독수리"
    case lighthouse = "해안 등대 (일출/일몰)"
    case mountain = "산 정상 (봉우리 일출)"
    case apartment = "도심 아파트 옥상"

    public var id: String { rawValue }
    public var displayName: String { rawValue }

    public var defaultHeightM: Float {
        switch self {
        case .person: return 1.8
        case .pagoda: return 6.5
        case .lotteTop: return 45.0
        case .lotteFull: return 555.0
        case .nSeoulTower: return 35.0
        case .airplane: return 65.0
        case .bird: return 1.8
        case .lighthouse: return 24.0
        case .mountain: return 80.0
        case .apartment: return 40.0
        }
    }

    public var isFlying: Bool {
        return self == .airplane || self == .bird
    }

    public var description: String {
        switch self {
        case .person: return "삼각대와 망원 카메라를 조작하는 인물 (~200m)"
        case .pagoda: return "기와 처마 곡선의 한국 전통 누각 (~700m)"
        case .lotteTop: return "올림픽공원/뚝섬/남한산성 출사 구도 (~4.9km)"
        case .lotteFull: return "건물 전체를 달 크기로 담는 원경 샷 (~61km)"
        case .nSeoulTower: return "노들섬/용산/반포 출사 구도 (~3.8km)"
        case .airplane: return "태양 또는 달 정중앙을 관통하는 대형 제트 여객기"
        case .bird: return "날개를 활짝 편 맹금류의 실루엣 (~200m)"
        case .lighthouse: return "수평선 오메가 일출·일몰과 등대 실루엣 (~2.6km)"
        case .mountain: return "산 능선 너머로 솟아오르는 장엄한 일출 샷 (~8.8km)"
        case .apartment: return "옥상 안테나와 빌딩 스카이라인 (~4.4km)"
        }
    }
}

public enum MoonArtRenderer {

    private static let silhouetteColor = Color(hex: 0x090D14)

    /// SwiftUI Canvas 상에서 고해상도 리얼 보름달 렌더링
    public static func drawRealisticFullMoon(
        context: inout GraphicsContext,
        center: CGPoint,
        radius: CGFloat
    ) {
        guard radius > 2 else { return }

        // 1. 달 외곽 대기광 (Halo & Glow)
        let glowRect = CGRect(x: center.x - radius * 1.35, y: center.y - radius * 1.35, width: radius * 2.7, height: radius * 2.7)
        context.fill(
            Path(ellipseIn: glowRect),
            with: .radialGradient(
                Gradient(colors: [Color(hex: 0xFFF8E1, alpha: 0.35), Color(hex: 0xFFE082, alpha: 0.15), .clear]),
                center: center,
                startRadius: radius * 0.8,
                endRadius: radius * 1.35
            )
        )

        // 2. 보름달 구체 기본 바탕색
        let moonRect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
        context.fill(
            Path(ellipseIn: moonRect),
            with: .radialGradient(
                Gradient(colors: [Color(hex: 0xFFFDE7), Color(hex: 0xFFF9C4), Color(hex: 0xF0E5B8), Color(hex: 0xE2D4A4)]),
                center: CGPoint(x: center.x - radius * 0.1, y: center.y - radius * 0.1),
                startRadius: 0,
                endRadius: radius
            )
        )

        // 3. 달의 바다 지형 (Lunar Maria)
        let mareColor1 = Color(hex: 0x6B614D, alpha: 0.22)
        let mareColor2 = Color(hex: 0x5A513E, alpha: 0.26)

        // 비의 바다 (Mare Imbrium)
        context.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.55, y: center.y - radius * 0.65, width: radius * 0.52, height: radius * 0.44)), with: .color(mareColor2))
        // 폭풍의 대양 (Oceanus Procellarum)
        context.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.85, y: center.y - radius * 0.35, width: radius * 0.55, height: radius * 0.65)), with: .color(mareColor1))
        // 맑음의 바다 & 고요의 바다
        context.fill(Path(ellipseIn: CGRect(x: center.x + radius * 0.05, y: center.y - radius * 0.48, width: radius * 0.38, height: radius * 0.36)), with: .color(mareColor2))
        context.fill(Path(ellipseIn: CGRect(x: center.x + radius * 0.18, y: center.y - radius * 0.18, width: radius * 0.45, height: radius * 0.40)), with: .color(mareColor2))
        // 위기의 바다 (Mare Crisium)
        context.fill(Path(ellipseIn: CGRect(x: center.x + radius * 0.62, y: center.y - radius * 0.28, width: radius * 0.22, height: radius * 0.26)), with: .color(Color(hex: 0x4D4433, alpha: 0.32)))

        // 4. 티코 크레이터 (Tycho Crater)
        let tychoCenter = CGPoint(x: center.x - radius * 0.18, y: center.y + radius * 0.58)
        context.fill(Path(ellipseIn: CGRect(x: tychoCenter.x - radius * 0.045, y: tychoCenter.y - radius * 0.045, width: radius * 0.09, height: radius * 0.09)), with: .color(.white.opacity(0.8)))

        // 5. 가장자리 음영
        context.stroke(Path(ellipseIn: moonRect), with: .color(Color(hex: 0x1A1508, alpha: 0.15)), lineWidth: radius * 0.04)
    }

    /// SwiftUI Canvas 상에서 이글거리는 일출/일몰 태양(Solar Disc & Corona) 렌더링
    public static func drawRealisticSun(
        context: inout GraphicsContext,
        center: CGPoint,
        radius: CGFloat
    ) {
        guard radius > 2 else { return }

        // 1. 태양 외곽 대기 산란광 (Atmospheric Sunset Haze)
        let outerHazeRect = CGRect(x: center.x - radius * 2.2, y: center.y - radius * 2.2, width: radius * 4.4, height: radius * 4.4)
        context.fill(
            Path(ellipseIn: outerHazeRect),
            with: .radialGradient(
                Gradient(colors: [Color(hex: 0xFF5722, alpha: 0.35), Color(hex: 0xFF9800, alpha: 0.18), Color(hex: 0xFFC107, alpha: 0.05), .clear]),
                center: center,
                startRadius: radius * 0.7,
                endRadius: radius * 2.2
            )
        )

        // 2. 태양 코로나 광륜 (Solar Corona & Rim Fire)
        let coronaRect = CGRect(x: center.x - radius * 1.35, y: center.y - radius * 1.35, width: radius * 2.7, height: radius * 2.7)
        context.fill(
            Path(ellipseIn: coronaRect),
            with: .radialGradient(
                Gradient(colors: [Color(hex: 0xFFEB3B, alpha: 0.75), Color(hex: 0xFF9800, alpha: 0.45), Color(hex: 0xF44336, alpha: 0.15), .clear]),
                center: center,
                startRadius: radius * 0.85,
                endRadius: radius * 1.35
            )
        )

        // 3. 태양 본체 구체 (Solar Disc - 눈부신 백열/황금빛 코어)
        let sunRect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
        context.fill(
            Path(ellipseIn: sunRect),
            with: .radialGradient(
                Gradient(colors: [
                    Color(hex: 0xFFFFFF),          // 백열의 초고온 중심부
                    Color(hex: 0xFFFDE7),          // 눈부신 백황색
                    Color(hex: 0xFFEE58),          // 밝은 태양 황금색
                    Color(hex: 0xFFA726),          // 일출/일몰 앰버 오렌지
                    Color(hex: 0xF4511E)           // 가장자리 림 홍염
                ]),
                center: center,
                startRadius: 0,
                endRadius: radius
            )
        )

        // 4. 태양 홍염 / 림 강조 (Rim Glow)
        context.stroke(
            Path(ellipseIn: sunRect),
            with: .color(Color(hex: 0xFF7043, alpha: 0.55)),
            lineWidth: max(1.5, radius * 0.05)
        )
    }

    /// 피사체 실루엣 그림자 렌더링
    public static func drawSubjectSilhouette(
        context: inout GraphicsContext,
        type: SubjectSilhouetteType,
        center: CGPoint,
        moonRadius: CGFloat,
        scaleRatio: CGFloat
    ) {
        let totalHeight = max(10.0, moonRadius * 2.0 * scaleRatio)
        let baseAnchorY = type.isFlying ? center.y + (totalHeight * 0.15) : center.y + moonRadius + (moonRadius * 0.05)

        switch type {
        case .person:
            drawPerson(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .pagoda:
            drawPagoda(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .lotteTop:
            drawLotteTopSpire(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .lotteFull:
            drawLotteFull(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .nSeoulTower:
            drawNSeoulTower(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .airplane:
            drawAirplane(context: &context, cx: center.x, centerY: baseAnchorY, size: totalHeight)
        case .bird:
            drawEagle(context: &context, cx: center.x, centerY: baseAnchorY, size: totalHeight)
        case .lighthouse:
            drawLighthouse(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .mountain:
            drawMountainPeak(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        case .apartment:
            drawApartment(context: &context, cx: center.x, bottomY: baseAnchorY, h: totalHeight)
        }
    }

    private static func drawPerson(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let w = h * 0.55
        let topY = bottomY - h

        // 삼각대
        var tripod = Path()
        tripod.move(to: CGPoint(x: cx + w * 0.15, y: topY + h * 0.42))
        tripod.addLine(to: CGPoint(x: cx - w * 0.2, y: bottomY))
        tripod.move(to: CGPoint(x: cx + w * 0.15, y: topY + h * 0.42))
        tripod.addLine(to: CGPoint(x: cx + w * 0.45, y: bottomY))
        context.stroke(tripod, with: .color(silhouetteColor), lineWidth: h * 0.035)

        // 카메라 바디 & 렌즈
        let camRect = CGRect(x: cx + w * 0.08, y: topY + h * 0.38, width: w * 0.22, height: h * 0.09)
        context.fill(Path(camRect), with: .color(silhouetteColor))
        let lensRect = CGRect(x: cx + w * 0.28, y: topY + h * 0.39, width: w * 0.24, height: h * 0.07)
        context.fill(Path(lensRect), with: .color(silhouetteColor))

        // 인물 머리
        let headRect = CGRect(x: cx - w * 0.22, y: topY + h * 0.1, width: w * 0.18, height: h * 0.13)
        context.fill(Path(ellipseIn: headRect), with: .color(silhouetteColor))

        // 인물 몸통
        var body = Path()
        body.move(to: CGPoint(x: cx - w * 0.23, y: topY + h * 0.2))
        body.addLine(to: CGPoint(x: cx - w * 0.07, y: topY + h * 0.2))
        body.addLine(to: CGPoint(x: cx + w * 0.12, y: topY + h * 0.4))
        body.addLine(to: CGPoint(x: cx - w * 0.08, y: topY + h * 0.6))
        body.addLine(to: CGPoint(x: cx - w * 0.24, y: topY + h * 0.6))
        body.closeSubpath()
        context.fill(body, with: .color(silhouetteColor))

        // 다리
        var legs = Path()
        legs.move(to: CGPoint(x: cx - w * 0.18, y: topY + h * 0.6))
        legs.addLine(to: CGPoint(x: cx - w * 0.22, y: bottomY))
        legs.move(to: CGPoint(x: cx - w * 0.11, y: topY + h * 0.6))
        legs.addLine(to: CGPoint(x: cx - w * 0.11, y: bottomY))
        context.stroke(legs, with: .color(silhouetteColor), lineWidth: h * 0.06)
    }

    private static func drawPagoda(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let w = h * 1.35
        let topY = bottomY - h

        var path = Path()
        path.move(to: CGPoint(x: cx, y: topY))
        path.addQuadCurve(to: CGPoint(x: cx - w * 0.48, y: topY + h * 0.16), control: CGPoint(x: cx - w * 0.25, y: topY + h * 0.18))
        path.addLine(to: CGPoint(x: cx - w * 0.38, y: topY + h * 0.25))
        path.addLine(to: CGPoint(x: cx - w * 0.2, y: topY + h * 0.42))
        path.addQuadCurve(to: CGPoint(x: cx - w * 0.56, y: topY + h * 0.44), control: CGPoint(x: cx - w * 0.35, y: topY + h * 0.46))
        path.addLine(to: CGPoint(x: cx - w * 0.32, y: bottomY))
        path.addLine(to: CGPoint(x: cx + w * 0.32, y: bottomY))
        path.addLine(to: CGPoint(x: cx + w * 0.56, y: topY + h * 0.44))
        path.addQuadCurve(to: CGPoint(x: cx + w * 0.2, y: topY + h * 0.42), control: CGPoint(x: cx + w * 0.35, y: topY + h * 0.46))
        path.addLine(to: CGPoint(x: cx + w * 0.38, y: topY + h * 0.25))
        path.addQuadCurve(to: CGPoint(x: cx, y: topY), control: CGPoint(x: cx + w * 0.25, y: topY + h * 0.18))
        path.closeSubpath()

        context.fill(path, with: .color(silhouetteColor))
    }

    private static func drawLotteTopSpire(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let w = h * 0.95
        let topY = bottomY - h

        var path = Path()
        path.move(to: CGPoint(x: cx - w * 0.28, y: topY))
        path.addLine(to: CGPoint(x: cx - w * 0.10, y: topY + h * 0.35))
        path.addLine(to: CGPoint(x: cx, y: topY + h * 0.20))
        path.addLine(to: CGPoint(x: cx + w * 0.10, y: topY + h * 0.35))
        path.addLine(to: CGPoint(x: cx + w * 0.28, y: topY))
        path.addLine(to: CGPoint(x: cx + w * 0.44, y: bottomY))
        path.addLine(to: CGPoint(x: cx - w * 0.44, y: bottomY))
        path.closeSubpath()

        context.fill(path, with: .color(silhouetteColor))

        // 창문 밴드
        var windows = Path()
        windows.move(to: CGPoint(x: cx - w * 0.32, y: topY + h * 0.52))
        windows.addLine(to: CGPoint(x: cx + w * 0.32, y: topY + h * 0.52))
        context.stroke(windows, with: .color(Color(hex: 0xFDE047, alpha: 0.8)), lineWidth: h * 0.045)
    }

    private static func drawLotteFull(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let w = h * 0.24
        let topY = bottomY - h

        var path = Path()
        path.move(to: CGPoint(x: cx - w * 0.04, y: topY))
        path.addLine(to: CGPoint(x: cx, y: topY + h * 0.04))
        path.addLine(to: CGPoint(x: cx + w * 0.04, y: topY))
        path.addQuadCurve(to: CGPoint(x: cx + w * 0.22, y: bottomY), control: CGPoint(x: cx + w * 0.15, y: topY + h * 0.55))
        path.addLine(to: CGPoint(x: cx - w * 0.22, y: bottomY))
        path.addQuadCurve(to: CGPoint(x: cx - w * 0.04, y: topY), control: CGPoint(x: cx - w * 0.15, y: topY + h * 0.55))
        path.closeSubpath()

        context.fill(path, with: .color(silhouetteColor))
    }

    private static func drawNSeoulTower(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let topY = bottomY - h
        let w = h * 0.28

        var path = Path()
        path.move(to: CGPoint(x: cx, y: topY))
        path.addLine(to: CGPoint(x: cx, y: topY + h * 0.32))
        path.addLine(to: CGPoint(x: cx - w * 0.24, y: topY + h * 0.36))
        path.addLine(to: CGPoint(x: cx - w * 0.08, y: topY + h * 0.44))
        path.addLine(to: CGPoint(x: cx - w * 0.25, y: bottomY))
        path.addLine(to: CGPoint(x: cx + w * 0.25, y: bottomY))
        path.addLine(to: CGPoint(x: cx + w * 0.08, y: topY + h * 0.44))
        path.addLine(to: CGPoint(x: cx + w * 0.24, y: topY + h * 0.36))
        path.closeSubpath()

        context.fill(path, with: .color(silhouetteColor))
    }

    private static func drawAirplane(context: inout GraphicsContext, cx: CGFloat, centerY: CGFloat, size: CGFloat) {
        let w = size * 1.15
        let h = size * 0.65

        // 동체
        let fuselage = CGRect(x: cx - w * 0.5, y: centerY - h * 0.12, width: w, height: h * 0.24)
        context.fill(Path(roundedRect: fuselage, cornerRadius: h * 0.12), with: .color(silhouetteColor))

        // 주날개
        var wings = Path()
        wings.move(to: CGPoint(x: cx - w * 0.08, y: centerY - h * 0.48))
        wings.addLine(to: CGPoint(x: cx + w * 0.12, y: centerY - h * 0.48))
        wings.addLine(to: CGPoint(x: cx - w * 0.02, y: centerY + h * 0.48))
        wings.addLine(to: CGPoint(x: cx - w * 0.18, y: centerY + h * 0.48))
        wings.closeSubpath()
        context.fill(wings, with: .color(silhouetteColor))

        // 꼬리날개
        var tail = Path()
        tail.move(to: CGPoint(x: cx - w * 0.42, y: centerY))
        tail.addLine(to: CGPoint(x: cx - w * 0.48, y: centerY - h * 0.35))
        tail.addLine(to: CGPoint(x: cx - w * 0.36, y: centerY - h * 0.35))
        tail.addLine(to: CGPoint(x: cx - w * 0.28, y: centerY))
        tail.closeSubpath()
        context.fill(tail, with: .color(silhouetteColor))
    }

    private static func drawEagle(context: inout GraphicsContext, cx: CGFloat, centerY: CGFloat, size: CGFloat) {
        let w = size * 1.3
        let h = size * 0.75

        var wings = Path()
        wings.move(to: CGPoint(x: cx, y: centerY + h * 0.15))
        wings.addQuadCurve(to: CGPoint(x: cx - w * 0.5, y: centerY - h * 0.35), control: CGPoint(x: cx - w * 0.25, y: centerY - h * 0.45))
        wings.addLine(to: CGPoint(x: cx - w * 0.42, y: centerY - h * 0.15))
        wings.addLine(to: CGPoint(x: cx - w * 0.28, y: centerY - h * 0.05))
        wings.addLine(to: CGPoint(x: cx, y: centerY + h * 0.15))
        wings.addQuadCurve(to: CGPoint(x: cx + w * 0.5, y: centerY - h * 0.35), control: CGPoint(x: cx + w * 0.25, y: centerY - h * 0.45))
        wings.addLine(to: CGPoint(x: cx + w * 0.42, y: centerY - h * 0.15))
        wings.addLine(to: CGPoint(x: cx + w * 0.28, y: centerY - h * 0.05))
        wings.closeSubpath()
        context.fill(wings, with: .color(silhouetteColor))

        let body = CGRect(x: cx - w * 0.06, y: centerY - h * 0.2, width: w * 0.12, height: h * 0.45)
        context.fill(Path(ellipseIn: body), with: .color(silhouetteColor))
    }

    private static func drawLighthouse(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let topY = bottomY - h
        let w = h * 0.45

        // 등대 기둥 타워 (위로 좁아지는 사다리꼴)
        var tower = Path()
        tower.move(to: CGPoint(x: cx - w * 0.22, y: topY + h * 0.25))
        tower.addLine(to: CGPoint(x: cx + w * 0.22, y: topY + h * 0.25))
        tower.addLine(to: CGPoint(x: cx + w * 0.36, y: bottomY))
        tower.addLine(to: CGPoint(x: cx - w * 0.36, y: bottomY))
        tower.closeSubpath()
        context.fill(tower, with: .color(silhouetteColor))

        // 등대 등명기실 (Lantern Room)
        let lanternRect = CGRect(x: cx - w * 0.16, y: topY + h * 0.08, width: w * 0.32, height: h * 0.17)
        context.fill(Path(roundedRect: lanternRect, cornerRadius: 2), with: .color(silhouetteColor))

        // 등대 돔형 지붕 (Dome Roof)
        var dome = Path()
        dome.addArc(center: CGPoint(x: cx, y: topY + h * 0.08), radius: w * 0.16, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        dome.closeSubpath()
        context.fill(dome, with: .color(silhouetteColor))

        // 피뢰침
        var finial = Path()
        finial.move(to: CGPoint(x: cx, y: topY + h * 0.08 - w * 0.16))
        finial.addLine(to: CGPoint(x: cx, y: topY))
        context.stroke(finial, with: .color(silhouetteColor), lineWidth: max(1.5, h * 0.03))

        // 난간 갤러리 플랫폼
        let railingRect = CGRect(x: cx - w * 0.28, y: topY + h * 0.23, width: w * 0.56, height: h * 0.035)
        context.fill(Path(railingRect), with: .color(silhouetteColor))

        // 등대 불빛 슬릿 (Light Beam Slit)
        let lightSlit = CGRect(x: cx - w * 0.09, y: topY + h * 0.12, width: w * 0.18, height: h * 0.08)
        context.fill(Path(lightSlit), with: .color(Color(hex: 0xFFF9C4, alpha: 0.9)))
    }

    private static func drawMountainPeak(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let topY = bottomY - h
        let w = h * 2.2

        var path = Path()
        path.move(to: CGPoint(x: cx - w * 0.5, y: bottomY))
        path.addLine(to: CGPoint(x: cx - w * 0.28, y: topY + h * 0.38))
        path.addLine(to: CGPoint(x: cx - w * 0.15, y: topY + h * 0.22))
        path.addLine(to: CGPoint(x: cx, y: topY)) // 주봉우리 정상
        path.addLine(to: CGPoint(x: cx + w * 0.12, y: topY + h * 0.18))
        path.addLine(to: CGPoint(x: cx + w * 0.32, y: topY + h * 0.45))
        path.addLine(to: CGPoint(x: cx + w * 0.5, y: bottomY))
        path.closeSubpath()
        context.fill(path, with: .color(silhouetteColor))

        // 정상 소나무/정상석 실루엣
        var monument = Path()
        monument.move(to: CGPoint(x: cx, y: topY))
        monument.addLine(to: CGPoint(x: cx, y: topY - h * 0.08))
        context.stroke(monument, with: .color(silhouetteColor), lineWidth: max(1.5, h * 0.025))
    }

    private static func drawApartment(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let w = h * 1.1
        let topY = bottomY - h

        let building = CGRect(x: cx - w * 0.4, y: topY + h * 0.2, width: w * 0.8, height: h * 0.8)
        context.fill(Path(building), with: .color(silhouetteColor))

        let tank = CGRect(x: cx - w * 0.25, y: topY + h * 0.05, width: w * 0.22, height: h * 0.15)
        context.fill(Path(tank), with: .color(silhouetteColor))

        var antenna = Path()
        antenna.move(to: CGPoint(x: cx + w * 0.15, y: topY + h * 0.2))
        antenna.addLine(to: CGPoint(x: cx + w * 0.15, y: topY))
        antenna.addLine(to: CGPoint(x: cx + w * 0.05, y: topY + h * 0.06))
        antenna.move(to: CGPoint(x: cx + w * 0.15, y: topY + h * 0.04))
        antenna.addLine(to: CGPoint(x: cx + w * 0.25, y: topY + h * 0.1))
        context.stroke(antenna, with: .color(silhouetteColor), lineWidth: h * 0.035)
    }
}
