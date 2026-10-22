import SwiftUI

public struct MoonAlignmentOpportunitySheet: View {
    let cameraPoint: GeoPoint
    let targetPoint: GeoPoint
    let targetHeightMeters: Double
    let subjectName: String
    let onSelectOpportunity: (MoonAlignmentOpportunity) -> Void
    let onSaveOpportunity: (MoonAlignmentOpportunity) -> Void
    @Environment(\.presentationMode) var presentationMode
    
    @State private var searchDays: Int = 60
    @State private var selectedFilter: OpportunityFilter = .all
    @State private var selectedSort: OpportunitySort = .byScore
    @State private var opportunities: [MoonAlignmentOpportunity] = []
    @State private var isCalculating: Bool = true
    
    public init(
        cameraPoint: GeoPoint,
        targetPoint: GeoPoint,
        targetHeightMeters: Double,
        subjectName: String,
        onSelectOpportunity: @escaping (MoonAlignmentOpportunity) -> Void,
        onSaveOpportunity: @escaping (MoonAlignmentOpportunity) -> Void
    ) {
        self.cameraPoint = cameraPoint
        self.targetPoint = targetPoint
        self.targetHeightMeters = targetHeightMeters
        self.subjectName = subjectName
        self.onSelectOpportunity = onSelectOpportunity
        self.onSaveOpportunity = onSaveOpportunity
    }
    
    private var distanceMeters: Double {
        AlignmentFinder.calculateDistanceMeters(p1: cameraPoint, p2: targetPoint)
    }
    
    private var filteredOpportunities: [MoonAlignmentOpportunity] {
        var list = opportunities
        
        switch selectedFilter {
        case .all:
            break
        case .goldenBlue:
            list = list.filter { $0.skyStage == .blueHour || $0.skyStage == .goldenHour }
        case .fullMoon:
            list = list.filter { $0.moonDetails.illuminationFraction >= 0.80 }
        }
        
        switch selectedSort {
        case .byScore:
            list.sort {
                if $0.score != $1.score {
                    return $0.score > $1.score
                }
                return $0.date < $1.date
            }
        case .byDate:
            list.sort { $0.date < $1.date }
        }
        
        return list
    }
    
    public var body: some View {
        NavigationView {
            ZStack {
                Color.nightSky.ignoresSafeArea()
                
                VStack(spacing: 0) {
                    // 1. 피사체 및 카메라 기준 요약 바
                    HStack(spacing: 12) {
                        Image(systemName: "scope")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.sunGold)
                        
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(subjectName) 달걸이 정렬")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.textPrimary)
                            
                            Text("거리 \(String(format: "%.2f", distanceMeters / 1000.0))km · 높이 \(Int(targetHeightMeters))m 기준")
                                .font(.system(size: 11))
                                .foregroundColor(.textSecondary)
                        }
                        
                        Spacer()
                        
                        // 탐색 기간 선택기
                        Menu {
                            Button("30일간 탐색") { changeDays(30) }
                            Button("60일간 탐색 (기본)") { changeDays(60) }
                            Button("90일간 탐색") { changeDays(90) }
                        } label: {
                            HStack(spacing: 4) {
                                Text("\(searchDays)일간")
                                    .font(.system(size: 12, weight: .semibold))
                                Image(systemName: "chevron.down")
                                    .font(.system(size: 9, weight: .bold))
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color.cardBackground)
                            .foregroundColor(.sunGold)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color.sunGold.opacity(0.3), lineWidth: 1)
                            )
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Color.surfaceDark)
                    
                    // 2. 필터 및 정렬 컨트롤
                    VStack(spacing: 8) {
                        // 위상/조명 필터 탭
                        HStack(spacing: 8) {
                            ForEach(OpportunityFilter.allCases) { filter in
                                Button(action: { selectedFilter = filter }) {
                                    Text(filter.rawValue)
                                        .font(.system(size: 12, weight: selectedFilter == filter ? .bold : .medium))
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(selectedFilter == filter ? Color.sunGold : Color.cardBackground)
                                        .foregroundColor(selectedFilter == filter ? Color.nightSky : Color.textSecondary)
                                        .cornerRadius(16)
                                }
                            }
                            Spacer()
                        }
                        
                        // 정렬 기준 선택
                        HStack(spacing: 6) {
                            Text("정렬:")
                                .font(.system(size: 11.5, weight: .medium))
                                .foregroundColor(.textSecondary)
                            
                            ForEach(OpportunitySort.allCases) { sort in
                                Button(action: { selectedSort = sort }) {
                                    Text(sort.rawValue)
                                        .font(.system(size: 11.5, weight: selectedSort == sort ? .bold : .medium))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(selectedSort == sort ? Color.surfaceDark : Color.clear)
                                        .foregroundColor(selectedSort == sort ? Color.moonGlow : Color.textSecondary)
                                        .cornerRadius(6)
                                }
                            }
                            
                            Spacer()
                            
                            if !isCalculating {
                                Text("총 \(filteredOpportunities.count)회 발견")
                                    .font(.system(size: 11.5, weight: .semibold))
                                    .foregroundColor(.textSecondary)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.surfaceDark.opacity(0.6))
                    
                    Divider().background(Color.gridLine)
                    
                    // 3. 리스트 영역
                    if isCalculating {
                        Spacer()
                        VStack(spacing: 12) {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .sunGold))
                                .scaleEffect(1.2)
                            Text("향후 \(searchDays)일간의 달 궤적과 3점 1열 정렬 데이터를 계산 중...")
                                .font(.system(size: 13))
                                .foregroundColor(.textSecondary)
                        }
                        Spacer()
                    } else if filteredOpportunities.isEmpty {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: "moon.stars")
                                .font(.system(size: 40))
                                .foregroundColor(.textSecondary.opacity(0.5))
                            Text("선택한 조건에 부합하는 달걸이 정렬 기회가 없습니다.")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.textSecondary)
                            Text("필터를 '전체'로 변경하거나 탐색 기간을 늘려보세요.")
                                .font(.system(size: 12))
                                .foregroundColor(.textSecondary.opacity(0.8))
                        }
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 12) {
                                ForEach(filteredOpportunities) { opp in
                                    OpportunityCardView(
                                        opportunity: opp,
                                        onSelect: {
                                            onSelectOpportunity(opp)
                                            presentationMode.wrappedValue.dismiss()
                                        },
                                        onSave: {
                                            onSaveOpportunity(opp)
                                            presentationMode.wrappedValue.dismiss()
                                        }
                                    )
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                        }
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 2) {
                        Text("🏆 달걸이 골든타임 탐색기")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.textPrimary)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("닫기") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.textSecondary)
                }
            }
        }
        .onAppear {
            calculateOpportunities()
        }
    }
    
    private func changeDays(_ days: Int) {
        guard searchDays != days else { return }
        searchDays = days
        calculateOpportunities()
    }
    
    private func calculateOpportunities() {
        isCalculating = true
        DispatchQueue.global(qos: .userInitiated).async {
            let results = MoonAlignmentFinder.findOpportunities(
                camera: cameraPoint,
                target: targetPoint,
                targetHeightMeters: targetHeightMeters,
                startDate: Date(),
                days: searchDays,
                toleranceDeg: 0.40
            )
            DispatchQueue.main.async {
                self.opportunities = results
                self.isCalculating = false
            }
        }
    }
}

// MARK: - Opportunity Card View
private struct OpportunityCardView: View {
    let opportunity: MoonAlignmentOpportunity
    let onSelect: () -> Void
    let onSave: () -> Void
    
    private var gradeColor: Color {
        switch opportunity.grade {
        case .s: return Color.sunGold
        case .a: return Color.accentBlue
        case .b: return Color.textSecondary
        }
    }
    
    private var skyStageColor: Color {
        switch opportunity.skyStage {
        case .blueHour: return Color(hex: 0x38BDF8)
        case .goldenHour: return Color(hex: 0xFB923C)
        case .nauticalTwilight: return Color(hex: 0x818CF8)
        case .night: return Color(hex: 0x94A3B8)
        case .daytime: return Color(hex: 0xFACC15)
        }
    }
    
    private var formattedDate: String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "ko_KR")
        df.timeZone = EphemerisEngine.seoulCalendar.timeZone
        df.dateFormat = "yyyy.MM.dd (E) HH:mm"
        return df.string(from: opportunity.date)
    }
    
    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // 1. 헤더: 등급 뱃지, 조명 단계, 점수
            HStack(spacing: 8) {
                // 등급 뱃지
                Text(opportunity.grade.title)
                    .font(.system(size: 11.5, weight: .bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(gradeColor.opacity(0.2))
                    .foregroundColor(gradeColor)
                    .cornerRadius(6)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(gradeColor.opacity(0.6), lineWidth: 1)
                    )
                
                // 조명 단계 뱃지
                Text(opportunity.skyStage.shortLabel)
                    .font(.system(size: 11, weight: .semibold))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3.5)
                    .background(skyStageColor.opacity(0.15))
                    .foregroundColor(skyStageColor)
                    .cornerRadius(5)
                
                Spacer()
                
                // 점수 뱃지
                HStack(spacing: 2) {
                    Text("\(opportunity.score)")
                        .font(.system(size: 18, weight: .black, design: .rounded))
                        .foregroundColor(gradeColor)
                    Text("점")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(gradeColor.opacity(0.8))
                }
            }
            
            // 2. 일시 & 달 위상
            HStack(alignment: .firstTextBaseline) {
                Text(formattedDate)
                    .font(.system(size: 15, weight: .bold, design: .monospaced))
                    .foregroundColor(.textPrimary)
                
                Spacer()
                
                Text("\(opportunity.moonDetails.phase.emoji) \(Int((opportunity.moonDetails.illuminationFraction * 100).rounded()))% (\(opportunity.moonDetails.phase.koreanName))")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.moonGlow)
            }
            
            // 3. 촬영 가이드 정보 그리드
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("추천 렌즈")
                        .font(.system(size: 10))
                        .foregroundColor(.textSecondary)
                    Text("🔭 \(opportunity.recommendedFocalLengthMm)mm")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.textPrimary)
                }
                
                Divider().frame(height: 24).background(Color.gridLine)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("정렬 오차")
                        .font(.system(size: 10))
                        .foregroundColor(.textSecondary)
                    Text("±\(String(format: "%.2f", opportunity.angularOffsetDeg))°")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.sunGold)
                }
                
                Divider().frame(height: 24).background(Color.gridLine)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("고도 · 궤적")
                        .font(.system(size: 10))
                        .foregroundColor(.textSecondary)
                    Text("\(String(format: "%.1f", opportunity.moonDetails.altitudeDeg))° · \(opportunity.isMoonRising ? "월출 ↗" : "월몰 ↘")")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.textPrimary)
                }
                
                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(Color.nightSky.opacity(0.6))
            .cornerRadius(8)
            
            // 4. 촬영 조언
            Text(opportunity.skyStage.advice)
                .font(.system(size: 11))
                .foregroundColor(.textSecondary)
                .lineLimit(2)
            
            // 5. 액션 버튼 (지도 이동 & 계획 저장)
            HStack(spacing: 8) {
                Button(action: onSelect) {
                    HStack(spacing: 4) {
                        Image(systemName: "location.fill")
                            .font(.system(size: 11))
                        Text("🎯 이 시간으로 지도 이동")
                            .font(.system(size: 12, weight: .bold))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(Color.sunGold)
                    .foregroundColor(.nightSky)
                    .cornerRadius(7)
                }
                
                Button(action: onSave) {
                    HStack(spacing: 4) {
                        Image(systemName: "bookmark.fill")
                            .font(.system(size: 11))
                        Text("계획 저장")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.surfaceDark)
                    .foregroundColor(.textPrimary)
                    .cornerRadius(7)
                    .overlay(
                        RoundedRectangle(cornerRadius: 7)
                            .stroke(Color.gridLine, lineWidth: 1)
                    )
                }
            }
        }
        .padding(14)
        .background(Color.cardBackground)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(opportunity.grade == .s ? Color.sunGold.opacity(0.4) : Color.gridLine.opacity(0.5), lineWidth: 1)
        )
    }
}
