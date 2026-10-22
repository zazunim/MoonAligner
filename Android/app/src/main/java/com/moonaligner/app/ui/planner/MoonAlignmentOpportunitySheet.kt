package com.moonaligner.app.ui.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonaligner.app.core.alignment.AlignmentFinder
import com.moonaligner.app.core.alignment.GeoPoint
import com.moonaligner.app.core.alignment.MoonAlignmentFinder
import com.moonaligner.app.core.alignment.MoonAlignmentOpportunity
import com.moonaligner.app.core.alignment.OpportunityFilter
import com.moonaligner.app.core.alignment.OpportunitySort
import com.moonaligner.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoonAlignmentOpportunitySheet(
    cameraPoint: GeoPoint,
    targetPoint: GeoPoint,
    targetHeightMeters: Double,
    subjectName: String,
    onDismissRequest: () -> Unit,
    onSelectOpportunity: (MoonAlignmentOpportunity) -> Unit,
    onSaveOpportunity: (MoonAlignmentOpportunity) -> Unit
) {
    var searchDays by remember { mutableStateOf(60) }
    var selectedFilter by remember { mutableStateOf(OpportunityFilter.ALL) }
    var selectedSort by remember { mutableStateOf(OpportunitySort.BY_SCORE) }
    var opportunities by remember { mutableStateOf<List<MoonAlignmentOpportunity>>(emptyList()) }
    var isCalculating by remember { mutableStateOf(true) }
    var showDaysDropdown by remember { mutableStateOf(false) }

    val distanceMeters = remember(cameraPoint, targetPoint) {
        AlignmentFinder.calculateDistanceMeters(cameraPoint, targetPoint)
    }

    LaunchedEffect(searchDays, cameraPoint, targetPoint, targetHeightMeters) {
        isCalculating = true
        withContext(Dispatchers.Default) {
            val results = MoonAlignmentFinder.findOpportunities(
                camera = cameraPoint,
                target = targetPoint,
                targetHeightMeters = targetHeightMeters,
                startInstant = Instant.now(),
                days = searchDays,
                toleranceDeg = 0.40
            )
            withContext(Dispatchers.Main) {
                opportunities = results
                isCalculating = false
            }
        }
    }

    val filteredOpportunities = remember(opportunities, selectedFilter, selectedSort) {
        var list = opportunities

        when (selectedFilter) {
            OpportunityFilter.ALL -> {}
            OpportunityFilter.GOLDEN_BLUE -> {
                list = list.filter {
                    it.skyStage == MoonAlignmentOpportunity.SkyLightingStage.BLUE_HOUR ||
                            it.skyStage == MoonAlignmentOpportunity.SkyLightingStage.GOLDEN_HOUR
                }
            }
            OpportunityFilter.FULL_MOON -> {
                list = list.filter { it.moonDetails.illuminationFraction >= 0.80 }
            }
        }

        when (selectedSort) {
            OpportunitySort.BY_SCORE -> {
                list = list.sortedWith(
                    compareByDescending<MoonAlignmentOpportunity> { it.score }
                        .thenBy { it.timestamp }
                )
            }
            OpportunitySort.BY_DATE -> {
                list = list.sortedBy { it.timestamp }
            }
        }

        list
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = GridLine) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // 1. 시트 타이틀 바
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "🏆 달걸이 골든타임 탐색기",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "카메라-피사체-보름달 3점 1열 정렬 추천",
                        fontSize = 11.5.sp,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("✕", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 2. 피사체 & 기준 요약 바
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$subjectName 달걸이 정렬",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = SunGold
                    )
                    Text(
                        text = "거리 %.2f km · 높이 %d m 기준".format(Locale.US, distanceMeters / 1000.0, targetHeightMeters.toInt()),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                Box {
                    OutlinedButton(
                        onClick = { showDaysDropdown = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        border = BorderStroke(1.dp, SunGold.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("${searchDays}일간 ▼", fontSize = 11.5.sp, color = SunGold, fontWeight = FontWeight.SemiBold)
                    }

                    DropdownMenu(
                        expanded = showDaysDropdown,
                        onDismissRequest = { showDaysDropdown = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        listOf(30, 60, 90).forEach { d ->
                            DropdownMenuItem(
                                text = { Text("${d}일간 탐색 ${if (d == 60) "(기본)" else ""}", color = TextPrimary) },
                                onClick = {
                                    searchDays = d
                                    showDaysDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            // 3. 필터 & 정렬 탭
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OpportunityFilter.values().forEach { filter ->
                        val isSelected = selectedFilter == filter
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) SunGold else CardBackground,
                            modifier = Modifier.clickable { selectedFilter = filter }
                        ) {
                            Text(
                                text = filter.title,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) NightSkyBackground else TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("정렬:", fontSize = 11.5.sp, color = TextSecondary)
                        OpportunitySort.values().forEach { sort ->
                            val isSelected = selectedSort == sort
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) CardBackground else Color.Transparent,
                                modifier = Modifier.clickable { selectedSort = sort }
                            ) {
                                Text(
                                    text = sort.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MoonGlow else TextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (!isCalculating) {
                        Text(
                            text = "총 ${filteredOpportunities.size}회 발견",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    }
                }
            }

            HorizontalDivider(color = GridLine, thickness = 1.dp)

            // 4. 컨텐츠 리스트
            if (isCalculating) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = SunGold, modifier = Modifier.size(36.dp))
                        Text(
                            text = "향후 ${searchDays}일간의 달 궤적과 3점 1열 정렬 데이터를 계산 중...",
                            fontSize = 12.5.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else if (filteredOpportunities.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🌙", fontSize = 36.sp)
                        Text(
                            text = "선택한 조건에 부합하는 달걸이 정렬 기회가 없습니다.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                        Text(
                            text = "필터를 '전체'로 변경하거나 탐색 기간을 늘려보세요.",
                            fontSize = 12.sp,
                            color = TextSecondary.copy(alpha = 0.8f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredOpportunities, key = { it.id }) { opp ->
                        OpportunityCard(
                            opportunity = opp,
                            onSelect = {
                                onSelectOpportunity(opp)
                                onDismissRequest()
                            },
                            onSave = {
                                onSaveOpportunity(opp)
                                onDismissRequest()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpportunityCard(
    opportunity: MoonAlignmentOpportunity,
    onSelect: () -> Unit,
    onSave: () -> Unit
) {
    val gradeColor = when (opportunity.grade) {
        MoonAlignmentOpportunity.OpportunityGrade.S -> SunGold
        MoonAlignmentOpportunity.OpportunityGrade.A -> AccentBlue
        MoonAlignmentOpportunity.OpportunityGrade.B -> TextSecondary
    }

    val skyStageColor = when (opportunity.skyStage) {
        MoonAlignmentOpportunity.SkyLightingStage.BLUE_HOUR -> Color(0xFF38BDF8)
        MoonAlignmentOpportunity.SkyLightingStage.GOLDEN_HOUR -> Color(0xFFFB923C)
        MoonAlignmentOpportunity.SkyLightingStage.NAUTICAL_TWILIGHT -> Color(0xFF818CF8)
        MoonAlignmentOpportunity.SkyLightingStage.NIGHT -> Color(0xFF94A3B8)
        MoonAlignmentOpportunity.SkyLightingStage.DAYTIME -> Color(0xFFFACC15)
    }

    val zdt = remember(opportunity.timestamp) {
        opportunity.timestamp.atZone(ZoneId.of("Asia/Seoul"))
    }
    val dateStr = remember(zdt) {
        zdt.format(DateTimeFormatter.ofPattern("yyyy.MM.dd (E) HH:mm", Locale.KOREAN))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (opportunity.grade == MoonAlignmentOpportunity.OpportunityGrade.S) SunGold.copy(alpha = 0.4f) else GridLine.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 1. 헤더: 등급 뱃지, 조명 단계, 점수
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 등급 뱃지
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = gradeColor.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = opportunity.grade.title,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = gradeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // 조명 단계 뱃지
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = skyStageColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = opportunity.skyStage.shortLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = skyStageColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                        )
                    }
                }

                // 점수 뱃지
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${opportunity.score}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = gradeColor
                    )
                    Text(
                        text = "점",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 일시 & 달 위상
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateStr,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )

                Text(
                    text = "${opportunity.moonDetails.phase.emoji} ${(opportunity.moonDetails.illuminationFraction * 100).toInt()}% (${opportunity.moonDetails.phase.displayName})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MoonGlow
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. 촬영 가이드 정보 그리드
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = NightSkyBackground.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("추천 렌즈", fontSize = 10.sp, color = TextSecondary)
                        Text("🔭 ${opportunity.recommendedFocalLengthMm}mm", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    VerticalDivider(
                        color = GridLine,
                        modifier = Modifier
                            .height(22.dp)
                            .width(1.dp)
                    )

                    Column {
                        Text("정렬 오차", fontSize = 10.sp, color = TextSecondary)
                        Text("±%.2f°".format(Locale.US, opportunity.angularOffsetDeg), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SunGold)
                    }

                    VerticalDivider(
                        color = GridLine,
                        modifier = Modifier
                            .height(22.dp)
                            .width(1.dp)
                    )

                    Column {
                        Text("고도 · 궤적", fontSize = 10.sp, color = TextSecondary)
                        Text(
                            "%.1f° · %s".format(Locale.US, opportunity.moonDetails.altitudeDeg, if (opportunity.isMoonRising) "월출 ↗" else "월몰 ↘"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 4. 촬영 조언
            Text(
                text = opportunity.skyStage.advice,
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 15.sp,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 5. 액션 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Text("🎯 이 시간으로 지도 이동", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onSave,
                    border = BorderStroke(1.dp, GridLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("계획 저장", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
