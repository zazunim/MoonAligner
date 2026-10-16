package com.moonaligner.app.ui.calculator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 피사체 종류 정의 (보름달 겹침 실루엣 아트)
 */
enum class SubjectSilhouetteType(
    val displayName: String,
    val defaultHeightM: Float,
    val isFlying: Boolean = false, // 비행체는 달 정중앙을 통과
    val description: String
) {
    PERSON("인물 (사진작가)", 1.8f, false, "삼각대와 망원 카메라를 조작하는 인물 실루엣 (~200m)"),
    PAGODA("전통 누각 (정자)", 6.5f, false, "우아한 기와 처마 곡선의 한국 전통 누각 (~700m)"),
    LOTTE_TOP("롯데타워 (상단 첨탑)", 45.0f, false, "올림픽공원/뚝섬/남한산성 출사 구도 (~4.9km)"),
    LOTTE_FULL("롯데타워 (전체 555m)", 555.0f, false, "건물 전체를 달 크기로 압축하는 원경 샷 (~61km)"),
    N_SEOUL_TOWER("N서울타워 (상단부)", 35.0f, false, "노들섬/용산/반포 출사 구도 (~3.8km)"),
    AIRPLANE("보름달 횡단 여객기", 65.0f, true, "달 정중앙을 관통하는 대형 제트 여객기"),
    BIRD("비상하는 독수리", 1.8f, true, "날개를 활짝 편 맹금류의 실루엣 (~200m)"),
    APARTMENT("도심 아파트 옥상", 40.0f, false, "옥상 안테나와 빌딩 실루엣 (~4.4km)")
}

/**
 * 보름달 및 피사체 그림자 실루엣 고화질 렌더러
 */
object MoonArtRenderer {

    // 칠흑 같은 밤하늘 속 실루엣 색상
    private val SilhouetteColor = Color(0xFF090D14)

    /**
     * 사실적인 크레이터와 바다 지형을 포함한 보름달 아트 렌더링
     */
    fun drawRealisticFullMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float
    ) {
        if (radius <= 2f) return

        with(drawScope) {
            // 1. 달 외곽 대기광 (Atmospheric Moon Halo & Corona Glow)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x50FFF8E1),
                        Color(0x28FFE082),
                        Color(0x10FFD54F),
                        Color(0x00000000)
                    ),
                    center = center,
                    radius = radius * 1.35f
                ),
                radius = radius * 1.35f,
                center = center
            )

            // 2. 보름달 구체 기본 바탕색 (은은한 백금빛 크림색 그라데이션)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFFDE7), // 중심부 밝은 반사광
                        Color(0xFFFFF9C4),
                        Color(0xFFF0E5B8),
                        Color(0xFFE2D4A4)  // 가장자리
                    ),
                    center = Offset(center.x - radius * 0.1f, center.y - radius * 0.1f),
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            // 3. 달의 바다 지형 (Lunar Maria - 어두운 현무암 분지들)
            val mareColor1 = Color(0x356B614D)
            val mareColor2 = Color(0x405A513E)

            // (A) 비의 바다 (Mare Imbrium) - 북서쪽 큰 원형 바다
            drawOval(
                color = mareColor2,
                topLeft = Offset(center.x - radius * 0.55f, center.y - radius * 0.65f),
                size = Size(radius * 0.52f, radius * 0.44f)
            )

            // (B) 폭풍의 대양 (Oceanus Procellarum) - 서쪽 광활한 평원
            drawOval(
                color = mareColor1,
                topLeft = Offset(center.x - radius * 0.85f, center.y - radius * 0.35f),
                size = Size(radius * 0.55f, radius * 0.65f)
            )

            // (C) 맑음의 바다 (Mare Serenitatis) & 고요의 바다 (Mare Tranquillitatis) - 북동 및 동쪽
            drawOval(
                color = mareColor2,
                topLeft = Offset(center.x + radius * 0.05f, center.y - radius * 0.48f),
                size = Size(radius * 0.38f, radius * 0.36f)
            )
            drawOval(
                color = mareColor2,
                topLeft = Offset(center.x + radius * 0.18f, center.y - radius * 0.18f),
                size = Size(radius * 0.45f, radius * 0.40f)
            )

            // (D) 위기의 바다 (Mare Crisium) - 극동쪽 독립된 뚜렷한 타원형 바다
            drawOval(
                color = Color(0x504D4433),
                topLeft = Offset(center.x + radius * 0.62f, center.y - radius * 0.28f),
                size = Size(radius * 0.22f, radius * 0.26f)
            )

            // (E) 풍요의 바다 (Mare Fecunditatis) & 감로의 바다 (Mare Nectaris) - 남동쪽
            drawOval(
                color = mareColor1,
                topLeft = Offset(center.x + radius * 0.22f, center.y + radius * 0.12f),
                size = Size(radius * 0.42f, radius * 0.38f)
            )
            drawOval(
                color = mareColor1,
                topLeft = Offset(center.x + radius * 0.05f, center.y + radius * 0.22f),
                size = Size(radius * 0.28f, radius * 0.26f)
            )

            // 4. 티코 크레이터 (Tycho Crater) 및 사방으로 뻗는 광조 (Ejecta Rays)
            val tychoCenter = Offset(center.x - radius * 0.18f, center.y + radius * 0.58f)
            // 크레이터 중심 핵
            drawCircle(
                color = Color(0xCCFFFFFF),
                radius = radius * 0.045f,
                center = tychoCenter
            )
            drawCircle(
                color = Color(0x505A513E),
                radius = radius * 0.07f,
                center = tychoCenter,
                style = Stroke(width = 1.5f)
            )

            // 방사형 광조선 (Rays)
            val rayColor = Color(0x40FFFFFF)
            val rayAngles = listOf(15f, 45f, 75f, 110f, 150f, 195f, 240f, 290f, 335f)
            for (angle in rayAngles) {
                val rad = Math.toRadians(angle.toDouble())
                val endX = tychoCenter.x + (radius * 0.7f * kotlin.math.cos(rad)).toFloat()
                val endY = tychoCenter.y + (radius * 0.7f * kotlin.math.sin(rad)).toFloat()
                drawLine(
                    color = rayColor,
                    start = tychoCenter,
                    end = Offset(endX, endY),
                    strokeWidth = 1.2f
                )
            }

            // 5. 달 가장자리 림 쉐이딩 (Limb Darkening - 구체 입체감)
            drawCircle(
                color = Color(0x221A1508),
                radius = radius,
                center = center,
                style = Stroke(width = radius * 0.04f)
            )
        }
    }

    /**
     * 선택된 피사체 종류에 따른 정밀 실루엣(그림자 아트) 렌더링
     */
    fun drawSubjectSilhouette(
        drawScope: DrawScope,
        type: SubjectSilhouetteType,
        center: Offset,
        moonRadius: Float,
        scaleRatio: Float
    ) {
        with(drawScope) {
            val totalHeight = (moonRadius * 2f * scaleRatio).coerceAtLeast(10f)

            // 비행체(비행기, 새)는 달 정중앙을 지나고, 지상 피사체는 달 하단에 걸쳐 솟아오름
            val baseAnchorY = if (type.isFlying) {
                center.y + (totalHeight * 0.15f)
            } else {
                center.y + moonRadius + (moonRadius * 0.05f)
            }

            when (type) {
                SubjectSilhouetteType.PERSON -> {
                    drawPersonWithTripod(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.PAGODA -> {
                    drawKoreanPagoda(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.N_SEOUL_TOWER -> {
                    drawNSeoulTower(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.LOTTE_TOP -> {
                    drawLotteTopSpire(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.LOTTE_FULL -> {
                    drawLotteTower(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.AIRPLANE -> {
                    drawJetAirplane(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.BIRD -> {
                    drawSoaringEagle(center.x, baseAnchorY, totalHeight)
                }
                SubjectSilhouetteType.APARTMENT -> {
                    drawApartmentBuilding(center.x, baseAnchorY, totalHeight)
                }
            }
        }
    }

    // --- 1. 인물 (삼각대와 망원렌즈 카메라를 조작하는 사진가 실루엣) ---
    private fun DrawScope.drawPersonWithTripod(cx: Float, bottomY: Float, h: Float) {
        val w = h * 0.55f
        val topY = bottomY - h

        // (A) 삼각대 다리 3개
        val tripodTopX = cx + (w * 0.15f)
        val tripodTopY = topY + (h * 0.42f)
        drawLine(SilhouetteColor, Offset(tripodTopX, tripodTopY), Offset(cx - (w * 0.2f), bottomY), strokeWidth = h * 0.035f)
        drawLine(SilhouetteColor, Offset(tripodTopX, tripodTopY), Offset(cx + (w * 0.45f), bottomY), strokeWidth = h * 0.035f)
        drawLine(SilhouetteColor, Offset(tripodTopX, tripodTopY), Offset(cx + (w * 0.15f), bottomY), strokeWidth = h * 0.03f)

        // (B) 대구경 망원렌즈 + 카메라 바디
        val lensY = topY + (h * 0.38f)
        val lensPath = Path().apply {
            moveTo(tripodTopX - (w * 0.18f), lensY - (h * 0.05f))
            lineTo(tripodTopX + (w * 0.28f), lensY - (h * 0.07f))
            lineTo(tripodTopX + (w * 0.30f), lensY + (h * 0.03f))
            lineTo(tripodTopX - (w * 0.18f), lensY + (h * 0.04f))
            close()
        }
        drawPath(lensPath, SilhouetteColor)

        // (C) 서 있는 사진작가 (머리, 모자, 몸통, 다리)
        val personX = cx - (w * 0.15f)
        // 머리 & 챙모자
        drawCircle(SilhouetteColor, radius = h * 0.075f, center = Offset(personX, topY + (h * 0.12f)))
        drawLine(SilhouetteColor, Offset(personX - (h * 0.1f), topY + (h * 0.1f)), Offset(personX + (h * 0.08f), topY + (h * 0.09f)), strokeWidth = h * 0.025f)

        // 몸통 (자켓)
        val bodyPath = Path().apply {
            moveTo(personX - (h * 0.08f), topY + (h * 0.2f))
            lineTo(personX + (h * 0.08f), topY + (h * 0.2f))
            lineTo(tripodTopX - (w * 0.02f), topY + (h * 0.4f)) // 손을 뻗어 카메라를 잡음
            lineTo(personX + (h * 0.07f), topY + (h * 0.58f))
            lineTo(personX - (h * 0.09f), topY + (h * 0.58f))
            close()
        }
        drawPath(bodyPath, SilhouetteColor)

        // 다리
        drawLine(SilhouetteColor, Offset(personX - (h * 0.04f), topY + (h * 0.58f)), Offset(personX - (h * 0.08f), bottomY), strokeWidth = h * 0.065f)
        drawLine(SilhouetteColor, Offset(personX + (h * 0.04f), topY + (h * 0.58f)), Offset(personX + (h * 0.02f), bottomY), strokeWidth = h * 0.065f)
    }

    // --- 2. 한옥 전통 정자/누각 (처마선과 기와 곡선의 미) ---
    private fun DrawScope.drawKoreanPagoda(cx: Float, bottomY: Float, h: Float) {
        val w = h * 1.35f
        val topY = bottomY - h

        val path = Path().apply {
            // 절병통 (탑 꼭대기 장식)
            moveTo(cx, topY)
            lineTo(cx - w * 0.02f, topY + h * 0.06f)
            lineTo(cx + w * 0.02f, topY + h * 0.06f)
            close()

            // 상단 2층 지붕 (치켜올라간 기와 처마 곡선)
            moveTo(cx, topY + h * 0.06f)
            quadraticTo(cx - w * 0.25f, topY + h * 0.18f, cx - w * 0.48f, topY + h * 0.16f) // 좌측 처마 끝
            lineTo(cx - w * 0.38f, topY + h * 0.25f)
            lineTo(cx - w * 0.2f, topY + h * 0.24f)
            lineTo(cx - w * 0.2f, topY + h * 0.42f)

            // 하단 1층 큰 지붕 처마
            quadraticTo(cx - w * 0.35f, topY + h * 0.46f, cx - w * 0.56f, topY + h * 0.44f) // 좌측 대처마
            lineTo(cx - w * 0.44f, topY + h * 0.54f)
            lineTo(cx - w * 0.32f, topY + h * 0.53f)

            // 1층 기둥과 기단
            lineTo(cx - w * 0.32f, bottomY)
            lineTo(cx + w * 0.32f, bottomY)

            // 우측 대칭 올라가기
            lineTo(cx + w * 0.32f, topY + h * 0.53f)
            lineTo(cx + w * 0.44f, topY + h * 0.54f)
            quadraticTo(cx + w * 0.35f, topY + h * 0.46f, cx + w * 0.2f, topY + h * 0.42f)
            lineTo(cx + w * 0.2f, topY + h * 0.24f)
            lineTo(cx + w * 0.38f, topY + h * 0.25f)
            quadraticTo(cx + w * 0.25f, topY + h * 0.18f, cx, topY + h * 0.06f)
            close()
        }
        drawPath(path, SilhouetteColor)

        // 기둥 사이 통과하는 빛 구멍 (창살/기둥 사이 빈 공간 연출)
        val pillarGap = w * 0.12f
        drawRect(Color(0xFFFFFBEA), topLeft = Offset(cx - pillarGap * 0.7f, topY + h * 0.6f), size = Size(pillarGap * 0.4f, h * 0.28f))
        drawRect(Color(0xFFFFFBEA), topLeft = Offset(cx + pillarGap * 0.3f, topY + h * 0.6f), size = Size(pillarGap * 0.4f, h * 0.28f))
    }

    // --- 3. N서울타워 (남산타워 랜드마크) ---
    private fun DrawScope.drawNSeoulTower(cx: Float, bottomY: Float, h: Float) {
        val topY = bottomY - h
        val w = h * 0.28f

        val towerPath = Path().apply {
            // 안테나 첨탑
            moveTo(cx, topY)
            lineTo(cx - w * 0.015f, topY + h * 0.32f)
            lineTo(cx + w * 0.015f, topY + h * 0.32f)
            close()

            // 회전 전망대 상층 데크
            moveTo(cx - w * 0.16f, topY + h * 0.32f)
            lineTo(cx + w * 0.16f, topY + h * 0.32f)
            lineTo(cx + w * 0.24f, topY + h * 0.36f)
            lineTo(cx + w * 0.20f, topY + h * 0.42f)
            lineTo(cx + w * 0.08f, topY + h * 0.44f)

            // 타워 주 기둥 (아래로 내려갈수록 완만하게 넓어짐)
            lineTo(cx + w * 0.09f, topY + h * 0.78f)
            lineTo(cx + w * 0.25f, bottomY)
            lineTo(cx - w * 0.25f, bottomY)
            lineTo(cx - w * 0.09f, topY + h * 0.78f)
            lineTo(cx - w * 0.08f, topY + h * 0.44f)
            lineTo(cx - w * 0.20f, topY + h * 0.42f)
            lineTo(cx - w * 0.24f, topY + h * 0.36f)
            close()
        }
        drawPath(towerPath, SilhouetteColor)

        // 전망대 창문 밴드
        drawLine(Color(0xFFFFF9C4), Offset(cx - w * 0.18f, topY + h * 0.38f), Offset(cx + w * 0.18f, topY + h * 0.38f), strokeWidth = h * 0.015f)
    }

    // --- 롯데월드타워 상단 랜턴 & 첨탑 (올림픽공원/뚝섬/남한산성 출사 스팟 구도) ---
    private fun DrawScope.drawLotteTopSpire(cx: Float, bottomY: Float, h: Float) {
        val topY = bottomY - h
        val w = h * 0.95f

        val path = Path().apply {
            // 상단 V자 랜턴 개구부와 양쪽 첨탑
            moveTo(cx - w * 0.28f, topY)
            lineTo(cx - w * 0.10f, topY + h * 0.35f)
            lineTo(cx, topY + h * 0.20f)
            lineTo(cx + w * 0.10f, topY + h * 0.35f)
            lineTo(cx + w * 0.28f, topY)

            // 하단으로 내려오는 상층부 117~123층 외벽
            lineTo(cx + w * 0.44f, bottomY)
            lineTo(cx - w * 0.44f, bottomY)
            close()
        }
        drawPath(path, SilhouetteColor)

        // 최상단 전망대 창문 밴드
        drawLine(Color(0xDDFDE047), Offset(cx - w * 0.32f, topY + h * 0.52f), Offset(cx + w * 0.32f, topY + h * 0.52f), strokeWidth = h * 0.045f)
        drawLine(Color(0xDDFDE047), Offset(cx - w * 0.36f, topY + h * 0.70f), Offset(cx + w * 0.36f, topY + h * 0.70f), strokeWidth = h * 0.04f)
    }

    // --- 4. 롯데월드타워 전체 123층 (원경 60km+ 샷) ---
    private fun DrawScope.drawLotteTower(cx: Float, bottomY: Float, h: Float) {
        val topY = bottomY - h
        val w = h * 0.24f

        val towerPath = Path().apply {
            // 상단 V자 랜턴 개구부
            moveTo(cx - w * 0.04f, topY)
            lineTo(cx - w * 0.02f, topY + h * 0.06f)
            lineTo(cx, topY + h * 0.04f)
            lineTo(cx + w * 0.02f, topY + h * 0.06f)
            lineTo(cx + w * 0.04f, topY)

            // 완만한 유선형 곡면으로 넓어지는 123층 몸통
            quadraticTo(cx + w * 0.15f, topY + h * 0.55f, cx + w * 0.22f, bottomY)
            lineTo(cx - w * 0.22f, bottomY)
            quadraticTo(cx - w * 0.15f, topY + h * 0.55f, cx - w * 0.04f, topY)
            close()
        }
        drawPath(towerPath, SilhouetteColor)

        // 중앙 수직 분할선 (외벽 커튼월 느낌)
        drawLine(Color(0x30FFFBEA), Offset(cx, topY + h * 0.06f), Offset(cx, bottomY), strokeWidth = h * 0.008f)
    }

    // --- 5. 보름달 횡단 여객기 (제트 엔진과 날개 실루엣) ---
    private fun DrawScope.drawJetAirplane(cx: Float, centerY: Float, size: Float) {
        val w = size * 1.15f
        val h = size * 0.85f

        val planePath = Path().apply {
            // 기수 (앞코)
            moveTo(cx + w * 0.5f, centerY)
            // 동체 상단
            quadraticTo(cx + w * 0.2f, centerY - h * 0.09f, cx - w * 0.25f, centerY - h * 0.08f)
            // 수직 꼬리날개
            lineTo(cx - w * 0.45f, centerY - h * 0.42f)
            lineTo(cx - w * 0.40f, centerY - h * 0.42f)
            lineTo(cx - w * 0.32f, centerY - h * 0.06f)
            // 후미
            lineTo(cx - w * 0.50f, centerY)
            // 수평 꼬리날개 아래쪽
            lineTo(cx - w * 0.35f, centerY + h * 0.12f)
            lineTo(cx - w * 0.28f, centerY + h * 0.06f)
            // 후방 동체 하단
            lineTo(cx - w * 0.05f, centerY + h * 0.08f)

            // 주날개 (좌/하단 뷰포인트)
            lineTo(cx - w * 0.25f, centerY + h * 0.45f)
            lineTo(cx - w * 0.12f, centerY + h * 0.45f)
            lineTo(cx + w * 0.15f, centerY + h * 0.08f)

            // 기수 하단
            quadraticTo(cx + w * 0.38f, centerY + h * 0.06f, cx + w * 0.5f, centerY)
            close()
        }
        drawPath(planePath, SilhouetteColor)

        // 반대편 주날개 (상단으로 뻗어나감)
        val upperWing = Path().apply {
            moveTo(cx - w * 0.05f, centerY - h * 0.08f)
            lineTo(cx - w * 0.20f, centerY - h * 0.38f)
            lineTo(cx - w * 0.12f, centerY - h * 0.38f)
            lineTo(cx + w * 0.15f, centerY - h * 0.07f)
            close()
        }
        drawPath(upperWing, SilhouetteColor)

        // 제트 엔진 2기
        drawRoundRect(SilhouetteColor, topLeft = Offset(cx - w * 0.08f, centerY + h * 0.22f), size = Size(w * 0.16f, h * 0.07f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
        drawRoundRect(SilhouetteColor, topLeft = Offset(cx - w * 0.05f, centerY - h * 0.22f), size = Size(w * 0.14f, h * 0.06f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
    }

    // --- 6. 비상하는 독수리 (활공하는 날개깃 실루엣) ---
    private fun DrawScope.drawSoaringEagle(cx: Float, centerY: Float, size: Float) {
        val w = size * 1.35f
        val h = size * 0.75f

        val eaglePath = Path().apply {
            // 머리 & 날카로운 갈고리 부리
            moveTo(cx, centerY - h * 0.32f)
            lineTo(cx + w * 0.05f, centerY - h * 0.27f) // 부리 끝
            lineTo(cx + w * 0.02f, centerY - h * 0.22f) // 턱/목
            lineTo(cx + w * 0.06f, centerY - h * 0.16f) // 우측 가슴

            // 우측 날개 앞전 (위로 솟구치며 뻗어나가는 우아한 곡선)
            quadraticTo(
                cx + w * 0.25f, centerY - h * 0.28f,
                cx + w * 0.48f, centerY - h * 0.24f
            )

            // 우측 날개 끝 칼깃 (Primary feathers - 4단 날개깃 톱니)
            lineTo(cx + w * 0.50f, centerY - h * 0.18f)
            lineTo(cx + w * 0.44f, centerY - h * 0.15f)
            lineTo(cx + w * 0.47f, centerY - h * 0.09f)
            lineTo(cx + w * 0.39f, centerY - h * 0.06f)
            lineTo(cx + w * 0.41f, centerY - h * 0.01f)
            lineTo(cx + w * 0.33f, centerY + h * 0.01f)
            lineTo(cx + w * 0.34f, centerY + h * 0.06f)
            lineTo(cx + w * 0.25f, centerY + h * 0.04f)

            // 우측 날개 후전 곡선 및 몸통 연결
            quadraticTo(
                cx + w * 0.12f, centerY + h * 0.12f,
                cx + w * 0.05f, centerY + h * 0.22f
            )

            // 부채꼴 꼬리깃 (활짝 펼친 형태)
            lineTo(cx + w * 0.13f, centerY + h * 0.38f)
            quadraticTo(
                cx, centerY + h * 0.42f,
                cx - w * 0.13f, centerY + h * 0.38f
            )
            lineTo(cx - w * 0.05f, centerY + h * 0.22f)

            // 좌측 날개 후전 곡선
            quadraticTo(
                cx - w * 0.12f, centerY + h * 0.12f,
                cx - w * 0.25f, centerY + h * 0.04f
            )

            // 좌측 날개 끝 칼깃 (Primary feathers - 4단 대칭 날개깃)
            lineTo(cx - w * 0.34f, centerY + h * 0.06f)
            lineTo(cx - w * 0.33f, centerY + h * 0.01f)
            lineTo(cx - w * 0.41f, centerY - h * 0.01f)
            lineTo(cx - w * 0.39f, centerY - h * 0.06f)
            lineTo(cx - w * 0.47f, centerY - h * 0.09f)
            lineTo(cx - w * 0.44f, centerY - h * 0.15f)
            lineTo(cx - w * 0.50f, centerY - h * 0.18f)
            lineTo(cx - w * 0.48f, centerY - h * 0.24f)

            // 좌측 날개 앞전 곡선 (머리/목 쪽으로 복귀)
            quadraticTo(
                cx - w * 0.25f, centerY - h * 0.28f,
                cx - w * 0.06f, centerY - h * 0.16f
            )
            lineTo(cx - w * 0.02f, centerY - h * 0.22f)
            close()
        }
        drawPath(eaglePath, SilhouetteColor)
    }

    // --- 7. 고층 아파트/빌딩 (스카이라인 실루엣) ---
    private fun DrawScope.drawApartmentBuilding(cx: Float, bottomY: Float, h: Float) {
        val w = h * 0.65f
        val topY = bottomY - h

        val buildingPath = Path().apply {
            moveTo(cx - w * 0.45f, bottomY)
            lineTo(cx - w * 0.45f, topY + h * 0.12f)
            lineTo(cx - w * 0.32f, topY + h * 0.12f)
            // 옥상 계단실 및 헬리패드
            lineTo(cx - w * 0.32f, topY + h * 0.04f)
            lineTo(cx + w * 0.15f, topY + h * 0.04f)
            // 옥상 통신 안테나
            lineTo(cx + w * 0.15f, topY)
            lineTo(cx + w * 0.17f, topY)
            lineTo(cx + w * 0.17f, topY + h * 0.04f)
            lineTo(cx + w * 0.32f, topY + h * 0.04f)
            lineTo(cx + w * 0.32f, topY + h * 0.12f)
            lineTo(cx + w * 0.45f, topY + h * 0.12f)
            lineTo(cx + w * 0.45f, bottomY)
            close()
        }
        drawPath(buildingPath, SilhouetteColor)

        // 불 켜진 창문 몇 개 감성 포인트 (노란 불빛)
        val winW = w * 0.06f
        val winH = h * 0.04f
        val windowColor = Color(0xDDFDE047)
        drawRect(windowColor, topLeft = Offset(cx - w * 0.3f, topY + h * 0.25f), size = Size(winW, winH))
        drawRect(windowColor, topLeft = Offset(cx - w * 0.1f, topY + h * 0.35f), size = Size(winW, winH))
        drawRect(windowColor, topLeft = Offset(cx + w * 0.18f, topY + h * 0.28f), size = Size(winW, winH))
        drawRect(windowColor, topLeft = Offset(cx - w * 0.25f, topY + h * 0.52f), size = Size(winW, winH))
        drawRect(windowColor, topLeft = Offset(cx + w * 0.22f, topY + h * 0.62f), size = Size(winW, winH))
    }
}
