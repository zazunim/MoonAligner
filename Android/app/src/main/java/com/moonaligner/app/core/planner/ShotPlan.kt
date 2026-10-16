package com.moonaligner.app.core.planner

import android.content.Context
import com.moonaligner.app.core.alignment.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 정렬 플래너 촬영 계획 데이터 모델
 */
data class ShotPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val plannedTimestampEpochMilli: Long,
    val cameraPoint: GeoPoint,
    val targetPoint: GeoPoint,
    val targetHeightMeters: Double,
    val distanceMeters: Double,
    val focalLengthMm: Float = 600f,
    val note: String = ""
)

/**
 * 로컬 영구 저장소 (SharedPreferences + JSON)
 * 오프라인 상태에서도 계획을 저장하고 현장에서 바로 불러올 수 있습니다.
 */
object ShotPlanStorage {
    private const val PREF_NAME = "moonaligner_shot_plans"
    private const val KEY_PLANS = "saved_shot_plans"

    fun loadPlans(context: Context): List<ShotPlan> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PLANS, null) ?: return emptyList()
        val list = mutableListOf<ShotPlan>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ShotPlan(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "촬영 계획"),
                        plannedTimestampEpochMilli = obj.getLong("timestamp"),
                        cameraPoint = GeoPoint(
                            latitudeDeg = obj.getDouble("camLat"),
                            longitudeDeg = obj.getDouble("camLng"),
                            altitudeMeters = obj.optDouble("camAlt", 0.0)
                        ),
                        targetPoint = GeoPoint(
                            latitudeDeg = obj.getDouble("tgtLat"),
                            longitudeDeg = obj.getDouble("tgtLng"),
                            altitudeMeters = obj.optDouble("tgtAlt", 0.0)
                        ),
                        targetHeightMeters = obj.optDouble("tgtHeight", 20.0),
                        distanceMeters = obj.optDouble("dist", 0.0),
                        focalLengthMm = obj.optDouble("focal", 600.0).toFloat(),
                        note = obj.optString("note", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun savePlan(context: Context, plan: ShotPlan) {
        val existing = loadPlans(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == plan.id }
        if (idx >= 0) {
            existing[idx] = plan
        } else {
            existing.add(0, plan) // 최신 계획을 맨 앞에 배치
        }
        saveAll(context, existing)
    }

    fun deletePlan(context: Context, planId: String) {
        val existing = loadPlans(context).filter { it.id != planId }
        saveAll(context, existing)
    }

    private fun saveAll(context: Context, list: List<ShotPlan>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("timestamp", item.plannedTimestampEpochMilli)
                put("camLat", item.cameraPoint.latitudeDeg)
                put("camLng", item.cameraPoint.longitudeDeg)
                put("camAlt", item.cameraPoint.altitudeMeters)
                put("tgtLat", item.targetPoint.latitudeDeg)
                put("tgtLng", item.targetPoint.longitudeDeg)
                put("tgtAlt", item.targetPoint.altitudeMeters)
                put("tgtHeight", item.targetHeightMeters)
                put("dist", item.distanceMeters)
                put("focal", item.focalLengthMm.toDouble())
                put("note", item.note)
            }
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PLANS, arr.toString()).apply()
    }
}
