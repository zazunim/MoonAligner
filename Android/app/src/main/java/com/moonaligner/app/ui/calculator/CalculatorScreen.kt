package com.moonaligner.app.ui.calculator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonaligner.app.core.optics.OpticsCalculator
import com.moonaligner.app.ui.planner.AlignmentAnchorMode
import com.moonaligner.app.ui.planner.PinMoveMode
import com.moonaligner.app.ui.planner.PlannerSharedState
import com.moonaligner.app.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(sharedState: PlannerSharedState) {
    val selectedSubjectType = sharedState.selectedSubjectType
    val subjectHeight = sharedState.subjectHeight
    val scaleRatio = sharedState.scaleRatio
    val focalLength = sharedState.focalLength
    val selectedSensor = sharedState.selectedSensor

    // 피사체 종류 변경 시 기본 높이 자동 동기화 및 플래너 맵 위치 자동 정렬
    fun selectSubject(type: SubjectSilhouetteType) {
        sharedState.selectedSubjectType = type
        sharedState.subjectHeight = type.defaultHeightM
        sharedState.alignToCalculatorDistance()
    }

    val requiredDistance = sharedState.calculatorRequiredDistance

    val moonDiameterOnSensor = remember(focalLength) {
        OpticsCalculator.calculateMoonImageDiameterOnSensorMm(focalLength.toDouble())
    }

    val moonFrameFraction = remember(moonDiameterOnSensor, selectedSensor) {
        (moonDiameterOnSensor / selectedSensor.heightMm).coerceIn(0.0, 1.0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightSkyBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "화각 압축 계산기",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "달-피사체 크기 비율 및 최적 촬영 거리",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 1. 피사체 선택 가로 스크롤 칩
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SubjectSilhouetteType.entries.forEach { type ->
                val isSelected = type == selectedSubjectType
                FilterChip(
                    selected = isSelected,
                    onClick = { selectSubject(type) },
                    label = { Text(type.displayName, fontSize = 11.5.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SunGold,
                        selectedLabelColor = NightSkyBackground,
                        containerColor = CardBackground,
                        labelColor = TextPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. 가상 뷰파인더 프리뷰
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "가상 뷰파인더",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${selectedSensor.displayName} · ${focalLength.roundToInt()}mm",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF06090F), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0x4464748B), RoundedCornerShape(14.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)

                val gridColor = Color(0x18FFFFFF)
                drawLine(gridColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), strokeWidth = 1f)
                drawLine(gridColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), strokeWidth = 1f)

                val moonRadiusPx = (size.height * moonFrameFraction.toFloat() / 2f).coerceAtLeast(15f)

                MoonArtRenderer.drawRealisticFullMoon(
                    drawScope = this,
                    center = center,
                    radius = moonRadiusPx
                )

                MoonArtRenderer.drawSubjectSilhouette(
                    drawScope = this,
                    type = selectedSubjectType,
                    center = center,
                    moonRadius = moonRadiusPx,
                    scaleRatio = scaleRatio
                )

                val chLen = 14.dp.toPx()
                val chColor = Color(0x40FFFFFF)
                drawLine(chColor, Offset(center.x - chLen, center.y), Offset(center.x + chLen, center.y), strokeWidth = 1f)
                drawLine(chColor, Offset(center.x, center.y - chLen), Offset(center.x, center.y + chLen), strokeWidth = 1f)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[ ${selectedSensor.displayName} | ${focalLength.roundToInt()}mm ]",
                    color = Color(0xCCFFFFFF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "달 화각: ${(moonFrameFraction * 100).roundToInt()}%",
                    color = SunGold,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ISO 100  1/250s  f/11",
                    color = Color(0x88FFFFFF),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${selectedSubjectType.displayName} (${String.format("%.1f", subjectHeight)}m)",
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. 필수 촬영 거리 결과 요약 & 플래너 맵 자동 연동 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "필요 촬영 거리", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF4ADE80), RoundedCornerShape(3.dp)))
                        Text(
                            text = "지도 연동",
                            color = Color(0xFF4ADE80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val distNumber = if (requiredDistance >= 1000.0) {
                    String.format("%.2f km", requiredDistance / 1000.0)
                } else {
                    "${requiredDistance.roundToInt()} m"
                }
                Text(
                    text = distNumber,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MoonGlow
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 지도 자동 정렬 기준 세그먼트 (피사체 고정 vs 카메라 고정)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            sharedState.anchorMode = AlignmentAnchorMode.TARGET_FIXED
                            sharedState.pinMoveMode = PinMoveMode.CAMERA
                            sharedState.alignCameraToCalculatorDistance()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.anchorMode == AlignmentAnchorMode.TARGET_FIXED) Color(0xFF0284C7) else SurfaceDark,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("피사체 고정", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            sharedState.anchorMode = AlignmentAnchorMode.CAMERA_FIXED
                            sharedState.pinMoveMode = PinMoveMode.TARGET
                            sharedState.alignTargetToCalculatorDistance()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.anchorMode == AlignmentAnchorMode.CAMERA_FIXED) Color(0xFFE11D48) else SurfaceDark,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("카메라 고정", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        sharedState.alignToCalculatorDistance()
                        sharedState.centerMapTrigger = if (sharedState.anchorMode == AlignmentAnchorMode.TARGET_FIXED) {
                            sharedState.cameraPoint
                        } else {
                            sharedState.targetPoint
                        }
                        sharedState.selectedTab = 0
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("지도에서 위치 확인 ➔", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. 슬라이더 컨트롤러
        // (A) 피사체 실제 높이
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "피사체 높이", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(text = "${String.format("%.1f", subjectHeight)} m", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = subjectHeight.coerceIn(1f, 600f),
            onValueChange = {
                sharedState.subjectHeight = it
                sharedState.alignToCalculatorDistance()
            },
            valueRange = 1.0f..600.0f,
            colors = SliderDefaults.colors(thumbColor = MoonGlow, activeTrackColor = MoonSurface)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // (B) 달 대비 크기 비율 (배경압축 배율)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "달 대비 크기", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            val desc = when {
                scaleRatio < 0.85f -> "달이 큼"
                scaleRatio in 0.85f..1.15f -> "1:1 일치"
                else -> "피사체가 큼"
            }
            Text(text = "${String.format("%.2f", scaleRatio)}x ($desc)", color = SunGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = scaleRatio,
            onValueChange = {
                sharedState.scaleRatio = it
                sharedState.alignToCalculatorDistance()
            },
            valueRange = 0.2f..2.5f,
            colors = SliderDefaults.colors(thumbColor = SunGold, activeTrackColor = SunGold)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // (C) 렌즈 초점거리 및 센서 크기
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "렌즈 초점거리", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(text = "${focalLength.roundToInt()} mm", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = focalLength,
            onValueChange = { sharedState.focalLength = it },
            valueRange = 70f..1200f,
            colors = SliderDefaults.colors(thumbColor = MoonGlow, activeTrackColor = MoonSurface)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(200f, 400f, 600f, 800f, 1200f).forEach { mm ->
                val isSelected = focalLength.roundToInt() == mm.toInt()
                FilledTonalButton(
                    onClick = { sharedState.focalLength = mm },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isSelected) SunGold else CardBackground,
                        contentColor = if (isSelected) NightSkyBackground else TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "${mm.toInt()}", fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. 광학 공식 컴팩트 칩
        Text(
            text = "D = H × 110.5 (달 각지름 0.52° 기준)",
            color = TextSecondary.copy(alpha = 0.8f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
