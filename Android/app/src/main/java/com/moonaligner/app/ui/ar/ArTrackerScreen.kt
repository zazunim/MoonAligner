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
import android.app.DatePickerDialog
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.moonaligner.app.ui.planner.CelestialTargetType
import com.moonaligner.app.ui.planner.PlannerSharedState
import com.moonaligner.app.ui.theme.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.*

enum class ArTargetMode(val title: String) {
    MOON("🌙 달 추적"),
    SUN("☀️ 태양 추적"),
    BOTH("🌗 전체 보기")
}

@Composable
fun ArTrackerScreen(sharedState: PlannerSharedState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val seoulZone = ZoneId.of("Asia/Seoul")

    // 1. 카메라 권한 관리
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

    // 2. GPS 위치 리스너
    var userLat by remember { mutableDoubleStateOf(sharedState.cameraPoint.latitudeDeg) }
    var userLon by remember { mutableDoubleStateOf(sharedState.cameraPoint.longitudeDeg) }

    DisposableEffect(Unit) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                userLat = loc.latitude
                userLon = loc.longitude
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, locationListener)
            locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                userLat = it.latitude
                userLon = it.longitude
            }
        }

        onDispose {
            locationManager?.removeUpdates(locationListener)
        }
    }

    // 3. 기기 모션 센서 (회전 벡터 + 저주파 필터링으로 손떨림 제거)
    var deviceAzimuth by remember { mutableFloatStateOf(0f) }
    var devicePitch by remember { mutableFloatStateOf(0f) }
    var rawAzimuth by remember { mutableFloatStateOf(0f) }
    var rawPitch by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    // Android Camera vector in device space: (0, 0, -1)
                    // Transformed to World Space (East, North, Up):
                    // camX = -R[2], camY = -R[5], camZ = -R[8]
                    val camX = -rotationMatrix[2]
                    val camY = -rotationMatrix[5]
                    val camZ = -rotationMatrix[8]
                    val horiz = sqrt(camX * camX + camY * camY).coerceAtLeast(0.0001f)

                    val pitchDeg = Math.toDegrees(atan2(camZ.toDouble(), horiz.toDouble())).toFloat()
                    val azDeg = ((Math.toDegrees(atan2(camX.toDouble(), camY.toDouble())).toFloat() + 360f) % 360f)

                    rawAzimuth = azDeg
                    rawPitch = pitchDeg
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    // 60fps 부드러운 각도 보간 루프
    LaunchedEffect(Unit) {
        val alpha = 0.18f
        while (true) {
            val diffAz = ((rawAzimuth - deviceAzimuth + 540f) % 360f) - 180f
            deviceAzimuth = ((deviceAzimuth + diffAz * alpha + 360f) % 360f)
            devicePitch += (rawPitch - devicePitch) * alpha
            delay(16L)
        }
    }

    // 4. 시간 및 천체 계산 상태
    var targetMode by remember {
        mutableStateOf(if (sharedState.celestialTarget == CelestialTargetType.SUN) ArTargetMode.SUN else ArTargetMode.MOON)
    }
    var today by remember { mutableStateOf(LocalDate.now(seoulZone)) }
    var previewDate by remember { mutableStateOf(LocalDate.now(seoulZone)) }
    var now by remember { mutableStateOf(Instant.now()) }
    var isLiveSync by remember { mutableStateOf(true) }
    val nowTime = remember { LocalTime.now(seoulZone) }
    var selectedHourFloat by remember { mutableFloatStateOf(nowTime.hour + nowTime.minute / 60f + nowTime.second / 3600f) }
    var showTimeSlider by remember { mutableStateOf(false) }

    // 플래너 추천 일시가 설정되어 있으면 AR 진입 시 자동 연동
    LaunchedEffect(Unit) {
        sharedState.plannedInstant?.let { planned ->
            val zdt = planned.atZone(seoulZone)
            previewDate = zdt.toLocalDate()
            val lt = zdt.toLocalTime()
            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
            isLiveSync = false
        }
    }

    LaunchedEffect(isLiveSync) {
        while (isLiveSync) {
            now = Instant.now()
            today = LocalDate.now(seoulZone)
            previewDate = today
            val lt = LocalTime.now(seoulZone)
            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
            delay(1000L)
        }
    }

    val effectiveInstant = remember(isLiveSync, now, previewDate, selectedHourFloat) {
        if (isLiveSync) {
            now
        } else {
            val totalSec = (selectedHourFloat * 3600f).toInt().coerceIn(0, 86399)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            ZonedDateTime.of(previewDate, LocalTime.of(h, m, s), seoulZone).toInstant()
        }
    }

    val moon = remember(effectiveInstant, userLat, userLon) {
        EphemerisEngine.calculateMoon(effectiveInstant, userLat, userLon)
    }
    val sun = remember(effectiveInstant, userLat, userLon) {
        EphemerisEngine.calculateSun(effectiveInstant, userLat, userLon)
    }

    // 24시간 천체 궤적 사전 계산
    val activeTrackDate = if (isLiveSync) today else previewDate
    val hourlyTracks = remember(activeTrackDate, userLat, userLon) {
        (0..24).map { h ->
            val inst = ZonedDateTime.of(activeTrackDate, LocalTime.of(h % 24, 0), seoulZone).toInstant()
            val s = EphemerisEngine.calculateSun(inst, userLat, userLon)
            val m = EphemerisEngine.calculateMoon(inst, userLat, userLon)
            Triple(h, s, m)
        }
    }

    val relativeDayText = remember(today, previewDate) {
        val days = ChronoUnit.DAYS.between(today, previewDate)
        when {
            days == 0L -> "오늘"
            days == 1L -> "내일 D+1"
            days > 1L -> "D+${days}일"
            else -> "D${days}일"
        }
    }
    val formattedPreviewDate = remember(previewDate) {
        previewDate.format(DateTimeFormatter.ofPattern("M/d (E)", java.util.Locale.KOREAN))
    }
    val totalMinutes = (selectedHourFloat * 60f).roundToInt().coerceIn(0, 1439)
    val formattedTimeText = String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60)

    val openPreviewDatePicker = {
        val dpd = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                previewDate = LocalDate.of(year, month + 1, dayOfMonth)
                isLiveSync = false
            },
            previewDate.year,
            previewDate.monthValue - 1,
            previewDate.dayOfMonth
        )
        dpd.show()
    }

    // 각거리 계산
    val dAzMoon = remember(moon.azimuthDeg, deviceAzimuth) {
        ((moon.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
    }
    val dPitchMoon = remember(moon.altitudeDeg, devicePitch) {
        moon.altitudeDeg.toFloat() - devicePitch
    }
    val moonAngularDist = remember(dAzMoon, dPitchMoon) {
        sqrt(dAzMoon * dAzMoon + dPitchMoon * dPitchMoon)
    }

    val dAzSun = remember(sun.azimuthDeg, deviceAzimuth) {
        ((sun.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
    }
    val dPitchSun = remember(sun.altitudeDeg, devicePitch) {
        sun.altitudeDeg.toFloat() - devicePitch
    }
    val sunAngularDist = remember(dAzSun, dPitchSun) {
        sqrt(dAzSun * dAzSun + dPitchSun * dPitchSun)
    }

    val isTargetLocked = remember(targetMode, moonAngularDist, sunAngularDist) {
        when (targetMode) {
            ArTargetMode.MOON -> moonAngularDist <= 3.0f
            ArTargetMode.SUN -> sunAngularDist <= 3.0f
            ArTargetMode.BOTH -> moonAngularDist <= 3.0f || sunAngularDist <= 3.0f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 카메라 프리뷰 또는 가상 우주 스카이 돔 배경
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
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF020617),
                                Color(0xFF0F172A),
                                Color(0xFF1E293B)
                            )
                        )
                    )
            )
        }

        // 2. AR 캔버스 오버레이 (지평선, 나침반, 궤적, 천체, 조준선)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val pxPerDeg = size.width / 60f
            val pxPerDegX = pxPerDeg
            val pxPerDegY = pxPerDeg

            // (A) 인공 지평선 (Horizon Line)
            val horizonY = center.y + (devicePitch * pxPerDegY)
            if (horizonY in -100f..(size.height + 100f)) {
                drawLine(
                    color = Color(0x7738BDF8),
                    start = Offset(0f, horizonY),
                    end = Offset(size.width, horizonY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
                )

                val textPaint = AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 10f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }

                val cardinals = listOf(
                    0 to "북(N)", 45 to "북동(NE)", 90 to "동(E)", 135 to "남동(SE)",
                    180 to "남(S)", 225 to "남서(SW)", 270 to "서(W)", 315 to "북서(NW)"
                )

                for ((deg, label) in cardinals) {
                    val dAz = ((deg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
                    val cardX = center.x + (dAz * pxPerDegX)
                    if (cardX in 0f..size.width) {
                        drawLine(
                            color = Color(0xBBFFFFFF),
                            start = Offset(cardX, horizonY - 8f),
                            end = Offset(cardX, horizonY + 8f),
                            strokeWidth = 1.5f
                        )
                        drawContext.canvas.nativeCanvas.drawText(label, cardX, horizonY - 14f, textPaint)
                    }
                }
            }

            // (B) 24시간 태양 & 달 3D 궤적 아크
            val showSun = (targetMode == ArTargetMode.SUN || targetMode == ArTargetMode.BOTH)
            val showMoon = (targetMode == ArTargetMode.MOON || targetMode == ArTargetMode.BOTH)

            val sunArcPoints = mutableListOf<Offset>()
            val moonArcPoints = mutableListOf<Offset>()

            for ((_, s, m) in hourlyTracks) {
                if (showSun) {
                    val dAzS = ((s.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
                    val dPitchS = s.altitudeDeg.toFloat() - devicePitch
                    val sx = center.x + (dAzS * pxPerDegX)
                    val sy = center.y - (dPitchS * pxPerDegY)
                    if (dAzS in -90f..90f) {
                        sunArcPoints.add(Offset(sx, sy))
                    }
                }

                if (showMoon) {
                    val dAzM = ((m.azimuthDeg.toFloat() - deviceAzimuth + 540f) % 360f) - 180f
                    val dPitchM = m.altitudeDeg.toFloat() - devicePitch
                    val mx = center.x + (dAzM * pxPerDegX)
                    val my = center.y - (dPitchM * pxPerDegY)
                    if (dAzM in -90f..90f) {
                        moonArcPoints.add(Offset(mx, my))
                    }
                }
            }

            if (showSun && sunArcPoints.size >= 2) {
                val sunPath = Path().apply {
                    moveTo(sunArcPoints[0].x, sunArcPoints[0].y)
                    for (i in 1 until sunArcPoints.size) {
                        lineTo(sunArcPoints[i].x, sunArcPoints[i].y)
                    }
                }
                drawPath(
                    path = sunPath,
                    color = SunGold.copy(alpha = 0.55f),
                    style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                )
            }

            if (showMoon && moonArcPoints.size >= 2) {
                val moonPath = Path().apply {
                    moveTo(moonArcPoints[0].x, moonArcPoints[0].y)
                    for (i in 1 until moonArcPoints.size) {
                        lineTo(moonArcPoints[i].x, moonArcPoints[i].y)
                    }
                }
                drawPath(
                    path = moonPath,
                    color = MoonGlow.copy(alpha = 0.55f),
                    style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                )
            }

            // (C) 달 렌더링 & 화면 밖 나침반 유도 화살표
            if (showMoon) {
                val moonX = center.x + (dAzMoon * pxPerDegX)
                val moonY = center.y - (dPitchMoon * pxPerDegY)
                val isMoonOnScreen = moonX in 40f..(size.width - 40f) && moonY in 40f..(size.height - 40f)

                if (isMoonOnScreen) {
                    drawCircle(color = MoonGlow.copy(alpha = 0.25f), radius = 36.dp.toPx(), center = Offset(moonX, moonY))
                    drawCircle(color = MoonSurface, radius = 22.dp.toPx(), center = Offset(moonX, moonY))
                    drawCircle(color = MoonGlow, radius = 24.dp.toPx(), center = Offset(moonX, moonY), style = Stroke(2.dp.toPx()))

                    val moonTextPaint = AndroidPaint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 11f * density
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = AndroidPaint.Align.CENTER
                    }
                    val phasePct = (moon.illuminationFraction * 100.0).roundToInt()
                    drawContext.canvas.nativeCanvas.drawText(
                        "${moon.phase.emoji} ${moon.phase.displayName} (${phasePct}%) · %.1f°".format(Locale.US, moon.altitudeDeg),
                        moonX,
                        moonY + 40.dp.toPx(),
                        moonTextPaint
                    )
                } else if (targetMode == ArTargetMode.MOON || targetMode == ArTargetMode.BOTH) {
                    val edgeMargin = 46f
                    val minSafeTopY = if (showTimeSlider) 230.dp.toPx() else 130.dp.toPx()
                    val clampedX = moonX.coerceIn(edgeMargin + 10f, size.width - edgeMargin - 10f)
                    val clampedY = moonY.coerceIn(minSafeTopY, size.height - edgeMargin - 100f)

                    drawRoundRect(
                        color = Color(0xEB1E1B4B),
                        topLeft = Offset(clampedX - 42.dp.toPx(), clampedY - 18.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(84.dp.toPx(), 36.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
                        style = Stroke(1.5f)
                    )
                    drawRoundRect(
                        color = Color(0xEB1E1B4B),
                        topLeft = Offset(clampedX - 42.dp.toPx(), clampedY - 18.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(84.dp.toPx(), 36.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
                    )

                    val guidePaint = AndroidPaint().apply {
                        color = android.graphics.Color.YELLOW
                        textSize = 10f * density
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = AndroidPaint.Align.CENTER
                    }
                    val dirArrow = when {
                        dPitchMoon > 10f && abs(dAzMoon) < 10f -> "▲ 위쪽"
                        dPitchMoon < -10f && abs(dAzMoon) < 10f -> "▼ 아래쪽"
                        dAzMoon > 10f && dPitchMoon > 10f -> "↗ 오른쪽 위"
                        dAzMoon < -10f && dPitchMoon > 10f -> "↖ 왼쪽 위"
                        dAzMoon > 10f && dPitchMoon < -10f -> "↘ 오른쪽 아래"
                        dAzMoon < -10f && dPitchMoon < -10f -> "↙ 왼쪽 아래"
                        dAzMoon > 0f -> "오른쪽 ▶"
                        else -> "◀ 왼쪽"
                    }
                    val dist = moonAngularDist.roundToInt()
                    drawContext.canvas.nativeCanvas.drawText("🌙 $dirArrow ${dist}°", clampedX, clampedY + 4f, guidePaint)
                }
            }

            // (D) 태양 렌더링 & 화면 밖 나침반 유도 화살표
            if (showSun) {
                val sunX = center.x + (dAzSun * pxPerDegX)
                val sunY = center.y - (dPitchSun * pxPerDegY)
                val isSunOnScreen = sunX in 40f..(size.width - 40f) && sunY in 40f..(size.height - 40f)

                if (isSunOnScreen) {
                    drawCircle(color = SunGold.copy(alpha = 0.3f), radius = 42.dp.toPx(), center = Offset(sunX, sunY))
                    drawCircle(color = SunGold, radius = 24.dp.toPx(), center = Offset(sunX, sunY))
                    drawCircle(color = Color(0xFFFEF08A), radius = 26.dp.toPx(), center = Offset(sunX, sunY), style = Stroke(2.dp.toPx()))

                    val sunTextPaint = AndroidPaint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 11f * density
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = AndroidPaint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.drawText("☀️ 태양 (고도 %.1f°)".format(Locale.US, sun.altitudeDeg), sunX, sunY + 42.dp.toPx(), sunTextPaint)
                } else if (targetMode == ArTargetMode.SUN || targetMode == ArTargetMode.BOTH) {
                    val edgeMargin = 46f
                    val minSafeTopY = if (showTimeSlider) 230.dp.toPx() else 130.dp.toPx()
                    val clampedX = sunX.coerceIn(edgeMargin + 10f, size.width - edgeMargin - 10f)
                    val clampedY = sunY.coerceIn(minSafeTopY, size.height - edgeMargin - 100f)

                    drawRoundRect(
                        color = Color(0xEB78350F),
                        topLeft = Offset(clampedX - 42.dp.toPx(), clampedY - 18.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(84.dp.toPx(), 36.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
                        style = Stroke(1.5f)
                    )
                    drawRoundRect(
                        color = Color(0xEB78350F),
                        topLeft = Offset(clampedX - 42.dp.toPx(), clampedY - 18.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(84.dp.toPx(), 36.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
                    )

                    val guidePaint = AndroidPaint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 10f * density
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = AndroidPaint.Align.CENTER
                    }
                    val dirArrow = when {
                        dPitchSun > 10f && abs(dAzSun) < 10f -> "▲ 위쪽"
                        dPitchSun < -10f && abs(dAzSun) < 10f -> "▼ 아래쪽"
                        dAzSun > 10f && dPitchSun > 10f -> "↗ 오른쪽 위"
                        dAzSun < -10f && dPitchSun > 10f -> "↖ 왼쪽 위"
                        dAzSun > 10f && dPitchSun < -10f -> "↘ 오른쪽 아래"
                        dAzSun < -10f && dPitchSun < -10f -> "↙ 왼쪽 아래"
                        dAzSun > 0f -> "오른쪽 ▶"
                        else -> "◀ 왼쪽"
                    }
                    val dist = sunAngularDist.roundToInt()
                    drawContext.canvas.nativeCanvas.drawText("☀️ $dirArrow ${dist}°", clampedX, clampedY + 4f, guidePaint)
                }
            }

            // (E) 화면 중앙 정밀 조준선 & 타깃 락온(Target Lock-on) 효과
            val crosshair = 20.dp.toPx()
            val reticleColor = if (isTargetLocked) Color(0xFF22C55E) else Color.White.copy(alpha = 0.7f)

            drawLine(color = reticleColor, start = Offset(center.x - crosshair, center.y), end = Offset(center.x - 6.dp.toPx(), center.y), strokeWidth = 2f)
            drawLine(color = reticleColor, start = Offset(center.x + 6.dp.toPx(), center.y), end = Offset(center.x + crosshair, center.y), strokeWidth = 2f)
            drawLine(color = reticleColor, start = Offset(center.x, center.y - crosshair), end = Offset(center.x, center.y - 6.dp.toPx()), strokeWidth = 2f)
            drawLine(color = reticleColor, start = Offset(center.x, center.y + 6.dp.toPx()), end = Offset(center.x, center.y + crosshair), strokeWidth = 2f)

            val ringRadius = if (isTargetLocked) 32.dp.toPx() else 24.dp.toPx()
            drawCircle(
                color = reticleColor,
                radius = ringRadius,
                center = center,
                style = if (isTargetLocked) Stroke(2.5f) else Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            )

            if (isTargetLocked) {
                val lockedTarget = if (targetMode == ArTargetMode.SUN) "☀️ 태양" else "🌙 달"
                val errDist = if (targetMode == ArTargetMode.SUN) sunAngularDist else moonAngularDist
                val lockPaint = AndroidPaint().apply {
                    color = android.graphics.Color.parseColor("#22C55E")
                    textSize = 11f * density
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = AndroidPaint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "🎯 $lockedTarget 정렬 일치 (오차 %.1f°)".format(Locale.US, errDist),
                    center.x,
                    center.y + ringRadius + 18.dp.toPx(),
                    lockPaint
                )
            }
        }

        // 3. 상단 HUD: 달/태양 전환 스위치 & 방향 상태 바
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // (A) 달 / 태양 탭 전환 세그먼트
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = NightSkyBackground.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ArTargetMode.values().forEach { mode ->
                            val isSelected = targetMode == mode
                            val bgColor = when {
                                !isSelected -> Color.Transparent
                                mode == ArTargetMode.SUN -> SunGold
                                mode == ArTargetMode.MOON -> CardBackground
                                else -> AccentBlue
                            }
                            val textColor = when {
                                !isSelected -> TextSecondary
                                mode == ArTargetMode.SUN -> NightSkyBackground
                                else -> MoonGlow
                            }

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = bgColor,
                                border = if (isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)) else null,
                                modifier = Modifier.clickable {
                                    targetMode = mode
                                    sharedState.celestialTarget = if (mode == ArTargetMode.SUN) CelestialTargetType.SUN else CelestialTargetType.MOON
                                }
                            ) {
                                Text(
                                    text = mode.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = textColor,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }

            // (B) 방향 & 천체 현황 카드
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.92f)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "🧭 조준: ${deviceAzimuth.roundToInt()}°",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "고도 ${devicePitch.roundToInt()}°",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentBlue
                            )
                        }

                        val targetInfo = if (targetMode == ArTargetMode.SUN) {
                            val stage = when {
                                sun.altitudeDeg in -4.0..2.0 -> "🌅 골든아워"
                                sun.altitudeDeg in -8.0..-4.0 -> "🌟 블루아워"
                                sun.altitudeDeg > 2.0 -> "☀️ 주간"
                                else -> "🌙 야간"
                            }
                            "☀️ 태양 방위 ${sun.azimuthDeg.roundToInt()}° · 고도 %.1f° (%s)".format(Locale.US, sun.altitudeDeg, stage)
                        } else {
                            val pct = (moon.illuminationFraction * 100.0).roundToInt()
                            "🌙 달 방위 ${moon.azimuthDeg.roundToInt()}° · 고도 %.1f° (%d%%)".format(Locale.US, moon.altitudeDeg, pct)
                        }

                        Text(
                            text = targetInfo,
                            fontSize = 11.sp,
                            color = MoonGlow
                        )
                    }

                    // 시간 프리뷰 토글 버튼
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isLiveSync) SurfaceDark else SunGold,
                        modifier = Modifier.clickable { showTimeSlider = !showTimeSlider }
                    ) {
                        Text(
                            text = if (isLiveSync) "⏱️ 실시간" else "$formattedTimeText ($relativeDayText)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLiveSync) MoonGlow else NightSkyBackground,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // (C) 다일간 날짜 네비게이션 & 시간 스크러빙 슬라이더
            AnimatedVisibility(visible = showTimeSlider) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. 플래너 연동 퀵 칩
                        sharedState.plannedInstant?.let { planned ->
                            val zdt = planned.atZone(seoulZone)
                            val planDateStr = zdt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SunGold.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, SunGold.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        previewDate = zdt.toLocalDate()
                                        val lt = zdt.toLocalTime()
                                        selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                                        isLiveSync = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📋 플래너 추천 일시 ($planDateStr) 적용",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SunGold
                                    )
                                }
                            }
                        }

                        // 2. 날짜 제어 바: [ ◀ -1일 ] [ 📅 M/d (E) ] [ +1일 ▶ ] [ 🟢 실시간 ]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SurfaceDark,
                                modifier = Modifier.clickable {
                                    previewDate = previewDate.minusDays(1)
                                    isLiveSync = false
                                }
                            ) {
                                Text(
                                    text = "◀ -1일",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF1E293B),
                                border = BorderStroke(1.dp, SunGold.copy(alpha = 0.6f)),
                                modifier = Modifier.clickable { openPreviewDatePicker() }
                            ) {
                                Text(
                                    text = "📅 $formattedPreviewDate",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SunGold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SurfaceDark,
                                modifier = Modifier.clickable {
                                    previewDate = previewDate.plusDays(1)
                                    isLiveSync = false
                                }
                            ) {
                                Text(
                                    text = "+1일 ▶",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SurfaceDark,
                                modifier = Modifier.clickable {
                                    isLiveSync = true
                                    today = LocalDate.now(seoulZone)
                                    previewDate = today
                                    val lt = LocalTime.now(seoulZone)
                                    selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                                }
                            ) {
                                Text(
                                    text = if (isLiveSync) "🟢 실시간" else "현재로 복귀",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLiveSync) Color(0xFF22C55E) else SunGold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // 3. 시간 스크러빙 슬라이더
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("시간 프리뷰:", fontSize = 11.sp, color = TextSecondary)
                                Text("$formattedTimeText ($relativeDayText)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SunGold)
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
                        }
                    }
                }
            }

            if (!hasCameraPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xEB78350F)),
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
                            "실제 카메라 뷰를 보려면 카메라 권한이 필요합니다.",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("권한 허용", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
