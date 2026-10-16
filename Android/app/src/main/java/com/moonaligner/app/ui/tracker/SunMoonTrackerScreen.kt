package com.moonaligner.app.ui.tracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonaligner.app.core.astronomy.EphemerisEngine
import com.moonaligner.app.ui.theme.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SunMoonTrackerScreen() {
    val seoulZone = ZoneId.of("Asia/Seoul")
    var today by remember { mutableStateOf(LocalDate.now(seoulZone)) }

    // 슬라이더 시간 (0.0 ~ 24.0시간, 기본값: 현재 시각 실시간 동기화)
    val initialLocalTime = remember { LocalTime.now(seoulZone) }
    var selectedHourFloat by remember {
        mutableFloatStateOf(initialLocalTime.hour + (initialLocalTime.minute / 60f) + (initialLocalTime.second / 3600f))
    }
    var isLiveSync by remember { mutableStateOf(true) }

    LaunchedEffect(isLiveSync) {
        while (isLiveSync) {
            val zdt = ZonedDateTime.now(seoulZone)
            today = zdt.toLocalDate()
            val lt = zdt.toLocalTime()
            selectedHourFloat = lt.hour + (lt.minute / 60f) + (lt.second / 3600f)
            delay(1000L)
        }
    }

    // 선택된 시각의 Instant 계산
    val selectedHour = selectedHourFloat.toInt().coerceIn(0, 23)
    val selectedMinute = ((selectedHourFloat - selectedHour) * 60f).toInt().coerceIn(0, 59)
    val selectedZonedDateTime = remember(today, selectedHourFloat) {
        ZonedDateTime.of(today, LocalTime.of(selectedHour, selectedMinute), seoulZone)
    }
    val selectedInstant = selectedZonedDateTime.toInstant()

    // 서울 기준 위경도
    val lat = 37.5665
    val lon = 126.9780

    // 실시간 천체 위치
    val moon = remember(selectedInstant) { EphemerisEngine.calculateMoon(selectedInstant, lat, lon) }
    val sun = remember(selectedInstant) { EphemerisEngine.calculateSun(selectedInstant, lat, lon) }
    val sunDayTimes = remember(today) { EphemerisEngine.calculateSunDayTimes(today, seoulZone, lat, lon) }

    // 하루 동안의 24시간 태양/달 궤적 데이터 사전 계산 (1시간 간격)
    val hourlyPositions = remember(today) {
        (0..24).map { h ->
            val inst = ZonedDateTime.of(today, LocalTime.of(h % 24, 0), seoulZone).toInstant()
            val s = EphemerisEngine.calculateSun(inst, lat, lon)
            val m = EphemerisEngine.calculateMoon(inst, lat, lon)
            Triple(h, s, m)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightSkyBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 상단 헤더
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "태양 & 달 궤적 트래커",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "서울 (${today.format(DateTimeFormatter.ofPattern("M월 d일 (E)"))}) · ${if (isLiveSync) "🟢 실시간 동기화 중" else "⏸ 수동 시뮬레이션"}",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = String.format("%02d:%02d", selectedHour, selectedMinute),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SunGold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 1. 360도 하늘 레이더 나침반 (Sky Compass Radar)
        Text(text = "하늘 360° 레이더 뷰 (중앙: 천정 90°, 테두리: 지평선 0°)", fontSize = 12.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(SurfaceDark, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f

                // 동심원 (고도 0도 지평선, 30도, 60도, 90도 천정)
                drawCircle(color = GridLine, radius = radius, center = center, style = Stroke(2f))
                drawCircle(color = GridLine, radius = radius * (2f / 3f), center = center, style = Stroke(1f))
                drawCircle(color = GridLine, radius = radius * (1f / 3f), center = center, style = Stroke(1f))
                drawCircle(color = MoonGlow.copy(alpha = 0.5f), radius = 4f, center = center)

                // 십자선 (방위선 N-S, E-W)
                drawLine(GridLine, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 1f)
                drawLine(GridLine, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1f)

                // 태양 하루 궤적선 (주황색 곡선)
                val sunPath = Path()
                var firstSun = true
                hourlyPositions.forEach { (_, s, _) ->
                    if (s.altitudeDeg >= -15.0) { // 지평선 근처까지만 표시
                        val r = radius * ((90f - s.altitudeDeg.toFloat().coerceAtLeast(0f)) / 90f)
                        val azRad = Math.toRadians(s.azimuthDeg - 90.0) // 북쪽 위 기준
                        val pt = Offset(
                            center.x + (r * cos(azRad)).toFloat(),
                            center.y + (r * sin(azRad)).toFloat()
                        )
                        if (firstSun) {
                            sunPath.moveTo(pt.x, pt.y)
                            firstSun = false
                        } else {
                            sunPath.lineTo(pt.x, pt.y)
                        }
                    }
                }
                drawPath(sunPath, color = SunGold.copy(alpha = 0.4f), style = Stroke(3f))

                // 달 하루 궤적선 (연노랑색 곡선)
                val moonPath = Path()
                var firstMoon = true
                hourlyPositions.forEach { (_, _, m) ->
                    if (m.altitudeDeg >= -15.0) {
                        val r = radius * ((90f - m.altitudeDeg.toFloat().coerceAtLeast(0f)) / 90f)
                        val azRad = Math.toRadians(m.azimuthDeg - 90.0)
                        val pt = Offset(
                            center.x + (r * cos(azRad)).toFloat(),
                            center.y + (r * sin(azRad)).toFloat()
                        )
                        if (firstMoon) {
                            moonPath.moveTo(pt.x, pt.y)
                            firstMoon = false
                        } else {
                            moonPath.lineTo(pt.x, pt.y)
                        }
                    }
                }
                drawPath(moonPath, color = MoonSurface.copy(alpha = 0.35f), style = Stroke(2f))

                // 현재 태양 위치 점 (☀️)
                if (sun.altitudeDeg > -10.0) {
                    val sunR = radius * ((90f - sun.altitudeDeg.toFloat().coerceAtLeast(0f)) / 90f)
                    val sunAzRad = Math.toRadians(sun.azimuthDeg - 90.0)
                    val sunPt = Offset(center.x + (sunR * cos(sunAzRad)).toFloat(), center.y + (sunR * sin(sunAzRad)).toFloat())
                    drawCircle(color = SunGold.copy(alpha = 0.35f), radius = 18f, center = sunPt)
                    drawCircle(color = SunGold, radius = 10f, center = sunPt)
                }

                // 현재 달 위치 점 (🌕)
                if (moon.altitudeDeg > -10.0) {
                    val moonR = radius * ((90f - moon.altitudeDeg.toFloat().coerceAtLeast(0f)) / 90f)
                    val moonAzRad = Math.toRadians(moon.azimuthDeg - 90.0)
                    val moonPt = Offset(center.x + (moonR * cos(moonAzRad)).toFloat(), center.y + (moonR * sin(moonAzRad)).toFloat())
                    drawCircle(color = MoonSurface.copy(alpha = 0.35f), radius = 18f, center = moonPt)
                    drawCircle(color = MoonGlow, radius = 9f, center = moonPt)
                }
            }

            // 방위 문자 (N, E, S, W)
            Text("N (북)", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            Text("S (남)", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            Text("W (서)", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
            Text("E (동)", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. 24시간 타임 슬라이더
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "시간 이동 (00:00 ~ 24:00)", color = TextSecondary, fontSize = 13.sp)
            TextButton(
                onClick = {
                    val zdt = ZonedDateTime.now(seoulZone)
                    today = zdt.toLocalDate()
                    val lt = zdt.toLocalTime()
                    selectedHourFloat = lt.hour + (lt.minute / 60f) + (lt.second / 3600f)
                    isLiveSync = true
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("현재 시각으로 복귀", fontSize = 12.sp, color = MoonGlow)
            }
        }
        Slider(
            value = selectedHourFloat,
            onValueChange = {
                isLiveSync = false
                selectedHourFloat = it
            },
            valueRange = 0f..24f,
            colors = SliderDefaults.colors(thumbColor = SunGold, activeTrackColor = SunGold)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. 태양 & 골든아워 상세 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "☀️ 태양 위치 & 사진 골든아워", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    val statusText = when {
                        sun.isGoldenHour -> "골든 아워 🌅"
                        sun.isBlueHour -> "블루 아워 🌌"
                        sun.altitudeDeg > 0 -> "낮 ☀️"
                        else -> "밤 🌙"
                    }
                    Text(text = statusText, color = SunGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("방위각 (Azimuth)", color = TextSecondary, fontSize = 12.sp)
                        Text("${String.format("%.1f", sun.azimuthDeg)}°", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("고도각 (Altitude)", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            text = "${String.format("%.1f", sun.altitudeDeg)}°",
                            color = if (sun.altitudeDeg > 0) SunGold else TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text("촬영 조도", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            text = if (sun.altitudeDeg > 0) "직사광" else "여명/야간",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SunOnTrack 일출/일몰 & 일조량 상세 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "☀️ 일조 시간 및 태양 운행 (SunOnTrack)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = "총 ${sunDayTimes.daylightDurationMinutes / 60}시간 ${sunDayTimes.daylightDurationMinutes % 60}분",
                        color = SunGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("🌅 일출 시각", color = TextSecondary, fontSize = 12.sp)
                        Text(sunDayTimes.sunriseTimeStr, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("${String.format("%.1f", sunDayTimes.sunriseAzimuthDeg)}° (동)", color = TextSecondary, fontSize = 11.sp)
                    }
                    Column {
                        Text("☀️ 남중 고도", color = TextSecondary, fontSize = 12.sp)
                        Text("최고 ${String.format("%.1f", sunDayTimes.maxAltitudeDeg)}°", color = SunGold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("180.0° (정남)", color = TextSecondary, fontSize = 11.sp)
                    }
                    Column {
                        Text("🌇 일몰 시각", color = TextSecondary, fontSize = 12.sp)
                        Text(sunDayTimes.sunsetTimeStr, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("${String.format("%.1f", sunDayTimes.sunsetAzimuthDeg)}° (서)", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. 달 상세 정보 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🌕 달 위치 & 위상 정보", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = "${moon.phase.emoji} ${moon.phase.displayName} (밝기 ${(moon.illuminationFraction * 100).roundToInt()}%)",
                        color = MoonGlow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("방위각 (Azimuth)", color = TextSecondary, fontSize = 12.sp)
                        Text("${String.format("%.1f", moon.azimuthDeg)}°", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("고도각 (Altitude)", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            text = "${String.format("%.1f", moon.altitudeDeg)}°",
                            color = if (moon.altitudeDeg > 0) MoonGlow else TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text("지구-달 거리", color = TextSecondary, fontSize = 12.sp)
                        Text("${Math.round(moon.distanceKm).toInt()} km", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("달 시직경", color = TextSecondary, fontSize = 12.sp)
                        Text("${String.format("%.3f", moon.angularDiameterDeg)}°", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
