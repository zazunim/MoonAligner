package com.moonaligner.app.ui.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.app.DatePickerDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.moonaligner.app.core.alignment.AlignmentFinder
import com.moonaligner.app.core.alignment.GeoPoint
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

enum class PlannerHudTab {
    MOON_ALIGNMENT,
    SUN_AND_SHADOW
}

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
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

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

    val selectedHour = selectedHourFloat.toInt().coerceIn(0, 23)
    val selectedMinute = ((selectedHourFloat - selectedHour) * 60f).toInt().coerceIn(0, 59)
    val selectedInstant = remember(today, selectedHour, selectedMinute) {
        ZonedDateTime.of(today, LocalTime.of(selectedHour, selectedMinute), seoulZone).toInstant()
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
    var currentTab by remember { mutableStateOf(PlannerHudTab.SUN_AND_SHADOW) }

    // 기하학 계산값 (거리, 방위각)
    val distance = remember(cameraPoint, targetPoint) {
        AlignmentFinder.calculateDistanceMeters(cameraPoint, targetPoint)
    }
    val bearing = remember(cameraPoint, targetPoint) {
        AlignmentFinder.calculateBearingDeg(cameraPoint, targetPoint)
    }

    // 달 방향과 조준선의 일치 여부
    val azDiff = remember(moon.azimuthDeg, bearing) {
        val diff = abs(moon.azimuthDeg - bearing)
        min(diff, 360.0 - diff)
    }
    val isAligned = azDiff < 1.0 && moon.altitudeDeg > -2.0

    val alignCameraToColinear: () -> Unit = {
        sharedState.alignCameraToMoonLine(moon.azimuthDeg)
        selectedMarkerMessage = "🎯 3점 1열 카메라 정렬 완료! [📷카메라 ➔ 🗼피사체 ➔ 🌕달]"
    }

    val alignTimeToMoon: () -> Unit = {
        val match = AlignmentFinder.findNextAzimuthAlignment(
            camera = cameraPoint,
            target = targetPoint,
            startInstant = selectedInstant
        )
        if (match != null) {
            val zdt = match.timestamp.atZone(seoulZone)
            today = zdt.toLocalDate()
            val lt = zdt.toLocalTime()
            selectedHourFloat = lt.hour + lt.minute / 60f + lt.second / 3600f
            isLiveSync = false
            selectedMarkerMessage = "🎯 3점 1열 정렬 시각으로 이동! (각도차 ${String.format("%.2f", match.offsetDeg)}°)"
        } else {
            selectedMarkerMessage = "⚠️ 향후 48시간 내에 일직선으로 정렬되는 달 궤적이 없습니다."
        }
    }

    // 정렬 최적 일시 검색 상태 (기간 및 달 위상 필터)
    var isSearching by remember { mutableStateOf(false) }
    var searchDays by remember { mutableStateOf(7) }
    var selectedPhaseFilter by remember { mutableStateOf(AlignmentFinder.MoonPhaseFilter.ALL) }
    var bestAlignments by remember { mutableStateOf<List<AlignmentFinder.AlignmentEvent>>(emptyList()) }
    var showTimelineSheet by remember { mutableStateOf(false) }

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
            val results = withContext(Dispatchers.Default) {
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
                        targetHeight.roundToInt(),
                        selectedHour,
                        selectedMinute
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

                    // (B) 달 방향선 (선명한 달빛 황금색 빔 + 🌕 달 배지 마커)
                    val moonDist = kotlin.math.max(distance * 1.6, distance + 3500.0).coerceAtLeast(6000.0)
                    val moonTip = AlignmentFinder.calculateDestinationPoint(cameraPoint, moonDist, moon.azimuthDeg)
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
                        title = "🌕 현재 달 (${moon.phase.displayName})"
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
                            if (isAligned) "🎯 3점 1열 정렬 [📷➔🗼➔🌕] (${String.format("%.0f", moon.azimuthDeg)}°)"
                            else "🌕 달 궤적 (${String.format("%.0f", moon.azimuthDeg)}° / ${String.format("%.1f", moon.altitudeDeg)}°)",
                            bgColor = if (isAligned) AndroidColor.parseColor("#E614532D") else AndroidColor.parseColor("#E61E1B4B"),
                            textColor = AndroidColor.parseColor("#FEF08A")
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> false }
                    }
                    mapView.overlays.add(moonLabel)

                    // (C) 태양 & 일출/일몰 광선
                    if (showSunRays) {
                        val sunTip = AlignmentFinder.calculateDestinationPoint(targetPoint, 5000.0, sun.azimuthDeg)
                        val sunEndOsm = OsmGeoPoint(sunTip.latitudeDeg, sunTip.longitudeDeg)
                        val sunRay = Polyline().apply {
                            setPoints(listOf(targetOsm, sunEndOsm))
                            outlinePaint.color = AndroidColor.parseColor("#F59E0B")
                            outlinePaint.strokeWidth = 5f
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
                    }

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
                    userLocation?.let { uLoc ->
                        val userOsm = OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg)
                        val userMarker = Marker(mapView).apply {
                            position = userOsm
                            icon = createCircularMarkerIcon(
                                context = context,
                                symbol = "📍",
                                bgColor = AndroidColor.parseColor("#0EA5E9"),
                                sizeDp = 44,
                                borderColor = AndroidColor.WHITE
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "내 현재 위치"
                            setOnMarkerClickListener { _, _ -> false }
                        }
                        mapView.overlays.add(userMarker)

                        // 현장 삼각대 위치 유도선 (내 위치 ➔ 계획된 카메라 핀 연결)
                        val distToCam = AlignmentFinder.calculateDistanceMeters(uLoc, cameraPoint)
                        if (distToCam > 3.0) {
                            val guideLine = Polyline().apply {
                                setPoints(listOf(userOsm, camOsm))
                                outlinePaint.color = AndroidColor.parseColor("#38BDF8")
                                outlinePaint.strokeWidth = 5f
                                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
                                title = "삼각대 유도선"
                            }
                            mapView.overlays.add(guideLine)

                            val guideMidLat = (uLoc.latitudeDeg + cameraPoint.latitudeDeg) / 2.0
                            val guideMidLon = (uLoc.longitudeDeg + cameraPoint.longitudeDeg) / 2.0
                            val guideDistStr = if (distToCam >= 1000) String.format("%.2f km", distToCam / 1000.0) else "${distToCam.roundToInt()} m"
                            val guideBearing = AlignmentFinder.calculateBearingDeg(uLoc, cameraPoint)
                            val guideBadge = Marker(mapView).apply {
                                position = OsmGeoPoint(guideMidLat, guideMidLon)
                                icon = createTextBadgeIcon(
                                    context,
                                    "📍 삼각대까지 $guideDistStr (${String.format("%.0f", guideBearing)}°)",
                                    bgColor = AndroidColor.parseColor("#E60369A1"),
                                    textColor = AndroidColor.WHITE
                                )
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                setOnMarkerClickListener { _, _ -> false }
                            }
                            mapView.overlays.add(guideBadge)
                        }
                    }

                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            // 상단: 원탭 핀 선택 스위치 바 & 상태 안내 & 마커 정보 배너
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1) 불러온 촬영 계획 표시 배너
                activePlanName?.let { pName ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xF2047857)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SunGold.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "📌 불러온 계획: $pName",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { activePlanName = null },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Text("✕", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 2) 실시간 현장 삼각대 위치 유도 가이드 배너 (GPS 켜짐 시)
                userLocation?.let { uLoc ->
                    val distToCam = AlignmentFinder.calculateDistanceMeters(uLoc, cameraPoint)
                    val bearingToCam = AlignmentFinder.calculateBearingDeg(uLoc, cameraPoint)
                    val isArrived = distToCam < 8.0
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isArrived) Color(0xF2059669) else Color(0xF20284C7)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (isArrived) SunGold else Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val guideMsg = if (isArrived) {
                                "🎉 삼각대 목표 위치 도착! (오차 ${distToCam.roundToInt()}m)"
                            } else {
                                val distStr = if (distToCam >= 1000) String.format("%.2fkm", distToCam / 1000.0) else "${distToCam.roundToInt()}m"
                                "📍 현장 삼각대 지점까지: $distStr (${bearingToCam.roundToInt()}° 방향 이동)"
                            }
                            Text(
                                text = guideMsg,
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 마커 터치 시 나타나는 시각/방위각 정보 배너 (일출, 일몰, 태양, 달)
                selectedMarkerMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xF20F172A)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SunGold.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "ℹ️ $msg",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { selectedMarkerMessage = null },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Text("✕", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            sharedState.pinMoveMode = PinMoveMode.CAMERA
                            sharedState.anchorMode = AlignmentAnchorMode.TARGET_FIXED
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.pinMoveMode == PinMoveMode.CAMERA) Color(0xFF0284C7) else SurfaceDark.copy(alpha = 0.9f),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("📷 카메라 이동", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            sharedState.pinMoveMode = PinMoveMode.TARGET
                            sharedState.anchorMode = AlignmentAnchorMode.CAMERA_FIXED
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.pinMoveMode == PinMoveMode.TARGET) Color(0xFFE11D48) else SurfaceDark.copy(alpha = 0.9f),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("🗼 피사체 이동", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { sharedState.pinMoveMode = PinMoveMode.NONE },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.pinMoveMode == PinMoveMode.NONE) SunGold else SurfaceDark.copy(alpha = 0.9f),
                            contentColor = if (sharedState.pinMoveMode == PinMoveMode.NONE) NightSkyBackground else TextSecondary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("✋ 잠금", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                val guideText = when (sharedState.pinMoveMode) {
                    PinMoveMode.CAMERA -> if (sharedState.isDistanceLockedToCalculator) {
                        "🎯 지도를 한 번 터치하면 [🗼 피사체 고정 + 거리 유지] 상태로 📷 방향이 이동합니다"
                    } else {
                        "💡 지도의 원하는 지점을 한 번 터치하면 📷 카메라가 즉시 이동합니다"
                    }
                    PinMoveMode.TARGET -> if (sharedState.isDistanceLockedToCalculator) {
                        "🎯 지도를 한 번 터치하면 [📷 카메라 고정 + 거리 유지] 상태로 🗼 피사체 방향이 이동합니다"
                    } else {
                        "💡 지도의 원하는 지점을 한 번 터치하면 🗼 피사체가 즉시 이동합니다"
                    }
                    PinMoveMode.NONE -> "✋ 핀 이동 잠금 상태 (핀이나 상단 버튼을 한 번 터치해 선택)"
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.92f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = guideText,
                        color = MoonGlow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // 지도 우측 상단 플로팅 범례 (Legend)
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 10.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.88f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("🗺️ 지도 범례", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text("📷 카메라 (원탭 이동)", fontSize = 9.sp, color = Color(0xFF38BDF8))
                    Text("🗼 피사체 (원탭 이동)", fontSize = 9.sp, color = Color(0xFFFB7185))
                    Text("⭕ 촬영 거리 반경 원", fontSize = 9.sp, color = Color(0xFF38BDF8))
                    Text("🌕 달 / ☀️ 태양 광선", fontSize = 9.sp, color = SunGold)
                    Text("👥 피사체 그림자", fontSize = 9.sp, color = Color(0xFF94A3B8))
                }
            }

            // 지도 하단 좌측: 계산기 거리 자동 정렬 버튼 & 실시간 GPS 컨트롤 버튼
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val calcDist = sharedState.calculatorRequiredDistance
                val calcDistStr = if (calcDist >= 1000) String.format("%.2fkm", calcDist / 1000.0) else "${calcDist.roundToInt()}m"

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(
                        onClick = {
                            sharedState.anchorMode = AlignmentAnchorMode.TARGET_FIXED
                            sharedState.pinMoveMode = PinMoveMode.CAMERA
                            sharedState.alignCameraToCalculatorDistance()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.anchorMode == AlignmentAnchorMode.TARGET_FIXED) SunGold else SurfaceDark.copy(alpha = 0.92f),
                            contentColor = if (sharedState.anchorMode == AlignmentAnchorMode.TARGET_FIXED) NightSkyBackground else Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text("🗼고정➔📷정렬($calcDistStr)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            sharedState.anchorMode = AlignmentAnchorMode.CAMERA_FIXED
                            sharedState.pinMoveMode = PinMoveMode.TARGET
                            sharedState.alignTargetToCalculatorDistance()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.anchorMode == AlignmentAnchorMode.CAMERA_FIXED) Color(0xFFFB7185) else SurfaceDark.copy(alpha = 0.92f),
                            contentColor = if (sharedState.anchorMode == AlignmentAnchorMode.CAMERA_FIXED) NightSkyBackground else Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text("📷고정➔🗼정렬($calcDistStr)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            sharedState.isDistanceLockedToCalculator = !sharedState.isDistanceLockedToCalculator
                            if (sharedState.isDistanceLockedToCalculator) {
                                sharedState.alignToCalculatorDistance()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sharedState.isDistanceLockedToCalculator) Color(0xFF0284C7) else SurfaceDark.copy(alpha = 0.92f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text(if (sharedState.isDistanceLockedToCalculator) "🔒거리고정" else "🔓자유", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(
                        onClick = alignCameraToColinear,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SunGold,
                            contentColor = NightSkyBackground
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text("🎯1열(📷카메라 이동)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = alignTimeToMoon,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6366F1),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text("⏱️1열(시각 맞춤)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(
                        onClick = {
                            val defaultName = "${sharedState.selectedSubjectType.displayName} 정렬 (${today.format(DateTimeFormatter.ofPattern("M/d"))} ${String.format("%02d:%02d", selectedHour, selectedMinute)})"
                            planNameInput = defaultName
                            showSavePlanDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF059669),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text("💾 계획 저장", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            savedPlans = ShotPlanStorage.loadPlans(context)
                            showSavedPlansSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF334155),
                            contentColor = SunGold
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Text("📋 계획 목록 (${savedPlans.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (!hasLocationPermission) {
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                userLocation?.let { uLoc ->
                                    mapViewRef?.controller?.animateTo(OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (userLocation != null) SurfaceDark.copy(alpha = 0.92f) else Color(0xDD78350F),
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Text(
                            text = if (userLocation != null) "📍 내 위치로 이동" else "📍 실시간 GPS 켜기",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (userLocation != null) {
                        Button(
                            onClick = {
                                userLocation?.let { uLoc ->
                                    sharedState.cameraPoint = uLoc
                                    mapViewRef?.controller?.animateTo(OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0284C7),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Text("내 위치 ➔ 📷 설정", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. 하단 컨트롤 및 SunOnTrack 분석 패널
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(12.dp)
        ) {
            // (A) 인기 출사지 프리셋 선택 칩 + 내 현재 위치 칩
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = userLocation != null && cameraPoint == userLocation,
                    onClick = {
                        if (!hasLocationPermission) {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            userLocation?.let { uLoc ->
                                sharedState.cameraPoint = uLoc
                                mapViewRef?.controller?.animateTo(OsmGeoPoint(uLoc.latitudeDeg, uLoc.longitudeDeg))
                            }
                        }
                    },
                    label = { Text("📍 내 위치", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF0284C7),
                        selectedLabelColor = Color.White,
                        containerColor = if (userLocation != null) Color(0xFF0369A1) else CardBackground,
                        labelColor = Color.White
                    )
                )

                PRESET_SPOTS.forEach { spot ->
                    val isSelected = spot == sharedState.selectedPreset && (userLocation == null || cameraPoint != userLocation)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            sharedState.selectedPreset = spot
                            sharedState.cameraPoint = spot.camera
                            sharedState.targetPoint = spot.target
                            sharedState.targetHeight = spot.targetHeightMeters
                            sharedState.subjectHeight = spot.targetHeightMeters.toFloat()
                            mapViewRef?.controller?.animateTo(OsmGeoPoint(spot.camera.latitudeDeg, spot.camera.longitudeDeg))
                        },
                        label = { Text(spot.name.split("➔")[0].trim(), fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SunGold,
                            selectedLabelColor = NightSkyBackground,
                            containerColor = CardBackground,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // (B) 탭 전환: SunOnTrack 그림자/일조량 분석 vs 보름달 조준 정렬
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentTab == PlannerHudTab.SUN_AND_SHADOW,
                    onClick = { currentTab = PlannerHudTab.SUN_AND_SHADOW },
                    label = { Text("☀️ 그림자 & 태양 분석 (SunOnTrack)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SunGold,
                        selectedLabelColor = NightSkyBackground,
                        containerColor = CardBackground,
                        labelColor = TextPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentTab == PlannerHudTab.MOON_ALIGNMENT,
                    onClick = { currentTab = PlannerHudTab.MOON_ALIGNMENT },
                    label = { Text("🌕 보름달 정렬 조준", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MoonGlow,
                        selectedLabelColor = NightSkyBackground,
                        containerColor = CardBackground,
                        labelColor = TextPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // (C) HUD 카드 내용
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentTab == PlannerHudTab.MOON_ALIGNMENT && isAligned) Color(0xFF1E3A2F) else CardBackground
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (currentTab == PlannerHudTab.SUN_AND_SHADOW) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (shadowResult.isSunAboveHorizon) {
                                        "👥 그림자 길이: ${String.format("%.1f", shadowResult.shadowLengthMeters)} m (배율 %.1fx)".format(shadowResult.shadowRatio)
                                    } else {
                                        "🌙 일몰 후 (태양이 지평선 아래에 있어 그림자 없음)"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (shadowResult.isSunAboveHorizon) SunGold else TextSecondary
                                )
                                Text(
                                    text = "그림자 방향: ${String.format("%.1f", shadowResult.shadowBearingDeg)}° | 피사체 높이: ${targetHeight.toInt()}m",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "직선거리: ${if (distance >= 1000) String.format("%.2f km", distance / 1000.0) else "${distance.roundToInt()} m"}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoonGlow
                                )
                                Text(
                                    text = "태양 고도: ${String.format("%.1f", sun.altitudeDeg)}°",
                                    fontSize = 11.sp,
                                    color = if (sun.altitudeDeg > 0) SunGold else TextSecondary
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "직선거리: ${if (distance >= 1000) String.format("%.2f km", distance / 1000.0) else "${distance.roundToInt()} m"}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoonGlow
                                )
                                Text(
                                    text = "조준 방위: ${String.format("%.1f", bearing)}° | 달 방위: ${String.format("%.1f", moon.azimuthDeg)}°",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (!isAligned) {
                                        Button(
                                            onClick = alignCameraToColinear,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = SunGold,
                                                contentColor = NightSkyBackground
                                            ),
                                            shape = RoundedCornerShape(4.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("🎯1열 카메라", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = alignTimeToMoon,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF6366F1),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(4.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("⏱️1열 시각", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    val statusText = if (isAligned) "🎯 3점 1열 일치!" else "각도차 ${String.format("%.1f", azDiff)}°"
                                    val statusColor = if (isAligned) SunGold else TextSecondary
                                    Text(
                                        text = statusText,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColor
                                    )
                                }
                                Text(
                                    text = if (isAligned) "📷 카메라 ➔ 🗼 피사체 ➔ 🌕 달" else "달 고도: ${String.format("%.1f", moon.altitudeDeg)}°",
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isAligned) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isAligned || moon.altitudeDeg > 0) SunGold else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // (D) 시간 이동 슬라이더 & 날짜 선택
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 📅 날짜 선택 (DatePicker) 버튼
                    Button(
                        onClick = openDatePicker,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = SunGold),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "📅 ${today.format(DateTimeFormatter.ofPattern("M/d (E)", java.util.Locale.KOREAN))}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = { today = today.minusDays(1); isLiveSync = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("◀", color = TextSecondary, fontSize = 10.sp)
                    }
                    IconButton(
                        onClick = { today = today.plusDays(1); isLiveSync = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("▶", color = TextSecondary, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "${String.format("%02d:%02d", selectedHour, selectedMinute)}",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(3.dp))
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
                            text = if (isLiveSync) "🟢 실시간" else "현재복귀",
                            fontSize = 10.sp,
                            color = MoonGlow
                        )
                    }
                }

                Button(
                    onClick = { runSearch(searchDays, selectedPhaseFilter) },
                    colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = NightSkyBackground),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(if (isSearching) "탐색중..." else "🔍 ${searchDays}일 정렬 탐색", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            Slider(
                value = selectedHourFloat,
                onValueChange = {
                    isLiveSync = false
                    selectedHourFloat = it
                },
                valueRange = 0f..24f,
                colors = SliderDefaults.colors(thumbColor = MoonGlow, activeTrackColor = MoonSurface)
            )
        }
    }

    // 3. 최적 정렬 시각 모달 바텀시트 (기간 선택 + 위상 필터)
    if (showTimelineSheet) {
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
                    text = "🎯 정렬 타이밍 검색 (${bestAlignments.size}건 발견)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "선택된 기준일(${today.format(DateTimeFormatter.ofPattern("M/d"))})부터 달이 피사체 정중앙을 관통하는 최적 순간입니다.",
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

                Spacer(modifier = Modifier.height(4.dp))

                // 3번 기능: 달 위상 필터 칩
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

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SunGold)
                    }
                } else if (bestAlignments.isEmpty()) {
                    Text("선택한 기간 및 위상 조건에 일치하는 달 정렬 궤적이 없습니다.\n탐색 기간을 늘리거나 카메라 핀의 위치를 조정해 보세요.", color = TextSecondary, fontSize = 13.sp)
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
                    Text(
                        text = "${event.moonDetails.phase.displayName} (${(event.moonDetails.illuminationFraction * 100).toInt()}%)",
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
