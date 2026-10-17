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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonaligner.app.core.astronomy.EphemerisEngine
import com.moonaligner.app.core.optics.OpticsCalculator
import com.moonaligner.app.ui.planner.AlignmentAnchorMode
import com.moonaligner.app.ui.planner.CelestialTargetType
import com.moonaligner.app.ui.planner.PinMoveMode
import com.moonaligner.app.ui.planner.PlannerSharedState
import com.moonaligner.app.ui.theme.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(sharedState: PlannerSharedState) {
    val selectedSubjectType = sharedState.selectedSubjectType
    val subjectHeight = sharedState.subjectHeight
    val scaleRatio = sharedState.scaleRatio
    val focalLength = sharedState.focalLength
    val selectedSensor = sharedState.selectedSensor
    val isSun = sharedState.celestialTarget == CelestialTargetType.SUN

    val zoneId = remember { ZoneId.systemDefault() }
    val sunTimes = remember(sharedState.cameraPoint) {
        EphemerisEngine.calculateSunDayTimes(
            date = LocalDate.now(zoneId),
            zoneId = zoneId,
            latitudeDeg = sharedState.cameraPoint.latitudeDeg,
            longitudeDeg = sharedState.cameraPoint.longitudeDeg
        )
    }

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
            text = if (isSun) "태양 화각 압축 계산기" else "화각 압축 계산기",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = if (isSun) "일출·일몰 오메가 선셋 & 다이아몬드 타워 최적 거리" else "달-피사체 크기 비율 및 최적 촬영 거리",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 🌕 달 / ☀️ 태양 계산기 모드 전환 세그먼트 탭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CelestialTargetType.entries.forEach { target ->
                val isSelected = sharedState.celestialTarget == target
                val btnColor = if (isSelected) {
                    if (target == CelestialTargetType.MOON) SunGold else Color(0xFFFF7043)
                } else {
                    CardBackground
                }
                val textColor = if (isSelected) {
                    if (target == CelestialTargetType.MOON) NightSkyBackground else Color.White
                } else {
                    TextSecondary
                }

                Button(
                    onClick = { sharedState.celestialTarget = target },
                    colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (target == CelestialTargetType.MOON) "🌕 달 계산기" else "☀️ 태양 계산기 (일출·일몰)",
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor
                    )
                }
            }
        }

        // 태양 모드 시 일출 & 일몰 정보 칩
        if (isSun) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .background(Color(0x33EA580C), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🌅 일출", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFDBA74))
                    Text(sunTimes.sunriseTimeStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                }
                Row(
                    modifier = Modifier
                        .background(Color(0x33DC2626), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🌇 일몰", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFCA5A5))
                    Text(sunTimes.sunsetTimeStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text("매직아워 ±30분", fontSize = 10.5.sp, color = SunGold, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

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
                        selectedContainerColor = if (isSun) Color(0xFFFF7043) else SunGold,
                        selectedLabelColor = if (isSun) Color.White else NightSkyBackground,
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

        val viewfinderBrush = if (isSun) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF181124),
                    Color(0xFF3E1838),
                    Color(0xFF822637),
                    Color(0xFFC84B25),
                    Color(0xFFF59E0B)
                )
            )
        } else {
            Brush.verticalGradient(listOf(Color(0xFF06090F), Color(0xFF06090F)))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(viewfinderBrush, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0x4464748B), RoundedCornerShape(14.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)

                val gridColor = Color(0x18FFFFFF)
                drawLine(gridColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), strokeWidth = 1f)
                drawLine(gridColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), strokeWidth = 1f)

                val celestialRadiusPx = (size.height * moonFrameFraction.toFloat() / 2f).coerceAtLeast(15f)

                if (isSun) {
                    MoonArtRenderer.drawRealisticSun(
                        drawScope = this,
                        center = center,
                        radius = celestialRadiusPx
                    )
                } else {
                    MoonArtRenderer.drawRealisticFullMoon(
                        drawScope = this,
                        center = center,
                        radius = celestialRadiusPx
                    )
                }

                MoonArtRenderer.drawSubjectSilhouette(
                    drawScope = this,
                    type = selectedSubjectType,
                    center = center,
                    moonRadius = celestialRadiusPx,
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
                    text = if (isSun) "태양 화각: ${(moonFrameFraction * 100).roundToInt()}%" else "달 화각: ${(moonFrameFraction * 100).roundToInt()}%",
                    color = if (isSun) Color(0xFFFDBA74) else SunGold,
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
                    text = if (isSun) "ISO 100  1/1000s  f/8" else "ISO 100  1/250s  f/11",
                    color = Color(0x99FFFFFF),
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

        // 3. 필요 촬영 거리 & 지도 연동 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "필요 촬영 거리",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0x204ADE80), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF4ADE80), RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "지도 연동",
                            color = Color(0xFF4ADE80),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    val distKm = requiredDistance / 1000.0
                    if (requiredDistance >= 1000.0) {
                        Text(
                            text = String.format("%.2f", distKm),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = MoonGlow
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "km",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    } else {
                        Text(
                            text = "${requiredDistance.roundToInt()}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = MoonGlow
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "m",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 기준점 고정 모드 (피사체 고정 vs 카메라 고정)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isTargetFixed = sharedState.anchorMode == AlignmentAnchorMode.TARGET_FIXED
                    Button(
                        onClick = {
                            sharedState.anchorMode = AlignmentAnchorMode.TARGET_FIXED
                            sharedState.pinMoveMode = PinMoveMode.CAMERA
                            sharedState.alignCameraToCalculatorDistance()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTargetFixed) AccentBlue else SurfaceDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "📍 피사체 고정",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    val isCamFixed = sharedState.anchorMode == AlignmentAnchorMode.CAMERA_FIXED
                    Button(
                        onClick = {
                            sharedState.anchorMode = AlignmentAnchorMode.CAMERA_FIXED
                            sharedState.pinMoveMode = PinMoveMode.TARGET
                            sharedState.alignTargetToCalculatorDistance()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCamFixed) SunGold else SurfaceDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "📷 카메라 고정",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCamFixed) NightSkyBackground else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 지도에서 위치 찾기 원탭 버튼
                Button(
                    onClick = {
                        sharedState.alignToCalculatorDistance()
                        sharedState.selectedTab = 0
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSun) Color(0xFFFF7043) else SunGold
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🗺️ 지도에서 위치 확인 →",
                        color = if (isSun) Color.White else NightSkyBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. 슬라이더 컨트롤러
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "피사체 높이", color = TextSecondary, fontSize = 12.sp)
            Text(text = "${String.format("%.1f", subjectHeight)} m", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = subjectHeight,
            onValueChange = {
                sharedState.subjectHeight = it
                sharedState.alignToCalculatorDistance()
            },
            valueRange = 1f..600f,
            colors = SliderDefaults.colors(thumbColor = if (isSun) Color(0xFFFF7043) else SunGold, activeTrackColor = if (isSun) Color(0xFFFF7043) else SunGold)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val targetName = if (isSun) "태양" else "달"
            Text(text = "$targetName 대비 크기", color = TextSecondary, fontSize = 12.sp)
            val matchText = when {
                scaleRatio < 0.85f -> "${targetName}이 큼"
                scaleRatio > 1.15f -> "피사체가 큼"
                else -> "1:1 일치"
            }
            Text(
                text = "${String.format("%.2f", scaleRatio)}x ($matchText)",
                color = if (isSun) Color(0xFFFDBA74) else SunGold,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = scaleRatio,
            onValueChange = {
                sharedState.scaleRatio = it
                sharedState.alignToCalculatorDistance()
            },
            valueRange = 0.2f..2.5f,
            colors = SliderDefaults.colors(thumbColor = if (isSun) Color(0xFFFF7043) else SunGold, activeTrackColor = if (isSun) Color(0xFFFF7043) else SunGold)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "렌즈 초점거리", color = TextSecondary, fontSize = 12.sp)
            Text(text = "${focalLength.roundToInt()} mm", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = focalLength,
            onValueChange = { sharedState.focalLength = it },
            valueRange = 70f..1200f,
            colors = SliderDefaults.colors(thumbColor = MoonGlow, activeTrackColor = MoonGlow)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(200f, 400f, 600f, 800f, 1200f).forEach { mm ->
                val isSelected = focalLength.roundToInt() == mm.roundToInt()
                Button(
                    onClick = { sharedState.focalLength = mm },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) (if (isSun) Color(0xFFFF7043) else SunGold) else CardBackground,
                        contentColor = if (isSelected) (if (isSun) Color.White else NightSkyBackground) else TextPrimary
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
            text = if (isSun) "D = H × 110.5 (태양 각지름 0.53° 기준)" else "D = H × 110.5 (달 각지름 0.52° 기준)",
            color = TextSecondary.copy(alpha = 0.8f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
