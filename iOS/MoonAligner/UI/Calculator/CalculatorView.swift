import SwiftUI
import CoreLocation

public struct CalculatorView: View {
    @ObservedObject var sharedState: PlannerSharedState

    private var requiredDistance: Double {
        sharedState.calculatorRequiredDistance
    }

    private var moonDiameterOnSensor: Double {
        OpticsCalculator.calculateMoonImageDiameterOnSensorMm(focalLengthMm: Double(sharedState.focalLength))
    }

    private var moonFrameFraction: Double {
        min(1.0, max(0.0, moonDiameterOnSensor / sharedState.selectedSensor.heightMm))
    }

    private var formattedDistance: String {
        if requiredDistance >= 1000.0 {
            return String(format: "%.2f km", requiredDistance / 1000.0)
        } else {
            return "\(Int(requiredDistance.rounded())) m"
        }
    }

    public init(sharedState: PlannerSharedState) {
        self.sharedState = sharedState
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // 상단 타이틀
                VStack(alignment: .leading, spacing: 3) {
                    Text("화각 압축 계산기")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.textPrimary)
                    Text("달-피사체 크기 비율 및 최적 촬영 거리")
                        .font(.system(size: 12))
                        .foregroundColor(.textSecondary)
                }

                // 1. 피사체 선택
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(SubjectSilhouetteType.allCases) { type in
                            let isSelected = sharedState.selectedSubjectType == type
                            Button(action: {
                                sharedState.selectedSubjectType = type
                                sharedState.subjectHeight = type.defaultHeightM
                                sharedState.alignToCalculatorDistance()
                            }) {
                                Text(type.displayName)
                                    .font(.system(size: 11.5, weight: isSelected ? .bold : .medium))
                                    .padding(.horizontal, 11)
                                    .padding(.vertical, 6)
                                    .background(isSelected ? Color.sunGold : Color.cardBackground)
                                    .foregroundColor(isSelected ? Color.nightSky : Color.textPrimary)
                                    .cornerRadius(14)
                            }
                        }
                    }
                }

                // 2. 가상 뷰파인더 캔버스
                HStack {
                    Text("가상 뷰파인더")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.textSecondary)
                    Spacer()
                    Text("\(sharedState.selectedSensor.displayName) · \(Int(sharedState.focalLength.rounded()))mm")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundColor(.textSecondary)
                }

                ZStack {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(Color(hex: 0x06090F))
                        .aspectRatio(16.0 / 9.0, contentMode: .fit)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color(hex: 0x64748B, alpha: 0.25), lineWidth: 1)
                        )

                    Canvas { context, size in
                        let center = CGPoint(x: size.width / 2.0, y: size.height / 2.0)

                        // 3분할 황금분할선
                        let gridColor = Color.white.opacity(0.1)
                        context.stroke(Path { p in
                            p.move(to: CGPoint(x: size.width / 3.0, y: 0))
                            p.addLine(to: CGPoint(x: size.width / 3.0, y: size.height))
                            p.move(to: CGPoint(x: size.width * 2.0 / 3.0, y: 0))
                            p.addLine(to: CGPoint(x: size.width * 2.0 / 3.0, y: size.height))
                            p.move(to: CGPoint(x: 0, y: size.height / 3.0))
                            p.addLine(to: CGPoint(x: size.width, y: size.height / 3.0))
                            p.move(to: CGPoint(x: 0, y: size.height * 2.0 / 3.0))
                            p.addLine(to: CGPoint(x: size.width, y: size.height * 2.0 / 3.0))
                        }, with: .color(gridColor), lineWidth: 1)

                        let moonRadius = max(15.0, size.height * CGFloat(moonFrameFraction) / 2.0)

                        // (A) 리얼 보름달 아트
                        MoonArtRenderer.drawRealisticFullMoon(
                            context: &context,
                            center: center,
                            radius: moonRadius
                        )

                        // (B) 피사체 실루엣 아트
                        MoonArtRenderer.drawSubjectSilhouette(
                            context: &context,
                            type: sharedState.selectedSubjectType,
                            center: center,
                            moonRadius: moonRadius,
                            scaleRatio: CGFloat(sharedState.scaleRatio)
                        )

                        // 중심 크로스헤어
                        let chLen: CGFloat = 12.0
                        context.stroke(Path { p in
                            p.move(to: CGPoint(x: center.x - chLen, y: center.y))
                            p.addLine(to: CGPoint(x: center.x + chLen, y: center.y))
                            p.move(to: CGPoint(x: center.x, y: center.y - chLen))
                            p.addLine(to: CGPoint(x: center.x, y: center.y + chLen))
                        }, with: .color(.white.opacity(0.3)), lineWidth: 1)
                    }

                    // 뷰파인더 모서리 정보 오버레이
                    VStack {
                        HStack {
                            Text("[ \(sharedState.selectedSensor.displayName) | \(Int(sharedState.focalLength.rounded()))mm ]")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                            Spacer()
                            Text("달 화각: \(Int((moonFrameFraction * 100.0).rounded()))%")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(.sunGold)
                        }
                        Spacer()
                        HStack {
                            Text("ISO 100  1/250s  f/11")
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundColor(.white.opacity(0.6))
                            Spacer()
                            Text("\(sharedState.selectedSubjectType.displayName) (\(String(format: "%.1f", sharedState.subjectHeight))m)")
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                        }
                    }
                    .padding(10)
                }

                // 3. 거리 결과 요약 & 정렬 플래너 맵 자동 연동 카드
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .center) {
                        Text("필요 촬영 거리")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.textSecondary)
                        Spacer()
                        HStack(spacing: 4) {
                            Circle().fill(Color(hex: 0x4ADE80)).frame(width: 6, height: 6)
                            Text("지도 연동")
                                .font(.system(size: 10.5, weight: .semibold))
                                .foregroundColor(Color(hex: 0x4ADE80))
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(hex: 0x4ADE80).opacity(0.12))
                        .cornerRadius(10)
                    }

                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(requiredDistance >= 1000.0 ? String(format: "%.2f", requiredDistance / 1000.0) : "\(Int(requiredDistance.rounded()))")
                            .font(.system(size: 32, weight: .black, design: .rounded))
                            .foregroundColor(.moonGlow)
                        Text(requiredDistance >= 1000.0 ? "km" : "m")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.textSecondary)
                    }

                    // 정렬 기준 세그먼트 (피사체 고정 vs 카메라 고정)
                    HStack(spacing: 6) {
                        Button(action: {
                            sharedState.anchorMode = .targetFixed
                            sharedState.pinMoveMode = .camera
                            sharedState.alignCameraToCalculatorDistance()
                        }) {
                            HStack(spacing: 4) {
                                Image(systemName: "mappin.circle.fill")
                                Text("피사체 고정")
                            }
                            .font(.system(size: 11.5, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(sharedState.anchorMode == .targetFixed ? Color.accentBlue : Color.surfaceDark)
                            .foregroundColor(.white)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(sharedState.anchorMode == .targetFixed ? Color.sunGold : Color.clear, lineWidth: 1.2)
                            )
                        }

                        Button(action: {
                            sharedState.anchorMode = .cameraFixed
                            sharedState.pinMoveMode = .target
                            sharedState.alignTargetToCalculatorDistance()
                        }) {
                            HStack(spacing: 4) {
                                Image(systemName: "camera.circle.fill")
                                Text("카메라 고정")
                            }
                            .font(.system(size: 11.5, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(sharedState.anchorMode == .cameraFixed ? Color(hex: 0xE11D48) : Color.surfaceDark)
                            .foregroundColor(.white)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(sharedState.anchorMode == .cameraFixed ? Color.sunGold : Color.clear, lineWidth: 1.2)
                            )
                        }
                    }

                    Button(action: {
                        sharedState.alignToCalculatorDistance()
                        let focusPt = sharedState.anchorMode == .targetFixed ? sharedState.cameraPoint : sharedState.targetPoint
                        sharedState.centerTrigger = CLLocationCoordinate2D(
                            latitude: focusPt.latitudeDeg,
                            longitude: focusPt.longitudeDeg
                        )
                        sharedState.selectedTab = 0
                    }) {
                        HStack(spacing: 6) {
                            Image(systemName: "map.fill")
                            Text("지도에서 위치 확인 ➔")
                                .font(.system(size: 12.5, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(Color.sunGold)
                        .foregroundColor(.nightSky)
                        .cornerRadius(8)
                    }
                }
                .padding(14)
                .background(Color.cardBackground)
                .cornerRadius(14)

                // 4. 슬라이더 컨트롤러
                VStack(spacing: 14) {
                    // (A) 피사체 실제 높이
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text("피사체 높이")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.textSecondary)
                            Spacer()
                            Text("\(String(format: "%.1f", sharedState.subjectHeight)) m")
                                .font(.system(size: 13, weight: .bold, design: .rounded))
                                .foregroundColor(.textPrimary)
                        }
                        Slider(
                            value: Binding(
                                get: { sharedState.subjectHeight },
                                set: { newVal in
                                    sharedState.subjectHeight = newVal
                                    sharedState.alignToCalculatorDistance()
                                }
                            ),
                            in: 1.0...600.0
                        )
                        .tint(.sunGold)
                    }

                    // (B) 달 대비 크기 비율
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text("달 대비 크기")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.textSecondary)
                            Spacer()
                            let matchText = sharedState.scaleRatio < 0.85 ? "달이 큼" : (sharedState.scaleRatio > 1.15 ? "피사체가 큼" : "1:1 일치")
                            Text("\(String(format: "%.2f", sharedState.scaleRatio))x (\(matchText))")
                                .font(.system(size: 13, weight: .bold, design: .rounded))
                                .foregroundColor(.sunGold)
                        }
                        Slider(
                            value: Binding(
                                get: { sharedState.scaleRatio },
                                set: { newVal in
                                    sharedState.scaleRatio = newVal
                                    sharedState.alignToCalculatorDistance()
                                }
                            ),
                            in: 0.2...2.5
                        )
                        .tint(.sunGold)
                    }

                    // (C) 초점거리
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text("렌즈 초점거리")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.textSecondary)
                            Spacer()
                            Text("\(Int(sharedState.focalLength.rounded())) mm")
                                .font(.system(size: 13, weight: .bold, design: .rounded))
                                .foregroundColor(.textPrimary)
                        }
                        Slider(value: $sharedState.focalLength, in: 70.0...1200.0)
                            .tint(.moonGlow)

                        HStack(spacing: 6) {
                            ForEach([200, 400, 600, 800, 1200], id: \.self) { mm in
                                let isSelected = Int(sharedState.focalLength.rounded()) == mm
                                Button(action: { sharedState.focalLength = Float(mm) }) {
                                    Text("\(mm)")
                                        .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 4)
                                        .background(isSelected ? Color.sunGold : Color.cardBackground)
                                        .foregroundColor(isSelected ? Color.nightSky : Color.textPrimary)
                                        .cornerRadius(6)
                                }
                            }
                        }
                    }
                }

                // 5. 광학 공식 컴팩트 칩
                HStack {
                    Text("D = H × 110.5 (달 각지름 0.52° 기준)")
                        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                        .foregroundColor(.textSecondary.opacity(0.8))
                    Spacer()
                }
                .padding(.horizontal, 4)
            }
            .padding(16)
        }
        .background(Color.nightSky.ignoresSafeArea())
    }
}
