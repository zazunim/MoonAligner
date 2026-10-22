package com.moonaligner.app.ui.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.app.DatePickerDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.moonaligner.app.core.alignment.AlignmentFinder
import com.moonaligner.app.core.alignment.GeoPoint
import com.moonaligner.app.core.alignment.MoonAlignmentOpportunity
import com.moonaligner.app.core.astronomy.EphemerisEngine
import com.moonaligner.app.core.optics.CameraSensor
import com.moonaligner.app.core.optics.OpticsCalculator
import com.moonaligner.app.core.planner.ShotPlan
import com.moonaligner.app.core.planner.ShotPlanStorage
import com.moonaligner.app.core.shadow.ShadowSimulator
import com.moonaligner.app.ui.calculator.SubjectSilhouetteType
import com.moonaligner.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

enum class PinMoveMode {
    NONE,
    CAMERA,
    TARGET
}

enum class AlignmentAnchorMode {
    TARGET_FIXED, // 🗼 피사체 고정 -> 📷 카메라 위치 정렬
    CAMERA_FIXED  // 📷 카메라 고정 -> 🗼 피사체 위치 정렬
}

enum class CelestialTargetType(val displayName: String, val shortName: String) {
    MOON("달 (Moon)", "달"),
    SUN("태양 (Sun)", "태양")
}

/**
 * 거리/화각 탭(Calculator)과 정렬 플래너(ShotPlanner) 간 실시간 양방향 상태 공유 홀더
 */
class PlannerSharedState {
    var selectedTab by mutableIntStateOf(0)
    var selectedPreset by mutableStateOf(PRESET_SPOTS[0])
    var cameraPoint by mutableStateOf(PRESET_SPOTS[0].camera)
    var targetPoint by mutableStateOf(PRESET_SPOTS[0].target)
    var targetHeight by mutableDoubleStateOf(PRESET_SPOTS[0].targetHeightMeters)

    // 거리/화각 계산기 연동 상태
    var celestialTarget by mutableStateOf(CelestialTargetType.MOON)
    var selectedSubjectType by mutableStateOf(SubjectSilhouetteType.LOTTE_TOP)
    var subjectHeight by mutableFloatStateOf(PRESET_SPOTS[0].targetHeightMeters.toFloat())
    var scaleRatio by mutableFloatStateOf(1.0f)
    var focalLength by mutableFloatStateOf(600f)
    var selectedSensor by mutableStateOf(CameraSensor.FULL_FRAME)

    // 지도 원탭 이동 모드 및 계산기 거리 고정 기준 모드
    var pinMoveMode by mutableStateOf(PinMoveMode.CAMERA)
    var anchorMode by mutableStateOf(AlignmentAnchorMode.TARGET_FIXED)
    var isDistanceLockedToCalculator by mutableStateOf(false)
    var centerMapTrigger by mutableStateOf<GeoPoint?>(null)

    val calculatorRequiredDistance: Double
        get() = OpticsCalculator.calculateRequiredDistance(
            subjectHeightMeters = subjectHeight.toDouble(),
            scaleRatioToMoon = scaleRatio.toDouble()
        )

    /**
     * 현재 선택된 기준점 모드(피사체 고정 or 카메라 고정)에 따라 거리 자동 정렬
     */
    fun alignToCalculatorDistance() {
        when (anchorMode) {
            AlignmentAnchorMode.TARGET_FIXED -> alignCameraToCalculatorDistance()
            AlignmentAnchorMode.CAMERA_FIXED -> alignTargetToCalculatorDistance()
        }
    }

    /**
     * 🗼 피사체 위치를 고정하고, 거리/화각 탭 거리값에 맞춰 📷 카메라 위치를 자동 정렬합니다.
     */
    fun alignCameraToCalculatorDistance() {
        val reqDist = calculatorRequiredDistance
        if (reqDist <= 1.0) return
        targetHeight = subjectHeight.toDouble()

        val reverseBearing = AlignmentFinder.calculateBearingDeg(targetPoint, cameraPoint)
        val alignedCam = AlignmentFinder.calculateDestinationPoint(
            start = targetPoint,
            distanceMeters = reqDist,
            bearingDeg = reverseBearing
        )
        cameraPoint = GeoPoint(
            latitudeDeg = alignedCam.latitudeDeg,
            longitudeDeg = alignedCam.longitudeDeg,
            altitudeMeters = cameraPoint.altitudeMeters
        )
    }

    /**
     * 📷 카메라 위치를 고정하고, 거리/화각 탭 거리값에 맞춰 🗼 피사체 위치를 자동 정렬합니다.
     */
    fun alignTargetToCalculatorDistance() {
        val reqDist = calculatorRequiredDistance
        if (reqDist <= 1.0) return
        targetHeight = subjectHeight.toDouble()

        val forwardBearing = AlignmentFinder.calculateBearingDeg(cameraPoint, targetPoint)
        val alignedTarget = AlignmentFinder.calculateDestinationPoint(
            start = cameraPoint,
            distanceMeters = reqDist,
            bearingDeg = forwardBearing
        )
        targetPoint = GeoPoint(
            latitudeDeg = alignedTarget.latitudeDeg,
            longitudeDeg = alignedTarget.longitudeDeg,
            altitudeMeters = targetPoint.altitudeMeters
        )
    }

    /**
     * 🌕 피사체(🗼) 위치를 고정하고, 달(Moon) 방위각과 일직선이 되도록 카메라(📷) 위치를 자동 정렬
     * 카메라 -> 피사체 -> 달 일직선 배치 (피사체 기준 달의 반대 방향으로 카메라 배치)
     */
    fun alignCameraToMoonLine(moonAzimuthDeg: Double) {
        val currentDist = AlignmentFinder.calculateDistanceMeters(cameraPoint, targetPoint)
        val dist = if (isDistanceLockedToCalculator && calculatorRequiredDistance > 1.0) {
            calculatorRequiredDistance
        } else if (currentDist > 5.0) {
            currentDist
        } else if (calculatorRequiredDistance > 1.0) {
            calculatorRequiredDistance
        } else {
            1000.0
        }
        targetHeight = subjectHeight.toDouble()
        anchorMode = AlignmentAnchorMode.TARGET_FIXED
        pinMoveMode = PinMoveMode.CAMERA

        val normMoonAz = (moonAzimuthDeg % 360.0 + 360.0) % 360.0
        var camBearing = (normMoonAz + 180.0) % 360.0
        var alignedCam = AlignmentFinder.calculateDestinationPoint(
            start = targetPoint,
            distanceMeters = dist,
            bearingDeg = camBearing
        )

        // 미세 구면 오차 정밀 보정 (0.001도 이내 수렴)
        val err = AlignmentFinder.calculateBearingDeg(alignedCam, targetPoint) - normMoonAz
        if (kotlin.math.abs(err) > 0.001) {
            camBearing = ((camBearing - err) % 360.0 + 360.0) % 360.0
            alignedCam = AlignmentFinder.calculateDestinationPoint(
                start = targetPoint,
                distanceMeters = dist,
                bearingDeg = camBearing
            )
        }

        cameraPoint = GeoPoint(
            latitudeDeg = alignedCam.latitudeDeg,
            longitudeDeg = alignedCam.longitudeDeg,
            altitudeMeters = cameraPoint.altitudeMeters
        )
    }

    /**
     * 🌕 카메라(📷) 위치를 고정하고, 달(Moon) 방위각과 일직선이 되도록 피사체(🗼) 위치를 자동 정렬
     * 카메라 -> 피사체 -> 달 일직선 배치 (카메라 기준 달의 방향으로 피사체 배치)
     */
    fun alignTargetToMoonLine(moonAzimuthDeg: Double) {
        val currentDist = AlignmentFinder.calculateDistanceMeters(cameraPoint, targetPoint)
        val dist = if (isDistanceLockedToCalculator && calculatorRequiredDistance > 1.0) {
            calculatorRequiredDistance
        } else if (currentDist > 5.0) {
            currentDist
        } else if (calculatorRequiredDistance > 1.0) {
            calculatorRequiredDistance
        } else {
            1000.0
        }
        targetHeight = subjectHeight.toDouble()
        anchorMode = AlignmentAnchorMode.CAMERA_FIXED
        pinMoveMode = PinMoveMode.TARGET

        val normMoonAz = (moonAzimuthDeg % 360.0 + 360.0) % 360.0
        val alignedTarget = AlignmentFinder.calculateDestinationPoint(
            start = cameraPoint,
            distanceMeters = dist,
            bearingDeg = normMoonAz
        )

        targetPoint = GeoPoint(
            latitudeDeg = alignedTarget.latitudeDeg,
            longitudeDeg = alignedTarget.longitudeDeg,
            altitudeMeters = targetPoint.altitudeMeters
        )
    }

    /**
     * 🌕 현재 선택된 기준점 모드(피사체 고정 or 카메라 고정)에 맞춰 달과 일직선 자동 정렬
     */
    fun alignToMoonLine(moonAzimuthDeg: Double) {
        when (anchorMode) {
            AlignmentAnchorMode.TARGET_FIXED -> alignCameraToMoonLine(moonAzimuthDeg)
            AlignmentAnchorMode.CAMERA_FIXED -> alignTargetToMoonLine(moonAzimuthDeg)
        }
    }
}

data class PresetSpot(
    val name: String,
    val camera: GeoPoint,
    val target: GeoPoint,
    val targetHeightMeters: Double
)

val PRESET_SPOTS = listOf(
    PresetSpot(
        name = "노들섬 ➔ N서울타워",
        camera = GeoPoint(37.5172, 126.9582, altitudeMeters = 15.0),
        target = GeoPoint(37.5512, 126.9882, altitudeMeters = 445.0),
        targetHeightMeters = 35.0
    ),
    PresetSpot(
        name = "선유도공원 ➔ 국회의사당 돔",
        camera = GeoPoint(37.5425, 126.9015, altitudeMeters = 16.0),
        target = GeoPoint(37.5318, 126.9142, altitudeMeters = 20.0),
        targetHeightMeters = 30.0
    ),
    PresetSpot(
        name = "올림픽공원 ➔ 롯데월드타워",
        camera = GeoPoint(37.5208, 127.1215, altitudeMeters = 30.0),
        target = GeoPoint(37.5126, 127.1025, altitudeMeters = 535.0),
        targetHeightMeters = 45.0
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotPlannerScreen(sharedState: PlannerSharedState) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val seoulZone = ZoneId.of("Asia/Seoul")
    var today by remember { mutableStateOf(LocalDate.now(seoulZone)) }

    // osmdroid 캐시 및 설정 초기화
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // GPS 위치 권한 및 실시간 좌표 상태
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var userHeadingDeg by remember { mutableStateOf<Double?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // 실시간 스마트폰 지향 각도 센서 (나침반 / 회전 벡터)
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val sensorListener = object : SensorEventListener {
            val rotationMatrix = FloatArray(9)
            val orientationAngles = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuthRad = orientationAngles[0]
                    var deg = Math.toDegrees(azimuthRad.toDouble())
                    if (deg < 0) deg += 360.0
                    userHeadingDeg = deg
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager?.unregisterListener(sensorListener)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasLocationPermission = isGranted
    }

    DisposableEffect(hasLocationPermission) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                userLocation = GeoPoint(location.latitude, location.longitude, location.altitude)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (hasLocationPermission && locationManager != null) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, locationListener)
                    val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (lastGps != null) {
                        userLocation = GeoPoint(lastGps.latitude, lastGps.longitude, lastGps.altitude)
                    }
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, locationListener)
                    val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (userLocation == null && lastNet != null) {
                        userLocation = GeoPoint(lastNet.latitude, lastNet.longitude, lastNet.altitude)
                    }
                }
            } catch (_: SecurityException) {}
        }

        onDispose {
            try {
                locationManager?.removeUpdates(locationListener)
            } catch (_: SecurityException) {}
        }
    }

    val cameraPoint = sharedState.cameraPoint
    val targetPoint = sharedState.targetPoint
    val targetHeight = sharedState.targetHeight

    // 지도 중심 이동 트리거 처리
    LaunchedEffect(sharedState.centerMapTrigger, mapViewRef) {
        val trigger = sharedState.centerMapTrigger
        val map = mapViewRef
        if (trigger != null && map != null) {
            map.controller.animateTo(OsmGeoPoint(trigger.latitudeDeg, trigger.longitudeDeg))
            sharedState.centerMapTrigger = null
        }
    }

    // 시각 슬라이더 (0.0 ~ 24.0h, 기본값: 실시간 현재 시각 동기화)
    val nowTime = remember { LocalTime.now(seoulZone) }
    var selectedHourFloat by remember { mutableFloatStateOf(nowTime.hour + nowTime.minute / 60f + nowTime.second / 3600f) }
    var isLiveSync by remember { mutableStateOf(true) }
    var selectedMarkerMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isLiveSync) {
        while (isLiveSync) {
            val zdt = ZonedDateTime.now(seoulZone)
            today = zdt.toLocalDate()
            val lt = zdt.toLocalTime()
            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
            delay(1000L)
        }
    }

    val totalSeconds = (selectedHourFloat * 3600f).toInt().coerceIn(0, 86399)
    val selectedHour = totalSeconds / 3600
    val selectedMinute = (totalSeconds % 3600) / 60
    val selectedSecond = totalSeconds % 60
    val selectedInstant = remember(today, selectedHour, selectedMinute, selectedSecond) {
        ZonedDateTime.of(today, LocalTime.of(selectedHour, selectedMinute, selectedSecond), seoulZone).toInstant()
    }

    // 천체 위치 (카메라 지점 기준)
    val moon = remember(selectedInstant, cameraPoint) {
        EphemerisEngine.calculateMoon(selectedInstant, cameraPoint.latitudeDeg, cameraPoint.longitudeDeg)
    }
    val sun = remember(selectedInstant, cameraPoint) {
        EphemerisEngine.calculateSun(selectedInstant, cameraPoint.latitudeDeg, cameraPoint.longitudeDeg)
    }
    val sunDayTimes = remember(today, cameraPoint) {
        EphemerisEngine.calculateSunDayTimes(today, seoulZone, cameraPoint.latitudeDeg, cameraPoint.longitudeDeg)
    }

    // SunOnTrack 스타일 그림자 시뮬레이션 결과
    val shadowResult = remember(sun, targetHeight, targetPoint) {
        ShadowSimulator.calculateShadow(sun.altitudeDeg, sun.azimuthDeg, targetHeight, targetPoint)
    }

    val showShadow = true
    val showSunRays = true
    val isSunTarget = sharedState.celestialTarget == CelestialTargetType.SUN
    val targetAzimuth = if (isSunTarget) sun.azimuthDeg else moon.azimuthDeg
    val targetAltitude = if (isSunTarget) sun.altitudeDeg else moon.altitudeDeg

    // 기하학 계산값 (거리, 방위각)
    val distance = remember(cameraPoint, targetPoint) {
        AlignmentFinder.calculateDistanceMeters(cameraPoint, targetPoint)
    }
    val bearing = remember(cameraPoint, targetPoint) {
        AlignmentFinder.calculateBearingDeg(cameraPoint, targetPoint)
    }

    // 천체(달 or 태양) 방향과 조준선의 일치 여부
    val azDiff = remember(targetAzimuth, bearing) {
        val diff = abs(targetAzimuth - bearing)
        min(diff, 360.0 - diff)
    }
    val isAligned = azDiff < 1.0 && targetAltitude > -2.0

    val alignCameraToColinear: () -> Unit = {
        sharedState.alignCameraToMoonLine(targetAzimuth)
        val name = if (isSunTarget) "☀️태양" else "${moon.phase.emoji}달"
        selectedMarkerMessage = "🎯 3점 1열 카메라 정렬 완료! [📷카메라 ➔ 🗼피사체 ➔ $name]"
    }

    val alignTimeToCelestialTarget: () -> Unit = {
        val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
        val match = AlignmentFinder.findNextAzimuthAlignment(
            camera = cameraPoint,
            target = targetPoint,
            startInstant = selectedInstant,
            isSun = isSun
        )
        val targetName = if (isSun) "☀️태양" else "${moon.phase.emoji}달"
        if (match != null) {
            val zdt = match.timestamp.atZone(seoulZone)
            today = zdt.toLocalDate()
            val lt = zdt.toLocalTime()
            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
            isLiveSync = false
            selectedMarkerMessage = "🎯 3점 1열 [$targetName] 정렬 시각으로 이동! (각도차 ${String.format("%.2f", match.offsetDeg)}°)"
        } else {
            selectedMarkerMessage = "⚠️ 향후 48시간 내에 일직선으로 정렬되는 $targetName 궤적이 없습니다."
        }
    }

    // 정렬 최적 일시 검색 상태 (기간 및 달 위상 필터)
    var isSearching by remember { mutableStateOf(false) }
    var searchDays by remember { mutableStateOf(7) }
    var selectedPhaseFilter by remember { mutableStateOf(AlignmentFinder.MoonPhaseFilter.ALL) }
    var bestAlignments by remember { mutableStateOf<List<AlignmentFinder.AlignmentEvent>>(emptyList()) }
    var showTimelineSheet by remember { mutableStateOf(false) }
    var showOpportunitySheet by remember { mutableStateOf(false) }

    // 촬영 계획 저장 및 불러오기 상태
    var showSavePlanDialog by remember { mutableStateOf(false) }
    var showSavedPlansSheet by remember { mutableStateOf(false) }
    var planNameInput by remember { mutableStateOf("") }
    var savedPlans by remember { mutableStateOf(ShotPlanStorage.loadPlans(context)) }
    var activePlanName by remember { mutableStateOf<String?>(null) }

    fun runSearch(days: Int = searchDays, filter: AlignmentFinder.MoonPhaseFilter = selectedPhaseFilter) {
        searchDays = days
        selectedPhaseFilter = filter
        isSearching = true
        coroutineScope.launch {
            val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
            val results = withContext(Dispatchers.Default) {
                if (isSun) {
                    AlignmentFinder.findSunAlignments(
                        camera = cameraPoint,
                        target = targetPoint,
                        targetHeightMeters = targetHeight,
                        startInstant = selectedInstant,
                        duration = Duration.ofDays(days.toLong()),
                        toleranceDeg = 0.5
                    )
                } else {
                    AlignmentFinder.findMoonAlignments(
                        camera = cameraPoint,
                        target = targetPoint,
                        targetHeightMeters = targetHeight,
                        startInstant = selectedInstant,
                        duration = Duration.ofDays(days.toLong()),
                        toleranceDeg = 0.4,
                        phaseFilter = filter
                    )
                }
            }
            bestAlignments = results
            isSearching = false
            showTimelineSheet = true
        }
    }

    val openDatePicker = {
        val dpd = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                today = LocalDate.of(year, month + 1, dayOfMonth)
                isLiveSync = false
                selectedMarkerMessage = "📅 날짜 변경: ${today.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"))}"
            },
            today.year,
            today.monthValue - 1,
            today.dayOfMonth
        )
        dpd.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightSkyBackground)
    ) {
        // 1. 오픈스트리트맵 인터랙티브 지도 화면 (원탭 핀 선택 & 원탭 이동 지원)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(14.0)
                        controller.setCenter(OsmGeoPoint(cameraPoint.latitudeDeg, cameraPoint.longitudeDeg))
                        mapViewRef = this
                    }
                },
                update = { mapView ->
                    val renderKey = listOf(
                        cameraPoint,
                        targetPoint,
                        sharedState.pinMoveMode,
                        sharedState.anchorMode,
                        sharedState.isDistanceLockedToCalculator,
                        userLocation,
                        userHeadingDeg?.roundToInt(),
                        sharedState.celestialTarget,
                        targetHeight.roundToInt(),
                        selectedHour,
                        selectedMinute,
                        selectedSecond
                    )
                    if (mapView.tag == renderKey) {
                        return@AndroidView
                    }
                    mapView.tag = renderKey
                    mapView.overlays.clear()

                    // (0) 지도 원탭 이벤트 리시버: 선택된 아이콘(📷 또는 🗼)을 터치한 지점으로 즉시 이동!
                    val tapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                            if (p == null) return false
                            val tappedGeo = GeoPoint(p.latitude, p.longitude)

                            // 일출, 일몰, 태양, 달 아이콘 주변 터치 시에는 카메라/피사체를 이동시키지 않음!
                            val moonTipPt = AlignmentFinder.calculateDestinationPoint(cameraPoint, 5500.0, moon.azimuthDeg)
                            val sunTipPt = AlignmentFinder.calculateDestinationPoint(targetPoint, 5000.0, sun.azimuthDeg)
                            val riseTipPt = AlignmentFinder.calculateDestinationPoint(targetPoint, 4200.0, sunDayTimes.sunriseAzimuthDeg)
                            val setTipPt = AlignmentFinder.calculateDestinationPoint(targetPoint, 4200.0, sunDayTimes.sunsetAzimuthDeg)
                            val celestialTips = listOf(moonTipPt, sunTipPt, riseTipPt, setTipPt)
                            if (celestialTips.any { AlignmentFinder.calculateDistanceMeters(tappedGeo, it) < 350.0 }) {
                                return false
                            }

                            val reqDist = sharedState.calculatorRequiredDistance

                            when (sharedState.pinMoveMode) {
                                PinMoveMode.CAMERA -> {
                                    if (sharedState.isDistanceLockedToCalculator && reqDist > 1.0) {
                                        // 🗼 피사체 고정 -> 터치한 방향으로 📷 카메라를 계산기 거리만큼 배치
                                        val dirBearing = AlignmentFinder.calculateBearingDeg(sharedState.targetPoint, tappedGeo)
                                        val lockedCam = AlignmentFinder.calculateDestinationPoint(
                                            start = sharedState.targetPoint,
                                            distanceMeters = reqDist,
                                            bearingDeg = dirBearing
                                        )
                                        sharedState.cameraPoint = GeoPoint(
                                            lockedCam.latitudeDeg,
                                            lockedCam.longitudeDeg,
                                            sharedState.cameraPoint.altitudeMeters
                                        )
                                    } else {
                                        sharedState.cameraPoint = GeoPoint(
                                            p.latitude,
                                            p.longitude,
                                            sharedState.cameraPoint.altitudeMeters
                                        )
                                    }
                                    return true
                                }
                                PinMoveMode.TARGET -> {
                                    if (sharedState.isDistanceLockedToCalculator && reqDist > 1.0) {
                                        // 📷 카메라 고정 -> 터치한 방향으로 🗼 피사체를 계산기 거리만큼 배치!
                                        val dirBearing = AlignmentFinder.calculateBearingDeg(sharedState.cameraPoint, tappedGeo)
                                        val lockedTarget = AlignmentFinder.calculateDestinationPoint(
                                            start = sharedState.cameraPoint,
                                            distanceMeters = reqDist,
                                            bearingDeg = dirBearing
                                        )
                                        sharedState.targetPoint = GeoPoint(
                                            lockedTarget.latitudeDeg,
                                            lockedTarget.longitudeDeg,
                                            sharedState.targetPoint.altitudeMeters
                                        )
                                    } else {
                                        sharedState.targetPoint = GeoPoint(
                                            p.latitude,
                                            p.longitude,
                                            sharedState.targetPoint.altitudeMeters
                                        )
                                    }
                                    return true
                                }
                                PinMoveMode.NONE -> return false
                            }
                        }

                        override fun longPressHelper(p: OsmGeoPoint?): Boolean = false
                    })
                    mapView.overlays.add(tapEventsOverlay)

                    val camOsm = OsmGeoPoint(cameraPoint.latitudeDeg, cameraPoint.longitudeDeg)
                    val targetOsm = OsmGeoPoint(targetPoint.latitudeDeg, targetPoint.longitudeDeg)

                    // (0-1) 촬영 거리 반경 원 (기준점에 따라 피사체 중심 또는 카메라 중심)
                    val circleCenter = if (sharedState.anchorMode == AlignmentAnchorMode.CAMERA_FIXED) cameraPoint else targetPoint
                    val circlePoints = (0..72).map { idx ->
                        val deg = idx * 5.0
                        val pt = AlignmentFinder.calculateDestinationPoint(circleCenter, distance.coerceAtLeast(10.0), deg)
                        OsmGeoPoint(pt.latitudeDeg, pt.longitudeDeg)
                    }
                    val radiusCircleLine = Polyline().apply {
                        setPoints(circlePoints)
                        outlinePaint.color = AndroidColor.parseColor("#AA38BDF8")
                        outlinePaint.strokeWidth = 4f
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
                        title = "현재 촬영 거리 반경: ${String.format("%.1f", distance)}m"
                    }
                    mapView.overlays.add(radiusCircleLine)

                    // (A) 피사체 조준선 (카메라 ➔ 피사체 연결 굵은 오렌지 라인)
                    val targetLine = Polyline().apply {
                        setPoints(listOf(camOsm, targetOsm))
                        outlinePaint.color = AndroidColor.parseColor("#FF5722")
                        outlinePaint.strokeWidth = 7f
                        title = "조준선: ${String.format("%.1f", distance)}m (${String.format("%.1f", bearing)}°)"
                    }
                    mapView.overlays.add(targetLine)

                    // 거리 & 방위각 중간 라벨 뱃지 마커
                    val midLat = (cameraPoint.latitudeDeg + targetPoint.latitudeDeg) / 2.0
                    val midLon = (cameraPoint.longitudeDeg + targetPoint.longitudeDeg) / 2.0
                    val distStr = if (distance >= 1000) String.format("%.2f km", distance / 1000.0) else "${distance.roundToInt()} m"
                    val midBadge = Marker(mapView).apply {
                        position = OsmGeoPoint(midLat, midLon)
                        icon = createTextBadgeIcon(
                            context,
                            "📏 $distStr (${String.format("%.1f", bearing)}°)",
                            bgColor = AndroidColor.parseColor("#E60F172A"),
                            textColor = AndroidColor.parseColor("#38BDF8")
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> false }
                    }
                    mapView.overlays.add(midBadge)

                    val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
                    val rayDist = kotlin.math.max(distance * 1.6, distance + 3500.0).coerceAtLeast(6000.0)

                    if (isSun) {
                        // ☀️ 태양 플래너 모드: 카메라 ➔ 피사체 ➔ 태양 관통선
                        val sunTip = AlignmentFinder.calculateDestinationPoint(cameraPoint, rayDist, sun.azimuthDeg)
                        val sunEndOsm = OsmGeoPoint(sunTip.latitudeDeg, sunTip.longitudeDeg)
                        val sunRay = Polyline().apply {
                            setPoints(listOf(camOsm, sunEndOsm))
                            outlinePaint.color = if (isAligned) AndroidColor.parseColor("#F59E0B") else AndroidColor.parseColor("#FBBF24")
                            outlinePaint.strokeWidth = if (isAligned) 8f else 5f
                        }
                        mapView.overlays.add(sunRay)

                        val sunMarker = Marker(mapView).apply {
                            position = sunEndOsm
                            icon = createCircularMarkerIcon(
                                context = context,
                                symbol = "☀️",
                                bgColor = AndroidColor.parseColor("#D97706"),
                                sizeDp = 44,
                                borderColor = AndroidColor.parseColor("#FEF08A")
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "☀️ 현재 태양"
                            snippet = "고도: ${String.format("%.1f", sun.altitudeDeg)}° | 방위각: ${sun.azimuthDeg.roundToInt()}°"
                            setOnMarkerClickListener { marker, _ ->
                                marker.showInfoWindow()
                                selectedMarkerMessage = "${marker.title} • ${marker.snippet}"
                                true
                            }
                        }
                        mapView.overlays.add(sunMarker)

                        val sunMidLat = cameraPoint.latitudeDeg + (sunTip.latitudeDeg - cameraPoint.latitudeDeg) * 0.45
                        val sunMidLon = cameraPoint.longitudeDeg + (sunTip.longitudeDeg - cameraPoint.longitudeDeg) * 0.45
                        val sunLabel = Marker(mapView).apply {
                            position = OsmGeoPoint(sunMidLat, sunMidLon)
                            icon = createTextBadgeIcon(
                                context,
                                if (isAligned) "🎯 3점 1열 정렬 [📷➔🗼➔☀️] (${String.format("%.0f", sun.azimuthDeg)}°)"
                                else "☀️ 태양 궤적 (${String.format("%.0f", sun.azimuthDeg)}° / ${String.format("%.1f", sun.altitudeDeg)}°)",
                                bgColor = if (isAligned) AndroidColor.parseColor("#E69A3412") else AndroidColor.parseColor("#E6451A03"),
                                textColor = AndroidColor.parseColor("#FEF08A")
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ -> false }
                        }
                        mapView.overlays.add(sunLabel)

                        // 보조: 달 광선
                        val moonTip = AlignmentFinder.calculateDestinationPoint(cameraPoint, rayDist, moon.azimuthDeg)
                        val moonEndOsm = OsmGeoPoint(moonTip.latitudeDeg, moonTip.longitudeDeg)
                        val moonRay = Polyline().apply {
                            setPoints(listOf(camOsm, moonEndOsm))
                            outlinePaint.color = AndroidColor.parseColor("#AAFEF08A")
                            outlinePaint.strokeWidth = 3.5f
                        }
                        mapView.overlays.add(moonRay)

                        val moonMarker = Marker(mapView).apply {
                            position = moonEndOsm
                            icon = createCircularMarkerIcon(
                                context = context,
                                symbol = moon.phase.emoji,
                                bgColor = AndroidColor.parseColor("#1E1B4B"),
                                sizeDp = 40,
                                borderColor = AndroidColor.parseColor("#FDE047")
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "${moon.phase.emoji} 현재 달 (${moon.phase.displayName})"
                            snippet = "고도: ${String.format("%.1f", moon.altitudeDeg)}° | 방위각: ${moon.azimuthDeg.roundToInt()}°"
                            setOnMarkerClickListener { marker, _ ->
                                marker.showInfoWindow()
                                selectedMarkerMessage = "${marker.title} • ${marker.snippet}"
                                true
                            }
                        }
                        mapView.overlays.add(moonMarker)
                    } else {
                        // 🌕 달 플래너 모드: 카메라 ➔ 피사체 ➔ 달 관통선
                        val moonTip = AlignmentFinder.calculateDestinationPoint(cameraPoint, rayDist, moon.azimuthDeg)
                        val moonEndOsm = OsmGeoPoint(moonTip.latitudeDeg, moonTip.longitudeDeg)
                        val moonRay = Polyline().apply {
                            setPoints(listOf(camOsm, moonEndOsm))
                            outlinePaint.color = if (isAligned) AndroidColor.parseColor("#FACC15") else AndroidColor.parseColor("#FDE047")
                            outlinePaint.strokeWidth = if (isAligned) 8f else 5f
                        }
                        mapView.overlays.add(moonRay)

                        val moonMarker = Marker(mapView).apply {
                            position = moonEndOsm
                            icon = createCircularMarkerIcon(
                                context = context,
                                symbol = moon.phase.emoji,
                                bgColor = AndroidColor.parseColor("#1E1B4B"),
                                sizeDp = 44,
                                borderColor = AndroidColor.parseColor("#FDE047")
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "${moon.phase.emoji} 현재 달 (${moon.phase.displayName})"
                            snippet = "고도: ${String.format("%.1f", moon.altitudeDeg)}° | 방위각: ${moon.azimuthDeg.roundToInt()}°"
                            setOnMarkerClickListener { marker, _ ->
                                marker.showInfoWindow()
                                selectedMarkerMessage = "${marker.title} • ${marker.snippet}"
                                true
                            }
                        }
                        mapView.overlays.add(moonMarker)

                        val moonMidLat = cameraPoint.latitudeDeg + (moonTip.latitudeDeg - cameraPoint.latitudeDeg) * 0.45
                        val moonMidLon = cameraPoint.longitudeDeg + (moonTip.longitudeDeg - cameraPoint.longitudeDeg) * 0.45
                        val moonLabel = Marker(mapView).apply {
                            position = OsmGeoPoint(moonMidLat, moonMidLon)
                            icon = createTextBadgeIcon(
                                context,
                                if (isAligned) "🎯 3점 1열 정렬 [📷➔🗼➔${moon.phase.emoji}] (${String.format("%.0f", moon.azimuthDeg)}°)"
                                else "${moon.phase.emoji} 달 궤적 (${String.format("%.0f", moon.azimuthDeg)}° / ${String.format("%.1f", moon.altitudeDeg)}°)",
                                bgColor = if (isAligned) AndroidColor.parseColor("#E614532D") else AndroidColor.parseColor("#E61E1B4B"),
                                textColor = AndroidColor.parseColor("#FEF08A")
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ -> false }
                        }
                        mapView.overlays.add(moonLabel)

                        // 보조: 태양 광선 (피사체 기준)
                        if (showSunRays) {
                            val sunTip = AlignmentFinder.calculateDestinationPoint(targetPoint, 5000.0, sun.azimuthDeg)
                            val sunEndOsm = OsmGeoPoint(sunTip.latitudeDeg, sunTip.longitudeDeg)
                            val sunRay = Polyline().apply {
                                setPoints(listOf(targetOsm, sunEndOsm))
                                outlinePaint.color = AndroidColor.parseColor("#F59E0B")
                                outlinePaint.strokeWidth = 4f
                            }
                            mapView.overlays.add(sunRay)

                            val sunMarker = Marker(mapView).apply {
                                position = sunEndOsm
                                icon = createCircularMarkerIcon(
                                    context = context,
                                    symbol = "☀️",
                                    bgColor = AndroidColor.parseColor("#D97706"),
                                    sizeDp = 44,
                                    borderColor = AndroidColor.parseColor("#FEF08A")
                                )
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "☀️ 현재 태양"
                                snippet = "고도: ${String.format("%.1f", sun.altitudeDeg)}° | 방위각: ${sun.azimuthDeg.roundToInt()}°"
                                setOnMarkerClickListener { marker, _ ->
                                    marker.showInfoWindow()
                                    selectedMarkerMessage = "${marker.title} • ${marker.snippet}"
                                    true
                                }
                            }
                            mapView.overlays.add(sunMarker)
                        }
                    }

                    // (C) 일출/일몰 광선 (항상 피사체 기준 표시)
                    val riseTip = AlignmentFinder.calculateDestinationPoint(targetPoint, 4200.0, sunDayTimes.sunriseAzimuthDeg)
                    val riseEndOsm = OsmGeoPoint(riseTip.latitudeDeg, riseTip.longitudeDeg)
                    val riseRay = Polyline().apply {
                        setPoints(listOf(targetOsm, riseEndOsm))
                        outlinePaint.color = AndroidColor.parseColor("#FF7043")
                        outlinePaint.strokeWidth = 3f
                    }
                    mapView.overlays.add(riseRay)

                    val riseMarker = Marker(mapView).apply {
                        position = riseEndOsm
                        icon = createCircularMarkerIcon(context, "🌅", AndroidColor.parseColor("#EA580C"), sizeDp = 36)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "🌅 오늘 일출 시각: ${sunDayTimes.sunriseTimeStr}"
                        snippet = "일출 방위각: ${sunDayTimes.sunriseAzimuthDeg.roundToInt()}°"
                        setOnMarkerClickListener { marker, _ ->
                            marker.showInfoWindow()
                            selectedMarkerMessage = "${marker.title} • ${marker.snippet}"
                            true
                        }
                    }
                    mapView.overlays.add(riseMarker)

                    val setTip = AlignmentFinder.calculateDestinationPoint(targetPoint, 4200.0, sunDayTimes.sunsetAzimuthDeg)
                    val setEndOsm = OsmGeoPoint(setTip.latitudeDeg, setTip.longitudeDeg)
                    val setRay = Polyline().apply {
                        setPoints(listOf(targetOsm, setEndOsm))
                        outlinePaint.color = AndroidColor.parseColor("#AB47BC")
                        outlinePaint.strokeWidth = 3f
                    }
                    mapView.overlays.add(setRay)

                    val setMarker = Marker(mapView).apply {
                        position = setEndOsm
                        icon = createCircularMarkerIcon(context, "🌇", AndroidColor.parseColor("#9333EA"), sizeDp = 36)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "🌇 오늘 일몰 시각: ${sunDayTimes.sunsetTimeStr}"
                        snippet = "일몰 방위각: ${sunDayTimes.sunsetAzimuthDeg.roundToInt()}°"
                        setOnMarkerClickListener { marker, _ ->
                            marker.showInfoWindow()
                            selectedMarkerMessage = "${marker.title} • ${marker.snippet}"
                            true
                        }
                    }
                    mapView.overlays.add(setMarker)

                    // (D) SunOnTrack 실시간 그림자 투영
                    if (showShadow && shadowResult.isSunAboveHorizon && shadowResult.shadowTipPoint != null) {
                        val shadowTipOsm = OsmGeoPoint(shadowResult.shadowTipPoint.latitudeDeg, shadowResult.shadowTipPoint.longitudeDeg)
                        val shadowLine = Polyline().apply {
                            setPoints(listOf(targetOsm, shadowTipOsm))
                            outlinePaint.color = AndroidColor.parseColor("#AA0F172A")
                            outlinePaint.strokeWidth = 18f
                        }
                        mapView.overlays.add(shadowLine)

                        val shadowTipMarker = Marker(mapView).apply {
                            position = shadowTipOsm
                            icon = createCircularMarkerIcon(
                                context = context,
                                symbol = "👥",
                                bgColor = AndroidColor.parseColor("#334155"),
                                sizeDp = 34,
                                borderColor = AndroidColor.parseColor("#94A3B8")
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ -> false }
                        }
                        mapView.overlays.add(shadowTipMarker)
                    }

                    // (E) 📷 카메라 핀 (한 번 터치하면 선택됨 -> 황금 네온 링 표시!)
                    val isCamSelected = sharedState.pinMoveMode == PinMoveMode.CAMERA
                    val camMarker = Marker(mapView).apply {
                        position = camOsm
                        icon = createCircularMarkerIcon(
                            context = context,
                            symbol = "📷",
                            bgColor = AndroidColor.parseColor("#0284C7"),
                            sizeDp = 48,
                            borderColor = if (isCamSelected) AndroidColor.parseColor("#FDE047") else AndroidColor.WHITE,
                            isSelected = isCamSelected
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "📷 카메라"
                        setOnMarkerClickListener { _, _ ->
                            sharedState.pinMoveMode = PinMoveMode.CAMERA
                            true
                        }
                    }
                    mapView.overlays.add(camMarker)

                    // (F) 🗼 피사체 핀 (한 번 터치하면 선택됨 -> 황금 네온 링 표시!)
                    val isTargetSelected = sharedState.pinMoveMode == PinMoveMode.TARGET
                    val targetMarker = Marker(mapView).apply {
                        position = targetOsm
                        icon = createCircularMarkerIcon(
                            context = context,
                            symbol = "🗼",
                            bgColor = AndroidColor.parseColor("#E11D48"),
                            sizeDp = 48,
                            borderColor = if (isTargetSelected) AndroidColor.parseColor("#FDE047") else AndroidColor.WHITE,
                            isSelected = isTargetSelected
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "🗼 피사체"
                        setOnMarkerClickListener { _, _ ->
                            sharedState.pinMoveMode = PinMoveMode.TARGET
                            true
                        }
                    }
                    mapView.overlays.add(targetMarker)

                    // (G) 📍 실시간 내 GPS 위치 마커 & 현장 삼각대 위치 유도선
                    // (G) 📍 실시간 내 GPS 위치 마커 (나침반 지향 콘 포함) & 현장 삼각대 위치 유도선
                    userLocation?.let { uLoc ->
                        val userOsm = OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg)
                        val headingStr = if (userHeadingDeg != null) " (${userHeadingDeg!!.roundToInt()}°)" else ""
                        val userMarker = Marker(mapView).apply {
                            position = userOsm
                            icon = createUserPuckMarkerIcon(context, userHeadingDeg)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "내 현재 위치$headingStr"
                            setOnMarkerClickListener { _, _ -> false }
                        }
                        mapView.overlays.add(userMarker)

                        // 현장 삼각대 위치 유도선 (내 위치 ➔ 계획된 카메라 핀 연결)
                        val distToCam = AlignmentFinder.calculateDistanceMeters(uLoc, cameraPoint)
                        if (distToCam > 3.0) {
                            val guideLine = Polyline().apply {
                                setPoints(listOf(userOsm, camOsm))
                                outlinePaint.color = AndroidColor.parseColor("#38BDF8")
                                outlinePaint.strokeWidth = 4f
                                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
                                title = "삼각대 유도선"
                            }
                            mapView.overlays.add(guideLine)
                        }
                    }

                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            // 상단: 미니멀 글래스 컨트롤 바 & 상태 배너
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 🌟 최상단 중앙: 큼직하고 직관적인 [ 🌕 달 플래너 | ☀️ 태양 플래너 ] 세그먼트 스위치
                Row(
                    modifier = Modifier
                        .background(Color(0xF00F172A), RoundedCornerShape(18.dp))
                        .border(1.dp, SunGold.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isMoon = sharedState.celestialTarget == CelestialTargetType.MOON
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isMoon) SunGold else Color.Transparent)
                            .clickable { sharedState.celestialTarget = CelestialTargetType.MOON }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${moon.phase.emoji} 달 플래너",
                            fontSize = 12.sp,
                            fontWeight = if (isMoon) FontWeight.Bold else FontWeight.Medium,
                            color = if (isMoon) NightSkyBackground else TextSecondary
                        )
                    }

                    val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSun) Color(0xFFF59E0B) else Color.Transparent)
                            .clickable { sharedState.celestialTarget = CelestialTargetType.SUN }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "☀️ 태양 플래너",
                            fontSize = 12.sp,
                            fontWeight = if (isSun) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSun) NightSkyBackground else TextSecondary
                        )
                    }
                }

                // 상단 툴바: [ 좌측 핀 모드 세그먼트 | 우측 빠른 액션 아이콘들 ]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1) 핀 이동 모드 스위치 캡슐 [ 📷 카메라 | 🗼 피사체 | 🔓 ]
                    Row(
                        modifier = Modifier
                            .background(Color(0xE00F172A), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isCam = sharedState.pinMoveMode == PinMoveMode.CAMERA
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCam) Color(0xFF0284C7) else Color.Transparent)
                                .clickable {
                                    sharedState.pinMoveMode = PinMoveMode.CAMERA
                                    sharedState.anchorMode = AlignmentAnchorMode.TARGET_FIXED
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text("📷", fontSize = 10.sp)
                                Text(
                                    "카메라",
                                    fontSize = 11.sp,
                                    fontWeight = if (isCam) FontWeight.Bold else FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }

                        val isTarget = sharedState.pinMoveMode == PinMoveMode.TARGET
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isTarget) Color(0xFFE11D48) else Color.Transparent)
                                .clickable {
                                    sharedState.pinMoveMode = PinMoveMode.TARGET
                                    sharedState.anchorMode = AlignmentAnchorMode.CAMERA_FIXED
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text("🗼", fontSize = 10.sp)
                                Text(
                                    "피사체",
                                    fontSize = 11.sp,
                                    fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }

                        val isLocked = sharedState.pinMoveMode == PinMoveMode.NONE
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isLocked) SunGold else Color.Transparent)
                                .clickable { sharedState.pinMoveMode = PinMoveMode.NONE }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (isLocked) "🔒" else "🔓",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // 2) 우측 빠른 액션 원형 아이콘들 (GPS / 카메라설정 / 저장 / 목록)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xE00F172A))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                                .clickable {
                                    if (!hasLocationPermission) {
                                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        userLocation?.let { uLoc ->
                                            mapViewRef?.controller?.animateTo(OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg))
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = if (userLocation != null) "📍" else "🛰️", fontSize = 12.sp)
                        }

                        if (userLocation != null) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xE00F172A))
                                    .border(1.dp, SunGold.copy(alpha = 0.6f), CircleShape)
                                    .clickable {
                                        userLocation?.let { uLoc ->
                                            sharedState.cameraPoint = uLoc
                                            mapViewRef?.controller?.animateTo(OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg))
                                            selectedMarkerMessage = "📷 내 현재 GPS 위치로 카메라 삼각대 좌표가 설정되었습니다."
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📸", fontSize = 12.sp)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xE00F172A))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                                .clickable {
                                    val timeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
                                    val targetName = sharedState.selectedSubjectType.displayName
                                    planNameInput = "$targetName 정렬 (${selectedInstant.atZone(seoulZone).format(timeFormatter)})"
                                    showSavePlanDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💾", fontSize = 12.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xE00F172A))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable {
                                savedPlans = ShotPlanStorage.loadPlans(context)
                                showSavedPlansSheet = true
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Text("📁", fontSize = 12.sp)
                                if (savedPlans.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(SunGold)
                                    )
                                }
                            }
                        }
                    }
                }

                // 활성 계획 배너 (불러온 계획이 있을 때만 미니멀 표시)
                activePlanName?.let { pName ->
                    Row(
                        modifier = Modifier
                            .background(Color(0xE6047857), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("🔖", fontSize = 10.sp)
                        Text(pName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        IconButton(
                            onClick = { activePlanName = null },
                            modifier = Modifier.size(16.dp)
                        ) {
                            Text("✕", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 현장 삼각대 위치 유도 (GPS 켜짐 시 컴팩트 표시 + 스마트폰 시선 방향)
                userLocation?.let { uLoc ->
                    val distToCam = AlignmentFinder.calculateDistanceMeters(uLoc, cameraPoint)
                    val bearingToCam = AlignmentFinder.calculateBearingDeg(uLoc, cameraPoint)
                    val isArrived = distToCam < 8.0
                    val headingText = if (userHeadingDeg != null) " · 시선 ${userHeadingDeg!!.roundToInt()}°" else ""
                    Row(
                        modifier = Modifier
                            .background(Color(0xE00F172A), RoundedCornerShape(12.dp))
                            .border(1.dp, if (isArrived) SunGold.copy(alpha = 0.8f) else Color(0xFF0284C7).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(if (isArrived) "🎯" else "🧭", fontSize = 10.sp)
                        val distText = if (distToCam >= 1000) String.format("%.2fkm", distToCam / 1000.0) else "${distToCam.roundToInt()}m"
                        val guideMsg = if (isArrived) "삼각대 도착 (오차 ${distToCam.roundToInt()}m)$headingText" else "삼각대까지 $distText · ${bearingToCam.roundToInt()}°$headingText"
                        Text(guideMsg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }

                // 마커 터치 시 미니멀 토스트 정보
                selectedMarkerMessage?.let { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xF20F172A), RoundedCornerShape(12.dp))
                            .border(1.dp, SunGold.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ℹ️", fontSize = 11.sp)
                            Text(msg, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                        IconButton(
                            onClick = { selectedMarkerMessage = null },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("✕", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 지도 좌측 하단: 단일 플로팅 글래스 정렬 캡슐
            val calcDist = sharedState.calculatorRequiredDistance
            val calcDistStr = if (calcDist >= 1000) String.format("%.2fkm", calcDist / 1000.0) else "${calcDist.roundToInt()}m"
            var showDistanceMenu by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 10.dp)
                    .background(Color(0xD10F172A), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = alignCameraToColinear,
                    colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🎯", fontSize = 10.sp)
                        Text("카메라 1열", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = alignTimeToCelestialTarget,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⏱️", fontSize = 10.sp)
                        Text("시각 1열", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sharedState.isDistanceLockedToCalculator) Color(0xFF0284C7) else Color(0xE61E293B))
                            .border(1.dp, if (sharedState.isDistanceLockedToCalculator) SunGold.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .clickable { showDistanceMenu = true }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (sharedState.isDistanceLockedToCalculator) "🔒" else "🔓", fontSize = 10.sp)
                            Text(calcDistStr, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    DropdownMenu(
                        expanded = showDistanceMenu,
                        onDismissRequest = { showDistanceMenu = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = { Text("🗼 피사체 고정 ➔ 📷 정렬 ($calcDistStr)", fontSize = 12.sp, color = Color.White) },
                            onClick = {
                                sharedState.anchorMode = AlignmentAnchorMode.TARGET_FIXED
                                sharedState.pinMoveMode = PinMoveMode.CAMERA
                                sharedState.alignCameraToCalculatorDistance()
                                showDistanceMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📷 카메라 고정 ➔ 🗼 정렬 ($calcDistStr)", fontSize = 12.sp, color = Color.White) },
                            onClick = {
                                sharedState.anchorMode = AlignmentAnchorMode.CAMERA_FIXED
                                sharedState.pinMoveMode = PinMoveMode.TARGET
                                sharedState.alignTargetToCalculatorDistance()
                                showDistanceMenu = false
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sharedState.isDistanceLockedToCalculator) "거리 고정 해제 (자유 이동)" else "거리 고정 켜기 ($calcDistStr)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SunGold
                                )
                            },
                            onClick = {
                                sharedState.isDistanceLockedToCalculator = !sharedState.isDistanceLockedToCalculator
                                if (sharedState.isDistanceLockedToCalculator) {
                                    sharedState.alignToCalculatorDistance()
                                }
                                showDistanceMenu = false
                            }
                        )
                    }
                }
            }
        }

        // 2. 하단 분석 패널 (미니멀 뷰파인더 HUD - iOS 완전 동일)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 천체 선택 캡슐 및 프리셋 칩 (수평 스크롤 행)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 천체 선택 토글 캡슐 [ 🌕 달 | ☀️ 태양 ]
                Row(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(2.5.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isMoon = sharedState.celestialTarget == CelestialTargetType.MOON
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isMoon) SunGold else Color.Transparent)
                            .clickable { sharedState.celestialTarget = CelestialTargetType.MOON }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${moon.phase.emoji} 달",
                            fontSize = 11.sp,
                            fontWeight = if (isMoon) FontWeight.Bold else FontWeight.Medium,
                            color = if (isMoon) NightSkyBackground else TextPrimary
                        )
                    }

                    val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isSun) Color(0xFFF59E0B) else Color.Transparent)
                            .clickable { sharedState.celestialTarget = CelestialTargetType.SUN }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "☀️ 태양",
                            fontSize = 11.sp,
                            fontWeight = if (isSun) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSun) NightSkyBackground else TextPrimary
                        )
                    }
                }

                // 프리셋 칩 (수평 스크롤)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(PRESET_SPOTS) { spot ->
                        val isSelected = spot == sharedState.selectedPreset && (userLocation == null || cameraPoint != userLocation)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) SunGold else CardBackground)
                                .clickable {
                                    sharedState.selectedPreset = spot
                                    sharedState.cameraPoint = spot.camera
                                    sharedState.targetPoint = spot.target
                                    sharedState.targetHeight = spot.targetHeightMeters
                                    sharedState.subjectHeight = spot.targetHeightMeters.toFloat()
                                    mapViewRef?.controller?.animateTo(OsmGeoPoint(spot.camera.latitudeDeg, spot.camera.longitudeDeg))
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = spot.name.split("➔")[0].trim(),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) NightSkyBackground else TextPrimary
                            )
                        }
                    }
                }
            }

            // HUD 카드 (미니멀 전문 뷰파인더)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAligned) Color(0xD9064E3B) else CardBackground
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isAligned) SunGold.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val distStr = if (distance >= 1000) String.format("%.2f", distance / 1000.0) else "${distance.roundToInt()}"
                            val unitStr = if (distance >= 1000) "km" else "m"
                            Text(
                                text = distStr,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = MoonGlow
                            )
                            Text(
                                text = unitStr,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )
                            Text(
                                text = "· ${bearing.roundToInt()}°",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                        }
                        val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
                        val targetCelestialStr = if (isSun) {
                            "태양 ${String.format("%.1f", sun.azimuthDeg)}° · 고도 ${String.format("%.1f", sun.altitudeDeg)}°"
                        } else {
                            "달 ${String.format("%.1f", moon.azimuthDeg)}° · 고도 ${String.format("%.1f", moon.altitudeDeg)}°"
                        }
                        Text(
                            text = targetCelestialStr,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isAligned) SunGold.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 8.dp, vertical = 3.5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isAligned) SunGold else if (azDiff < 3.0) Color(0xFFF59E0B) else TextSecondary.copy(alpha = 0.4f))
                                )
                                Text(
                                    text = if (isAligned) "1열 일치" else String.format("오차 %.1f°", azDiff),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAligned) SunGold else TextPrimary
                                )
                            }
                        }

                        if (shadowResult.isSunAboveHorizon) {
                            Text(
                                text = "그림자 ${String.format("%.1f", shadowResult.shadowLengthMeters)}m (${String.format("%.1f", shadowResult.shadowRatio)}x)",
                                fontSize = 10.5.sp,
                                color = SunGold.copy(alpha = 0.9f)
                            )
                        } else {
                            Text(
                                text = "일몰 후",
                                fontSize = 10.5.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // 시간 슬라이더 & 날짜 선택 & 정렬 탐색 버튼
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { today = today.minusDays(1); isLiveSync = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("◀", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { openDatePicker() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "📅 ${today.format(DateTimeFormatter.ofPattern("M/d (E)", java.util.Locale.KOREAN))}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SunGold
                        )
                    }

                    IconButton(
                        onClick = { today = today.plusDays(1); isLiveSync = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("▶", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    val timeText = if (isLiveSync) {
                        val lt = LocalTime.now(seoulZone)
                        String.format("%02d:%02d", lt.hour, lt.minute)
                    } else {
                        val totalMinutes = (selectedHourFloat * 60f).roundToInt().coerceIn(0, 1439)
                        String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60)
                    }

                    Text(
                        text = timeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    TextButton(
                        onClick = {
                            val zdt = ZonedDateTime.now(seoulZone)
                            today = zdt.toLocalDate()
                            val lt = zdt.toLocalTime()
                            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                            isLiveSync = true
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (isLiveSync) "🟢" else "현재",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MoonGlow
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (sharedState.celestialTarget == CelestialTargetType.MOON) {
                        Button(
                            onClick = { showOpportunitySheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                "🏆 달걸이 골든타임",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Button(
                        onClick = { runSearch(searchDays, selectedPhaseFilter) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.celestialTarget == CelestialTargetType.MOON) CardBackground else SunGold,
                            contentColor = if (sharedState.celestialTarget == CelestialTargetType.MOON) MoonGlow else NightSkyBackground
                        ),
                        border = if (sharedState.celestialTarget == CelestialTargetType.MOON) BorderStroke(1.dp, GridLine) else null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            if (isSearching) "탐색중..." else if (sharedState.celestialTarget == CelestialTargetType.MOON) "🔍 일반 탐색" else "🔍 ${searchDays}일 정렬 탐색",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
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
            }
        }
    }

    // 🏆 달걸이 골든타임 탐색 모달 바텀시트
    if (showOpportunitySheet) {
        MoonAlignmentOpportunitySheet(
            cameraPoint = cameraPoint,
            targetPoint = targetPoint,
            targetHeightMeters = targetHeight,
            subjectName = sharedState.selectedSubjectType.displayName,
            onDismissRequest = { showOpportunitySheet = false },
            onSelectOpportunity = { opp ->
                val zdt = opp.timestamp.atZone(seoulZone)
                today = zdt.toLocalDate()
                val lt = zdt.toLocalTime()
                selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                isLiveSync = false
                val timeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
                selectedMarkerMessage = "🏆 ${opp.grade.badgeText} 달걸이 골든타임(${zdt.format(timeFormatter)}, ${opp.skyStage.shortLabel})으로 이동되었습니다!"
            },
            onSaveOpportunity = { opp ->
                val zdt = opp.timestamp.atZone(seoulZone)
                today = zdt.toLocalDate()
                val lt = zdt.toLocalTime()
                selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                isLiveSync = false
                val timeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
                val targetName = sharedState.selectedSubjectType.displayName
                planNameInput = "$targetName 달걸이 (${zdt.format(timeFormatter)})"
                showSavePlanDialog = true
            }
        )
    }

    // 3. 최적 정렬 시각 모달 바텀시트 (기간 선택 + 위상 필터)
    if (showTimelineSheet) {
        val isSun = sharedState.celestialTarget == CelestialTargetType.SUN
        val targetName = if (isSun) "태양" else "달"
        ModalBottomSheet(
            onDismissRequest = { showTimelineSheet = false },
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "🎯 $targetName 정렬 타이밍 검색 (${bestAlignments.size}건 발견)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "선택된 기준일(${today.format(DateTimeFormatter.ofPattern("M/d"))})부터 $targetName" + (if (isSun) "이" else "이") + " 피사체 정중앙을 관통하는 최적 순간입니다.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                // 2번 기능: 검색 기간 선택 칩
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("기간:", fontSize = 11.5.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    listOf(3 to "3일", 7 to "7일(1주)", 30 to "30일(1달)", 90 to "90일(3달)").forEach { (days, label) ->
                        val isSelected = searchDays == days
                        FilterChip(
                            selected = isSelected,
                            onClick = { runSearch(days, selectedPhaseFilter) },
                            label = { Text(label, fontSize = 10.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SunGold,
                                selectedLabelColor = NightSkyBackground
                            )
                        )
                    }
                }

                if (!isSun) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // 3번 기능: 달 위상 필터 칩 (달 모드에서만 표시)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("위상:", fontSize = 11.5.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        AlignmentFinder.MoonPhaseFilter.values().forEach { filter ->
                            val isSelected = selectedPhaseFilter == filter
                            FilterChip(
                                selected = isSelected,
                                onClick = { runSearch(searchDays, filter) },
                                label = { Text(filter.displayName, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF6366F1),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SunGold)
                    }
                } else if (bestAlignments.isEmpty()) {
                    Text("선택한 기간 동안 일직선으로 정렬되는 $targetName 궤적이 없습니다.\n탐색 기간을 늘리거나 카메라 핀의 위치를 조정해 보세요.", color = TextSecondary, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(bestAlignments) { event ->
                            AlignmentEventCard(
                                event = event,
                                onApply = {
                                    val zdt = event.timestamp.atZone(seoulZone)
                                    today = zdt.toLocalDate()
                                    val lt = zdt.toLocalTime()
                                    selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                                    isLiveSync = false
                                    showTimelineSheet = false
                                    selectedMarkerMessage = "🎯 정렬 시점(${zdt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))})으로 지도가 이동되었습니다."
                                },
                                onSavePlan = {
                                    val zdt = event.timestamp.atZone(seoulZone)
                                    val dateStr = zdt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                                    planNameInput = "${sharedState.selectedSubjectType.displayName} 정렬 ($dateStr)"
                                    today = zdt.toLocalDate()
                                    val lt = zdt.toLocalTime()
                                    selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                                    isLiveSync = false
                                    showTimelineSheet = false
                                    showSavePlanDialog = true
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 4. 촬영 계획 저장 다이얼로그 (1번 기능)
    if (showSavePlanDialog) {
        AlertDialog(
            onDismissRequest = { showSavePlanDialog = false },
            containerColor = SurfaceDark,
            title = { Text("💾 촬영 계획 저장", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "현재 설정된 카메라 및 피사체 위치와 날짜를 기기에 영구 저장합니다. 출사 당일 현장에서 위치 가이드로 바로 활용할 수 있습니다.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = planNameInput,
                        onValueChange = { planNameInput = it },
                        label = { Text("계획 이름") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SunGold,
                            unfocusedBorderColor = TextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("📅 예정 시각: ${today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} ${String.format("%02d:%02d", selectedHour, selectedMinute)}", fontSize = 11.5.sp, color = MoonGlow)
                    Text("📏 촬영 거리: ${if (distance >= 1000) String.format("%.2f km", distance / 1000.0) else "${distance.roundToInt()} m"}", fontSize = 11.5.sp, color = MoonGlow)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = planNameInput.ifBlank { "촬영 계획" }
                        val newPlan = ShotPlan(
                            name = finalName,
                            plannedTimestampEpochMilli = selectedInstant.toEpochMilli(),
                            cameraPoint = cameraPoint,
                            targetPoint = targetPoint,
                            targetHeightMeters = targetHeight,
                            distanceMeters = distance,
                            focalLengthMm = sharedState.focalLength
                        )
                        ShotPlanStorage.savePlan(context, newPlan)
                        savedPlans = ShotPlanStorage.loadPlans(context)
                        activePlanName = finalName
                        showSavePlanDialog = false
                        selectedMarkerMessage = "💾 '$finalName' 계획이 안전하게 저장되었습니다!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground)
                ) {
                    Text("저장", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePlanDialog = false }) {
                    Text("취소", color = TextSecondary)
                }
            }
        )
    }

    // 5. 저장된 촬영 계획 목록 모달 (1번 기능)
    if (showSavedPlansSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSavedPlansSheet = false },
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 저장된 촬영 계획 (${savedPlans.size}개)",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = { showSavedPlansSheet = false }, modifier = Modifier.size(24.dp)) {
                        Text("✕", color = TextSecondary, fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (savedPlans.isEmpty()) {
                    Text(
                        "아직 저장된 계획이 없습니다.\n정렬 위치를 맞춘 뒤 [💾 계획 저장]을 누르면 여기에 보관됩니다.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(savedPlans, key = { it.id }) { plan ->
                            val planZdt = Instant.ofEpochMilli(plan.plannedTimestampEpochMilli).atZone(seoulZone)
                            val planDateStr = planZdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.KOREAN))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CardBackground),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(plan.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        IconButton(
                                            onClick = {
                                                ShotPlanStorage.deletePlan(context, plan.id)
                                                savedPlans = ShotPlanStorage.loadPlans(context)
                                                if (activePlanName == plan.name) activePlanName = null
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("🗑️", fontSize = 12.sp)
                                        }
                                    }
                                    Text("📅 예정 시각: $planDateStr", fontSize = 11.5.sp, color = SunGold)
                                    Text("📏 거리: ${if (plan.distanceMeters >= 1000) String.format("%.2f km", plan.distanceMeters / 1000.0) else "${plan.distanceMeters.roundToInt()} m"} | 피사체 높이: ${plan.targetHeightMeters.roundToInt()}m", fontSize = 11.sp, color = TextSecondary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            sharedState.cameraPoint = plan.cameraPoint
                                            sharedState.targetPoint = plan.targetPoint
                                            sharedState.targetHeight = plan.targetHeightMeters
                                            today = planZdt.toLocalDate()
                                            val lt = planZdt.toLocalTime()
                                            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
                                            isLiveSync = false
                                            activePlanName = plan.name
                                            showSavedPlansSheet = false
                                            mapViewRef?.controller?.animateTo(OsmGeoPoint(plan.cameraPoint.latitudeDeg, plan.cameraPoint.longitudeDeg))
                                            selectedMarkerMessage = "📋 '${plan.name}' 계획을 지도에 불러왔습니다! 현장 가이드가 활성화되었습니다."
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("🗺️ 지도에 불러오기 & 현장 가이드", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * iOS 스타일 실시간 사용자 GPS 위치 퍽 및 지향 각도 비전 콘 마커 생성
 */
private fun createUserPuckMarkerIcon(
    context: Context,
    headingDeg: Double?
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (96 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 스마트폰 지향 각도 비전 콘 (부채꼴 광선 및 화살촉)
    if (headingDeg != null) {
        val coneRadius = 40f * density
        val coneAngle = 60f
        val startAngle = (headingDeg.toFloat() - 90f) - coneAngle / 2f
        val oval = RectF(center - coneRadius, center - coneRadius, center + coneRadius, center + coneRadius)

        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.parseColor("#440284C7")
        canvas.drawArc(oval, startAngle, coneAngle, true, paint)

        // 방향 포인터 화살촉
        canvas.save()
        canvas.translate(center, center)
        canvas.rotate(headingDeg.toFloat() - 90f)
        val arrowPath = Path().apply {
            val arrowDist = 17f * density
            moveTo(arrowDist + 8f * density, 0f)
            lineTo(arrowDist, -4.5f * density)
            lineTo(arrowDist + 2.5f * density, 0f)
            lineTo(arrowDist, 4.5f * density)
            close()
        }
        paint.color = AndroidColor.parseColor("#38BDF8")
        canvas.drawPath(arrowPath, paint)
        canvas.restore()
    }

    // 외곽 은은한 블루 펄스 링
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.parseColor("#440EA5E9")
    canvas.drawCircle(center, center, 13f * density, paint)

    // 화이트 테두리
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(center, center, 9f * density, paint)

    // 블루 코어
    paint.color = AndroidColor.parseColor("#0284C7")
    canvas.drawCircle(center, center, 7f * density, paint)

    // 중앙 하이라이트 화이트 도트
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(center, center, 2.5f * density, paint)

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * 고해상도 원형 배지 마커 생성 (선택 시 황금 네온 링 강조 포함)
 */
private fun createCircularMarkerIcon(
    context: Context,
    symbol: String,
    bgColor: Int,
    sizeDp: Int = 46,
    borderColor: Int = AndroidColor.WHITE,
    isSelected: Boolean = false
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val totalDp = if (isSelected) sizeDp + 14 else sizeDp
    val canvasSizePx = (totalDp * density).toInt().coerceAtLeast(1)
    val baseSizePx = (sizeDp * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(canvasSizePx, canvasSizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = canvasSizePx / 2f

    if (isSelected) {
        // 선택된 핀 외곽 네온 황금빛 후광 링
        paint.color = AndroidColor.parseColor("#55FDE047")
        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, center - 2f * density, paint)

        paint.color = AndroidColor.parseColor("#FDE047")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        canvas.drawCircle(center, center, center - 2f * density, paint)
    }

    // 원형 배경
    paint.style = Paint.Style.FILL
    paint.color = bgColor
    canvas.drawCircle(center, center, baseSizePx / 2f - 3f * density, paint)

    // 테두리 라인
    paint.color = borderColor
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.5f * density
    canvas.drawCircle(center, center, baseSizePx / 2f - 3f * density, paint)

    // 중앙 텍스트 / 이모지
    paint.style = Paint.Style.FILL
    paint.textSize = baseSizePx * 0.48f
    paint.textAlign = Paint.Align.CENTER
    val fontMetrics = paint.fontMetrics
    val yPos = center - ((fontMetrics.descent + fontMetrics.ascent) / 2f)
    canvas.drawText(symbol, center, yPos, paint)

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * 텍스트 뱃지 마커
 */
private fun createTextBadgeIcon(
    context: Context,
    text: String,
    bgColor: Int = AndroidColor.parseColor("#E60F172A"),
    textColor: Int = AndroidColor.WHITE
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    val textWidth = paint.measureText(text)
    val paddingH = 8f * density
    val paddingV = 4f * density
    val widthPx = (textWidth + paddingH * 2f).toInt().coerceAtLeast(1)
    val heightPx = (10f * density + paddingV * 2f + 2f * density).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    paint.color = bgColor
    paint.style = Paint.Style.FILL
    val rect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
    canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)

    paint.color = AndroidColor.parseColor("#44FFFFFF")
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1f * density
    canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)

    paint.color = textColor
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.CENTER
    val fontMetrics = paint.fontMetrics
    val yPos = (heightPx / 2f) - ((fontMetrics.descent + fontMetrics.ascent) / 2f)
    canvas.drawText(text, widthPx / 2f, yPos, paint)

    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun AlignmentEventCard(
    event: AlignmentFinder.AlignmentEvent,
    onApply: () -> Unit,
    onSavePlan: () -> Unit
) {
    val zdt = remember(event.timestamp) {
        event.timestamp.atZone(ZoneId.of("Asia/Seoul"))
    }
    val dateStr = remember(zdt) {
        zdt.format(DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", java.util.Locale.KOREAN))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dateStr,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val subTitle = if (event.targetType == "SUN") {
                        "☀️ 태양 (일출/일몰 및 랜드마크 정렬)"
                    } else {
                        "${event.moonDetails.phase.displayName} (${(event.moonDetails.illuminationFraction * 100).toInt()}%)"
                    }
                    Text(
                        text = subTitle,
                        fontSize = 12.sp,
                        color = SunGold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "정렬 오차: %.2f°".format(java.util.Locale.US, event.angularOffsetDeg),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "고도 %.1f° / 방위 %.1f°".format(java.util.Locale.US, event.moonDetails.altitudeDeg, event.moonDetails.azimuthDeg),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApply,
                    colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) {
                    Text("🗺️ 이 시점으로 이동", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onSavePlan,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) {
                    Text("💾 계획으로 저장", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
