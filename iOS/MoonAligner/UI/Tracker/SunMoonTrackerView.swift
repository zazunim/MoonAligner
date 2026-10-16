import SwiftUI
import Combine

public struct SunMoonTrackerView: View {
    @State private var now: Date = Date()
    @State private var selectedHourFloat: Float = EphemerisEngine.currentHourFloat()
    @State private var isLiveSync: Bool = true

    private let lat = 37.5665
    private let lon = 126.9780
    private let liveTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    private var seoulCalendar: Calendar {
        EphemerisEngine.seoulCalendar
    }

    private var selectedDate: Date {
        if isLiveSync {
            return now
        }
        let startOfDay = seoulCalendar.startOfDay(for: now)
        let totalSeconds = Int((Double(selectedHourFloat) * 3600.0).rounded())
        return seoulCalendar.date(byAdding: .second, value: totalSeconds, to: startOfDay) ?? now
    }

    private var todayString: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = seoulCalendar.timeZone
        formatter.dateFormat = "M월 d일 (E)"
        return formatter.string(from: now)
    }

    private var displayedTimeString: String {
        if isLiveSync {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "ko_KR")
            formatter.timeZone = seoulCalendar.timeZone
            formatter.dateFormat = "HH:mm:ss"
            return formatter.string(from: now)
        } else {
            let totalMinutes = min(1439, max(0, Int((Double(selectedHourFloat) * 60.0).rounded())))
            let hour = totalMinutes / 60
            let minVal = totalMinutes % 60
            return String(format: "%02d:%02d", hour, minVal)
        }
    }

    private var moon: MoonDetails {
        EphemerisEngine.calculateMoon(date: selectedDate, latitudeDeg: lat, longitudeDeg: lon)
    }

    private var sun: SunDetails {
        EphemerisEngine.calculateSun(date: selectedDate, latitudeDeg: lat, longitudeDeg: lon)
    }

    private var sunDayTimes: SunDayTimes {
        EphemerisEngine.calculateSunDayTimes(date: selectedDate, calendar: seoulCalendar, latitudeDeg: lat, longitudeDeg: lon)
    }

    private var hourlyPositions: [(hour: Int, sun: SunDetails, moon: MoonDetails)] {
        let startOfDay = seoulCalendar.startOfDay(for: now)
        return (0...24).map { h in
            let sampleDate = seoulCalendar.date(byAdding: .hour, value: h % 24, to: startOfDay) ?? now
            let s = EphemerisEngine.calculateSun(date: sampleDate, latitudeDeg: lat, longitudeDeg: lon)
            let m = EphemerisEngine.calculateMoon(date: sampleDate, latitudeDeg: lat, longitudeDeg: lon)
            return (h, s, m)
        }
    }

    public init() {}

    private func syncToCurrentTime() {
        let current = Date()
        now = current
        selectedHourFloat = EphemerisEngine.currentHourFloat(in: seoulCalendar, for: current)
        isLiveSync = true
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                // 상단 헤더
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("태양 & 달 궤적 트래커")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundColor(.textPrimary)
                        HStack(spacing: 6) {
                            Text("서울 (\(todayString))")
                                .font(.system(size: 12))
                                .foregroundColor(.textSecondary)
                            Text(isLiveSync ? "🟢 실시간 동기화 중" : "⏸ 수동 시뮬레이션")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(isLiveSync ? Color(hex: 0x4ADE80) : .accentOrange)
                        }
                    }
                    Spacer()
                    Button(action: {
                        syncToCurrentTime()
                    }) {
                        Text(displayedTimeString)
                            .font(.system(size: 16, weight: .bold, design: .monospaced))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.cardBackground)
                            .foregroundColor(.sunGold)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(isLiveSync ? Color(hex: 0x4ADE80).opacity(0.6) : Color.sunGold.opacity(0.4), lineWidth: 1)
                            )
                    }
                }

                // 1. 360도 스카이 레이더 나침반 (Canvas 렌더링)
                VStack(alignment: .leading, spacing: 6) {
                    Text("하늘 360° 레이더 뷰 (중앙: 천정 90°, 테두리: 지평선 0°)")
                        .font(.system(size: 12))
                        .foregroundColor(.textSecondary)

                    ZStack {
                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color(hex: 0x090D15))
                            .frame(height: 280)
                            .overlay(
                                RoundedRectangle(cornerRadius: 16)
                                    .stroke(Color.gridLine, lineWidth: 1)
                            )

                        Canvas { context, size in
                            let center = CGPoint(x: size.width / 2.0, y: size.height / 2.0)
                            let maxRadius = min(size.width, size.height) * 0.42

                            // 동심원 (고도 0도 지평선, 30도, 60도)
                            for rRatio in [0.33, 0.66, 1.0] {
                                let r = maxRadius * CGFloat(rRatio)
                                context.stroke(
                                    Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
                                    with: .color(Color.gridLine),
                                    lineWidth: rRatio == 1.0 ? 2 : 1
                                )
                            }

                            // 십자선 (남북, 동서)
                            context.stroke(Path { p in
                                p.move(to: CGPoint(x: center.x, y: center.y - maxRadius))
                                p.addLine(to: CGPoint(x: center.x, y: center.y + maxRadius))
                                p.move(to: CGPoint(x: center.x - maxRadius, y: center.y))
                                p.addLine(to: CGPoint(x: center.x + maxRadius, y: center.y))
                            }, with: .color(Color.gridLine), lineWidth: 1)

                            // 방위 문자
                            let cardinals = [("N (북)", 0.0), ("E (동)", 90.0), ("S (남)", 180.0), ("W (서)", 270.0)]
                            for (text, deg) in cardinals {
                                let rad = (deg - 90.0) * (Double.pi / 180.0)
                                let tx = center.x + (maxRadius + 16.0) * cos(rad)
                                let ty = center.y + (maxRadius + 16.0) * sin(rad)
                                context.draw(Text(text).font(.system(size: 10, weight: .bold)).foregroundColor(.textSecondary), at: CGPoint(x: tx, y: ty))
                            }

                            // 태양 24시간 궤적선 (주황색 곡선)
                            var sunPath = Path()
                            var firstSun = true
                            for (_, s, _) in hourlyPositions {
                                if s.altitudeDeg >= -15.0 {
                                    let r = maxRadius * CGFloat((90.0 - max(0.0, s.altitudeDeg)) / 90.0)
                                    let azRad = (s.azimuthDeg - 90.0) * (Double.pi / 180.0)
                                    let pt = CGPoint(x: center.x + r * cos(azRad), y: center.y + r * sin(azRad))
                                    if firstSun {
                                        sunPath.move(to: pt)
                                        firstSun = false
                                    } else {
                                        sunPath.addLine(to: pt)
                                    }
                                }
                            }
                            context.stroke(sunPath, with: .color(Color.sunGold.opacity(0.4)), lineWidth: 3)

                            // 달 24시간 궤적선 (연노랑 곡선)
                            var moonPath = Path()
                            var firstMoon = true
                            for (_, _, m) in hourlyPositions {
                                if m.altitudeDeg >= -15.0 {
                                    let r = maxRadius * CGFloat((90.0 - max(0.0, m.altitudeDeg)) / 90.0)
                                    let azRad = (m.azimuthDeg - 90.0) * (Double.pi / 180.0)
                                    let pt = CGPoint(x: center.x + r * cos(azRad), y: center.y + r * sin(azRad))
                                    if firstMoon {
                                        moonPath.move(to: pt)
                                        firstMoon = false
                                    } else {
                                        moonPath.addLine(to: pt)
                                    }
                                }
                            }
                            context.stroke(moonPath, with: .color(Color.moonSurface.opacity(0.35)), lineWidth: 2)

                            // 현재 태양 위치 표시 (☀️)
                            if sun.altitudeDeg > -10.0 {
                                let sunAltClamped = max(0.0, sun.altitudeDeg)
                                let sunDist = maxRadius * CGFloat(1.0 - (sunAltClamped / 90.0))
                                let sunRad = (sun.azimuthDeg - 90.0) * (Double.pi / 180.0)
                                let sx = center.x + sunDist * cos(sunRad)
                                let sy = center.y + sunDist * sin(sunRad)

                                context.fill(Path(ellipseIn: CGRect(x: sx - 16, y: sy - 16, width: 32, height: 32)), with: .color(Color.sunGold.opacity(0.35)))
                                context.fill(Path(ellipseIn: CGRect(x: sx - 9, y: sy - 9, width: 18, height: 18)), with: .color(.sunGold))
                            }

                            // 현재 달 위치 표시 (🌕)
                            if moon.altitudeDeg > -10.0 {
                                let moonAltClamped = max(0.0, moon.altitudeDeg)
                                let moonDist = maxRadius * CGFloat(1.0 - (moonAltClamped / 90.0))
                                let moonRad = (moon.azimuthDeg - 90.0) * (Double.pi / 180.0)
                                let mx = center.x + moonDist * cos(moonRad)
                                let my = center.y + moonDist * sin(moonRad)

                                context.fill(Path(ellipseIn: CGRect(x: mx - 16, y: my - 16, width: 32, height: 32)), with: .color(Color.moonSurface.opacity(0.35)))
                                context.fill(Path(ellipseIn: CGRect(x: mx - 8, y: my - 8, width: 16, height: 16)), with: .color(.moonGlow))
                            }
                        }
                        .frame(height: 280)
                    }
                }

                // 2. 24시간 시간 슬라이더 & 현재 시각 동기화 버튼
                VStack(spacing: 6) {
                    HStack {
                        Text("시간 이동 (00:00 ~ 24:00)")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.textSecondary)
                        Spacer()
                        Button(action: {
                            syncToCurrentTime()
                        }) {
                            HStack(spacing: 4) {
                                Image(systemName: "clock.arrow.circlepath")
                                    .font(.system(size: 11, weight: .bold))
                                Text("현재 시각으로 복귀")
                                    .font(.system(size: 12, weight: .bold))
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(isLiveSync ? Color.surfaceDark : Color.sunGold)
                            .foregroundColor(isLiveSync ? .moonGlow : .nightSky)
                            .cornerRadius(8)
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
                .padding(12)
                .background(Color.cardBackground)
                .cornerRadius(12)

                // 3. 태양 위치 & 사진 골든아워 상세 카드
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("☀️ 태양 위치 & 사진 골든아워")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textPrimary)
                        Spacer()
                        let statusText: String = {
                            if sun.isGoldenHour { return "골든 아워 🌅" }
                            if sun.isBlueHour { return "블루 아워 🌌" }
                            return sun.altitudeDeg > 0 ? "낮 ☀️" : "밤 🌙"
                        }()
                        Text(statusText)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.sunGold)
                    }
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("방위각 (Azimuth)").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("\(String(format: "%.1f", sun.azimuthDeg))°").font(.system(size: 15, weight: .bold)).foregroundColor(.textPrimary)
                        }
                        Spacer()
                        VStack(alignment: .center, spacing: 2) {
                            Text("고도각 (Altitude)").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("\(String(format: "%.1f", sun.altitudeDeg))°")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(sun.altitudeDeg > 0 ? .sunGold : .textSecondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("촬영 조도").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text(sun.altitudeDeg > 0 ? "직사광" : "여명/야간")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(.textPrimary)
                        }
                    }
                }
                .padding(14)
                .background(Color.cardBackground)
                .cornerRadius(12)

                // 4. SunOnTrack 일조 시간 및 태양 운행 카드
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("☀️ 일조 시간 및 태양 운행 (SunOnTrack)")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textPrimary)
                        Spacer()
                        Text("총 \(sunDayTimes.daylightDurationMinutes / 60)시간 \(sunDayTimes.daylightDurationMinutes % 60)분")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.sunGold)
                    }
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("🌅 일출 시각").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text(sunDayTimes.sunriseTimeStr).font(.system(size: 15, weight: .bold)).foregroundColor(.textPrimary)
                            Text("\(String(format: "%.1f", sunDayTimes.sunriseAzimuthDeg))° (동)").font(.system(size: 10)).foregroundColor(.textSecondary)
                        }
                        Spacer()
                        VStack(alignment: .center, spacing: 2) {
                            Text("☀️ 남중 고도").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("최고 \(String(format: "%.1f", sunDayTimes.maxAltitudeDeg))°").font(.system(size: 15, weight: .bold)).foregroundColor(.sunGold)
                            Text("180.0° (정남)").font(.system(size: 10)).foregroundColor(.textSecondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("🌇 일몰 시각").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text(sunDayTimes.sunsetTimeStr).font(.system(size: 15, weight: .bold)).foregroundColor(.textPrimary)
                            Text("\(String(format: "%.1f", sunDayTimes.sunsetAzimuthDeg))° (서)").font(.system(size: 10)).foregroundColor(.textSecondary)
                        }
                    }
                }
                .padding(14)
                .background(Color.cardBackground)
                .cornerRadius(12)

                // 5. 달 상세 정보 카드
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("🌕 달 위치 & 위상 정보")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textPrimary)
                        Spacer()
                        Text("\(moon.phase.emoji) \(moon.phase.displayName) (밝기 \(Int((moon.illuminationFraction * 100).rounded()))%)")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.moonGlow)
                    }
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("방위각").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("\(String(format: "%.1f", moon.azimuthDeg))°").font(.system(size: 14, weight: .bold)).foregroundColor(.textPrimary)
                        }
                        Spacer()
                        VStack(alignment: .center, spacing: 2) {
                            Text("고도각").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("\(String(format: "%.1f", moon.altitudeDeg))°").font(.system(size: 14, weight: .bold)).foregroundColor(moon.altitudeDeg > 0 ? .moonGlow : .textSecondary)
                        }
                        Spacer()
                        VStack(alignment: .center, spacing: 2) {
                            Text("지구-달 거리").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("\(Int(moon.distanceKm.rounded())) km").font(.system(size: 13, weight: .bold)).foregroundColor(.textPrimary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("달 시직경").font(.system(size: 11)).foregroundColor(.textSecondary)
                            Text("\(String(format: "%.3f", moon.angularDiameterDeg))°").font(.system(size: 13, weight: .bold)).foregroundColor(.textPrimary)
                        }
                    }
                }
                .padding(14)
                .background(Color.cardBackground)
                .cornerRadius(12)
            }
            .padding(16)
        }
        .background(Color.nightSky.ignoresSafeArea())
        .onAppear {
            if isLiveSync {
                syncToCurrentTime()
            }
        }
        .onReceive(liveTimer) { tick in
            if isLiveSync {
                now = tick
                selectedHourFloat = EphemerisEngine.currentHourFloat(in: seoulCalendar, for: tick)
            }
        }
    }
}
