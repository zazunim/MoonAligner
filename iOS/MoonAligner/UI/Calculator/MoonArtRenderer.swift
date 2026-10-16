import SwiftUI

public enum SubjectSilhouetteType: String, CaseIterable, Identifiable {
    case person = "인물 (사진작가)"
    case pagoda = "전통 누각 (정자)"
    case lotteTop = "롯데타워 (상단 첨탑)"
    case lotteFull = "롯데타워 (전체 555m)"
    case nSeoulTower = "N서울타워 (상단부)"
    case airplane = "보름달 횡단 여객기"
    case bird = "비상하는 독수리"
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
        case .airplane: return "달 정중앙을 관통하는 대형 제트 여객기"
        case .bird: return "날개를 활짝 편 맹금류의 실루엣 (~200m)"
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

        // 인물 머리
        let headRect = CGRect(x: cx - w * 0.22, y: topY + h * 0.05, width: h * 0.15, height: h * 0.15)
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
        let h = size * 0.85

        // 1. 주 동체, 수직 꼬리날개, 수평 꼬리날개, 하단 주날개
        var planePath = Path()
        // 기수 (앞코)
        planePath.move(to: CGPoint(x: cx + w * 0.5, y: centerY))
        // 동체 상단
        planePath.addQuadCurve(
            to: CGPoint(x: cx - w * 0.25, y: centerY - h * 0.08),
            control: CGPoint(x: cx + w * 0.2, y: centerY - h * 0.09)
        )
        // 수직 꼬리날개 (정밀 테이퍼)
        planePath.addLine(to: CGPoint(x: cx - w * 0.45, y: centerY - h * 0.42))
        planePath.addLine(to: CGPoint(x: cx - w * 0.40, y: centerY - h * 0.42))
        planePath.addLine(to: CGPoint(x: cx - w * 0.32, y: centerY - h * 0.06))
        // 후미
        planePath.addLine(to: CGPoint(x: cx - w * 0.50, y: centerY))
        // 수평 꼬리날개 아래쪽
        planePath.addLine(to: CGPoint(x: cx - w * 0.35, y: centerY + h * 0.12))
        planePath.addLine(to: CGPoint(x: cx - w * 0.28, y: centerY + h * 0.06))
        // 후방 동체 하단
        planePath.addLine(to: CGPoint(x: cx - w * 0.05, y: centerY + h * 0.08))

        // 하단 주날개 (좌/하단 뷰포인트 후퇴익)
        planePath.addLine(to: CGPoint(x: cx - w * 0.25, y: centerY + h * 0.45))
        planePath.addLine(to: CGPoint(x: cx - w * 0.12, y: centerY + h * 0.45))
        planePath.addLine(to: CGPoint(x: cx + w * 0.15, y: centerY + h * 0.08))

        // 기수 하단 곡선
        planePath.addQuadCurve(
            to: CGPoint(x: cx + w * 0.5, y: centerY),
            control: CGPoint(x: cx + w * 0.38, y: centerY + h * 0.06)
        )
        planePath.closeSubpath()
        context.fill(planePath, with: .color(silhouetteColor))

        // 2. 반대편 상단 주날개 (상단으로 뻗어나가는 후퇴익)
        var upperWing = Path()
        upperWing.move(to: CGPoint(x: cx - w * 0.05, y: centerY - h * 0.08))
        upperWing.addLine(to: CGPoint(x: cx - w * 0.20, y: centerY - h * 0.38))
        upperWing.addLine(to: CGPoint(x: cx - w * 0.12, y: centerY - h * 0.38))
        upperWing.addLine(to: CGPoint(x: cx + w * 0.15, y: centerY - h * 0.07))
        upperWing.closeSubpath()
        context.fill(upperWing, with: .color(silhouetteColor))

        // 3. 제트 엔진 2기 (날개 하단 터보팬 엔진 실루엣)
        let engine1Rect = CGRect(x: cx - w * 0.08, y: centerY + h * 0.22, width: w * 0.16, height: h * 0.07)
        let engine2Rect = CGRect(x: cx - w * 0.05, y: centerY - h * 0.22, width: w * 0.14, height: h * 0.06)
        context.fill(Path(roundedRect: engine1Rect, cornerRadius: 4), with: .color(silhouetteColor))
        context.fill(Path(roundedRect: engine2Rect, cornerRadius: 4), with: .color(silhouetteColor))
    }

    private static func drawEagle(context: inout GraphicsContext, cx: CGFloat, centerY: CGFloat, size: CGFloat) {
        let w = size * 1.35
        let h = size * 0.75

        var path = Path()
        // 머리 & 날카로운 갈고리 부리
        path.move(to: CGPoint(x: cx, y: centerY - h * 0.32))
        path.addLine(to: CGPoint(x: cx + w * 0.05, y: centerY - h * 0.27)) // 부리 끝
        path.addLine(to: CGPoint(x: cx + w * 0.02, y: centerY - h * 0.22)) // 턱/목
        path.addLine(to: CGPoint(x: cx + w * 0.06, y: centerY - h * 0.16)) // 우측 가슴

        // 우측 날개 앞전 (위로 솟구치며 뻗어나가는 우아한 곡선)
        path.addQuadCurve(
            to: CGPoint(x: cx + w * 0.48, y: centerY - h * 0.24),
            control: CGPoint(x: cx + w * 0.25, y: centerY - h * 0.28)
        )

        // 우측 날개 끝 칼깃 (Primary feathers - 4단 날개깃 톱니)
        path.addLine(to: CGPoint(x: cx + w * 0.50, y: centerY - h * 0.18))
        path.addLine(to: CGPoint(x: cx + w * 0.44, y: centerY - h * 0.15))
        path.addLine(to: CGPoint(x: cx + w * 0.47, y: centerY - h * 0.09))
        path.addLine(to: CGPoint(x: cx + w * 0.39, y: centerY - h * 0.06))
        path.addLine(to: CGPoint(x: cx + w * 0.41, y: centerY - h * 0.01))
        path.addLine(to: CGPoint(x: cx + w * 0.33, y: centerY + h * 0.01))
        path.addLine(to: CGPoint(x: cx + w * 0.34, y: centerY + h * 0.06))
        path.addLine(to: CGPoint(x: cx + w * 0.25, y: centerY + h * 0.04))

        // 우측 날개 후전 곡선 및 몸통 연결
        path.addQuadCurve(
            to: CGPoint(x: cx + w * 0.05, y: centerY + h * 0.22),
            control: CGPoint(x: cx + w * 0.12, y: centerY + h * 0.12)
        )

        // 부채꼴 꼬리깃 (활짝 펼친 형태)
        path.addLine(to: CGPoint(x: cx + w * 0.13, y: centerY + h * 0.38))
        path.addQuadCurve(
            to: CGPoint(x: cx - w * 0.13, y: centerY + h * 0.38),
            control: CGPoint(x: cx, y: centerY + h * 0.42)
        )
        path.addLine(to: CGPoint(x: cx - w * 0.05, y: centerY + h * 0.22))

        // 좌측 날개 후전 곡선
        path.addQuadCurve(
            to: CGPoint(x: cx - w * 0.25, y: centerY + h * 0.04),
            control: CGPoint(x: cx - w * 0.12, y: centerY + h * 0.12)
        )

        // 좌측 날개 끝 칼깃 (Primary feathers - 4단 대칭 날개깃)
        path.addLine(to: CGPoint(x: cx - w * 0.34, y: centerY + h * 0.06))
        path.addLine(to: CGPoint(x: cx - w * 0.33, y: centerY + h * 0.01))
        path.addLine(to: CGPoint(x: cx - w * 0.41, y: centerY - h * 0.01))
        path.addLine(to: CGPoint(x: cx - w * 0.39, y: centerY - h * 0.06))
        path.addLine(to: CGPoint(x: cx - w * 0.47, y: centerY - h * 0.09))
        path.addLine(to: CGPoint(x: cx - w * 0.44, y: centerY - h * 0.15))
        path.addLine(to: CGPoint(x: cx - w * 0.50, y: centerY - h * 0.18))
        path.addLine(to: CGPoint(x: cx - w * 0.48, y: centerY - h * 0.24))

        // 좌측 날개 앞전 곡선 (머리/목 쪽으로 복귀)
        path.addQuadCurve(
            to: CGPoint(x: cx - w * 0.06, y: centerY - h * 0.16),
            control: CGPoint(x: cx - w * 0.25, y: centerY - h * 0.28)
        )
        path.addLine(to: CGPoint(x: cx - w * 0.02, y: centerY - h * 0.22))
        path.closeSubpath()

        context.fill(path, with: .color(silhouetteColor))
    }

    private static func drawApartment(context: inout GraphicsContext, cx: CGFloat, bottomY: CGFloat, h: CGFloat) {
        let w = h * 0.65
        let topY = bottomY - h

        var path = Path()
        path.move(to: CGPoint(x: cx - w * 0.45, y: bottomY))
        path.addLine(to: CGPoint(x: cx - w * 0.45, y: topY + h * 0.12))
        path.addLine(to: CGPoint(x: cx - w * 0.32, y: topY + h * 0.12))
        // 옥상 계단실 및 헬리패드
        path.addLine(to: CGPoint(x: cx - w * 0.32, y: topY + h * 0.04))
        path.addLine(to: CGPoint(x: cx + w * 0.15, y: topY + h * 0.04))
        // 옥상 통신 안테나
        path.addLine(to: CGPoint(x: cx + w * 0.15, y: topY))
        path.addLine(to: CGPoint(x: cx + w * 0.17, y: topY))
        path.addLine(to: CGPoint(x: cx + w * 0.17, y: topY + h * 0.04))
        path.addLine(to: CGPoint(x: cx + w * 0.32, y: topY + h * 0.04))
        path.addLine(to: CGPoint(x: cx + w * 0.32, y: topY + h * 0.12))
        path.addLine(to: CGPoint(x: cx + w * 0.45, y: topY + h * 0.12))
        path.addLine(to: CGPoint(x: cx + w * 0.45, y: bottomY))
        path.closeSubpath()

        context.fill(path, with: .color(silhouetteColor))

        // 불 켜진 창문 감성 포인트
        let winW = w * 0.06
        let winH = h * 0.04
        let winColor = Color(hex: 0xFDE047).opacity(0.85)
        context.fill(Path(CGRect(x: cx - w * 0.3, y: topY + h * 0.25, width: winW, height: winH)), with: .color(winColor))
        context.fill(Path(CGRect(x: cx - w * 0.1, y: topY + h * 0.35, width: winW, height: winH)), with: .color(winColor))
        context.fill(Path(CGRect(x: cx + w * 0.18, y: topY + h * 0.28, width: winW, height: winH)), with: .color(winColor))
        context.fill(Path(CGRect(x: cx - w * 0.25, y: topY + h * 0.52, width: winW, height: winH)), with: .color(winColor))
        context.fill(Path(CGRect(x: cx + w * 0.22, y: topY + h * 0.62, width: winW, height: winH)), with: .color(winColor))
    }
}
