package com.moonaligner.app.core.optics

/**
 * 카메라 센서 규격 정의 (가로 x 세로 크기 mm, 크롭 팩터)
 */
enum class CameraSensor(
    val displayName: String,
    val widthMm: Double,
    val heightMm: Double,
    val cropFactor: Double
) {
    FULL_FRAME("35mm 풀프레임 (1.0x)", 36.0, 24.0, 1.0),
    APS_C_SONY_NIKON_FUJI("APS-C 소니/니콘/후지 (1.5x)", 23.5, 15.6, 1.5),
    APS_C_CANON("APS-C 캐논 (1.6x)", 22.2, 14.8, 1.6),
    MICRO_FOUR_THIRDS("마이크로포서드 M4/3 (2.0x)", 17.3, 13.0, 2.0),
    MEDIUM_FORMAT_FUJI("중형 후지 GFX (0.79x)", 43.8, 32.9, 0.79),
    SMARTPHONE_1_INCH("스마트폰 1인치 센서", 13.2, 8.8, 2.7);

    val diagonalMm: Double
        get() = Math.hypot(widthMm, heightMm)
}
