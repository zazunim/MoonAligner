package com.moonaligner.app.ui.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moonaligner.app.core.astronomy.EphemerisEngine
import com.moonaligner.app.ui.theme.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * CameraX 실제 카메라 프리뷰 + 3D 증강현실(AR) 천체 궤적 추적 화면 (Sun Seeker & SunOnTrack 스타일)
 *
 * 카메라 권한이 없거나 에뮬레이터 환경일 때도 가상 스카이 돔과 화면 밖 천체 유도 화살표가 작동합니다.
 */
@Composable
fun ArTrackerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 카메라 런타임 권한 상태 관리
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 기기 지향 각도 (방위각 0~360, 피치/앙각 -90~+90)
    var deviceAzimuth by remember { mutableFloatStateOf(0f) }
    var devicePitch by remember { mutableFloatStateOf(0f) }

    // 현재 서울 기준 달 및 태양 위치 (1초 주기 실시간 갱신)
    val seoulZone = ZoneId.of("Asia/Seoul")
    var today by remember { mutableStateOf(LocalDate.now(seoulZone)) }
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            today = LocalDate.now(seoulZone)
            delay(1000L)
        }
    }

    val moon = remember(now) { EphemerisEngine.calculateMoon(now, 37.5665, 126.9780) }
    val sun = remember(now) { EphemerisEngine.calculateSun(now, 37.5665, 126.9780) }

    // 24시간 태양 & 달의 하늘 궤적 사전 계산 (1시간 간격)
    val hourlyTracks = remember(today) {
        (0..24).map { h ->
            val inst = ZonedDateTime.of(today, LocalTime.of(h % 24, 0), seoulZone).toInstant()
            val s = EphemerisEngine.calculateSun(inst, 37.5665, 126.9780)
            val m = EphemerisEngine.calculateMoon(inst, 37.5665, 126.9780)
            Triple(h, s, m)
        }
    }

    // 자이로 & 지자기 회전 벡터 센서 리스너
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)

                    val azDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()

                    deviceAzimuth = (azDeg + 360f) % 360f
                    devicePitch = -pitchDeg
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 배경 레이어 (카메라 프리뷰 또는 가상 스카이 돔 폴백)
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        } catch (_: Exception) {
                            // 에뮬레이터 또는 카메라 바인딩 실패 시
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 카메라 권한 미허용 또는 에뮬레이터 시: 가상 스카이 돔(Virtual Sky Dome) 렌더링
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF020617), // 딥 스페이스 블랙
                                Color(0xFF0F172A), // 나이트 미드나잇 블루
                                Color(0xFF1E293B)  // 지평선 여명 그라데이션
                            )
                        )
                    )
            )
        }

        // 2. 증강현실(AR) 3D 천체 및 궤적 오버레이 캔버스
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)

            // 카메라 수평 화각 약 60도, 수직 화각 약 45도 기준 픽셀 매핑
            val pxPerDegX = size.width / 60f
            val pxPerDegY = size.height / 45f

            // (A) 수평선 (Horizon Line)
            val horizonY = center.y + (devicePitch * pxPerDegY)
            if (horizonY in -100f..(size.height + 100f)) {
                drawLine(
                    color = Color(0x6694A3B8),
                    start = Offset(0f, horizonY),
                    end = Offset(size.width, horizonY),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 16f))
                )

                // 8방위 나침반 틱 및 라벨 (N, NE, E, SE, S, SW, W, NW)
                val cardinals = listOf(
                    0 to "북 (N)", 45 to "북동 (NE)", 90 to "동 (E)", 135 to "남동 (SE)",
                    180 to "남 (S)", 225 to "남서 (SW)", 270 to "서 (W)", 315 to "북서 (NW)"
                )
                val textPaint = AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 11f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }

                for ((deg, label) in cardinals) {
                    val dAz = ((deg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
                    val cardX = center.x + (dAz * pxPerDegX)
                    if (cardX in 0f..size.width) {
                        drawLine(
                            color = Color(0x99FFFFFF),
                            start = Offset(cardX, horizonY - 10f),
                            end = Offset(cardX, horizonY + 10f),
                            strokeWidth = 2f
                        )
                        drawContext.canvas.nativeCanvas.drawText(label, cardX, horizonY - 14f, textPaint)
                    }
                }
            }

            // (B) 24시간 태양 & 달 3D 궤적 호 (Sun & Moon Celestial Arcs)
            val sunArcPoints = mutableListOf<Offset>()
            val moonArcPoints = mutableListOf<Offset>()

            for ((_, s, m) in hourlyTracks) {
                val dAzS = ((s.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
                val dPitchS = s.altitudeDeg.toFloat() - devicePitch
                val sx = center.x + (dAzS * pxPerDegX)
                val sy = center.y - (dPitchS * pxPerDegY)
                if (dAzS in -90f..90f) {
                    sunArcPoints.add(Offset(sx, sy))
                }

                val dAzM = ((m.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
                val dPitchM = m.altitudeDeg.toFloat() - devicePitch
                val mx = center.x + (dAzM * pxPerDegX)
                val my = center.y - (dPitchM * pxPerDegY)
                if (dAzM in -90f..90f) {
                    moonArcPoints.add(Offset(mx, my))
                }
            }

            // 태양 하루 궤적 선 (황금색 점선 아치)
            if (sunArcPoints.size >= 2) {
                val sunPath = Path().apply {
                    moveTo(sunArcPoints[0].x, sunArcPoints[0].y)
                    for (i in 1 until sunArcPoints.size) {
                        lineTo(sunArcPoints[i].x, sunArcPoints[i].y)
                    }
                }
                drawPath(
                    path = sunPath,
                    color = SunGold.copy(alpha = 0.5f),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                )
            }

            // 달 하루 궤적 선 (은빛 점선 아치)
            if (moonArcPoints.size >= 2) {
                val moonPath = Path().apply {
                    moveTo(moonArcPoints[0].x, moonArcPoints[0].y)
                    for (i in 1 until moonArcPoints.size) {
                        lineTo(moonArcPoints[i].x, moonArcPoints[i].y)
                    }
                }
                drawPath(
                    path = moonPath,
                    color = MoonGlow.copy(alpha = 0.5f),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                )
            }

            // (C) 달의 현재 상대 좌표 및 렌더링
            val dAzMoon = ((moon.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
            val dPitchMoon = moon.altitudeDeg.toFloat() - devicePitch
            val moonX = center.x + (dAzMoon * pxPerDegX)
            val moonY = center.y - (dPitchMoon * pxPerDegY)
            val isMoonOnScreen = moonX in 40f..(size.width - 40f) && moonY in 40f..(size.height - 40f)

            if (isMoonOnScreen) {
                // 달이 화면 내에 있을 때 (원형 글로우와 위상 아이콘)
                drawCircle(color = MoonGlow.copy(alpha = 0.35f), radius = 34.dp.toPx(), center = Offset(moonX, moonY))
                drawCircle(color = MoonSurface, radius = 22.dp.toPx(), center = Offset(moonX, moonY))
                drawCircle(color = Color.White, radius = 26.dp.toPx(), center = Offset(moonX, moonY), style = Stroke(2.dp.toPx()))

                val moonTextPaint = AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 12f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText("🌕 ${moon.phase.displayName} (고도 ${moon.altitudeDeg.roundToInt()}°)", moonX, moonY + 44.dp.toPx(), moonTextPaint)
            } else {
                // 달이 화면 밖에 있을 때: 화면 가장자리에 달 유도 나침반 인디케이터 표시!
                val edgeMargin = 45f
                val clampedX = moonX.coerceIn(edgeMargin, size.width - edgeMargin)
                val clampedY = moonY.coerceIn(edgeMargin + 90f, size.height - edgeMargin)

                drawCircle(color = Color(0xE61E1B4B), radius = 22.dp.toPx(), center = Offset(clampedX, clampedY))
                drawCircle(color = MoonGlow, radius = 22.dp.toPx(), center = Offset(clampedX, clampedY), style = Stroke(2.dp.toPx()))

                val guidePaint = AndroidPaint().apply {
                    color = android.graphics.Color.YELLOW
                    textSize = 10f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }
                val dirArrow = if (dAzMoon > 0) "➔" else "◀"
                drawContext.canvas.nativeCanvas.drawText("🌕 $dirArrow ${dAzMoon.roundToInt().let { if (it < 0) -it else it }}°", clampedX, clampedY + 4f, guidePaint)
            }

            // (D) 태양의 현재 상대 좌표 및 렌더링
            val dAzSun = ((sun.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
            val dPitchSun = sun.altitudeDeg.toFloat() - devicePitch
            val sunX = center.x + (dAzSun * pxPerDegX)
            val sunY = center.y - (dPitchSun * pxPerDegY)
            val isSunOnScreen = sunX in 40f..(size.width - 40f) && sunY in 40f..(size.height - 40f)

            if (isSunOnScreen) {
                // 태양이 화면 내에 있을 때
                drawCircle(color = SunGold.copy(alpha = 0.4f), radius = 38.dp.toPx(), center = Offset(sunX, sunY))
                drawCircle(color = SunGold, radius = 24.dp.toPx(), center = Offset(sunX, sunY))
                drawCircle(color = Color(0xFFFEF08A), radius = 28.dp.toPx(), center = Offset(sunX, sunY), style = Stroke(2.dp.toPx()))

                val sunTextPaint = AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 12f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText("☀️ 태양 (고도 ${sun.altitudeDeg.roundToInt()}°)", sunX, sunY + 46.dp.toPx(), sunTextPaint)
            } else {
                // 태양이 화면 밖에 있을 때: 화면 가장자리에 태양 유도 화살표 인디케이터 표시!
                val edgeMargin = 45f
                val clampedX = sunX.coerceIn(edgeMargin, size.width - edgeMargin)
                val clampedY = sunY.coerceIn(edgeMargin + 90f, size.height - edgeMargin)

                drawCircle(color = Color(0xE678350F), radius = 22.dp.toPx(), center = Offset(clampedX, clampedY))
                drawCircle(color = SunGold, radius = 22.dp.toPx(), center = Offset(clampedX, clampedY), style = Stroke(2.dp.toPx()))

                val guidePaint = AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 10f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }
                val dirArrow = if (dAzSun > 0) "➔" else "◀"
                drawContext.canvas.nativeCanvas.drawText("☀️ $dirArrow ${dAzSun.roundToInt().let { if (it < 0) -it else it }}°", clampedX, clampedY + 4f, guidePaint)
            }

            // (E) 화면 중앙 카메라 조준 십자선 (Crosshair)
            val crosshairSize = 22.dp.toPx()
            drawLine(color = Color.White.copy(alpha = 0.65f), start = Offset(center.x - crosshairSize, center.y), end = Offset(center.x + crosshairSize, center.y), strokeWidth = 2f)
            drawLine(color = Color.White.copy(alpha = 0.65f), start = Offset(center.x, center.y - crosshairSize), end = Offset(center.x, center.y + crosshairSize), strokeWidth = 2f)
        }

        // 3. 상단 HUD 정보창
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.88f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "조준 방향: ${deviceAzimuth.roundToInt()}° (고도 ${devicePitch.roundToInt()}°)",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "☀️ 태양 ${sun.azimuthDeg.roundToInt()}°(고도 ${sun.altitudeDeg.roundToInt()}°) | 🌕 달 ${moon.azimuthDeg.roundToInt()}°(고도 ${moon.altitudeDeg.roundToInt()}°)",
                            color = MoonGlow,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = if (sun.altitudeDeg > 0) "☀️ 주간" else "🌙 야간",
                        color = if (sun.altitudeDeg > 0) SunGold else AccentOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 카메라 권한 미허용 안내 배너 (권한이 없을 때만 표시)
            if (!hasCameraPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xDD78350F)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "💡 실제 카메라 배경을 보려면 권한이 필요합니다",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("권한 허용", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
