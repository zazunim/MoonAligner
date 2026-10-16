package com.moonaligner.app.core.alignment

data class GeoPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double = 0.0 // 해발 고도 (미터)
)
