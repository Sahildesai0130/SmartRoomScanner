package com.cp36.roomscanner.data

import android.content.Context
import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.RoomPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class Project(
    val id: Long,
    val plan: RoomPlan,
    val createdAt: Long,
    val updatedAt: Long,
)

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val json: Json,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()

    suspend fun get(id: Long): Project? = dao.get(id)?.let {
        Project(it.id, json.decodeFromString(RoomPlan.serializer(), it.planJson), it.createdAt, it.updatedAt)
    }

    suspend fun create(plan: RoomPlan): Long {
        val now = System.currentTimeMillis()
        return dao.insert(entityFor(0, plan, now, now))
    }

    suspend fun save(id: Long, plan: RoomPlan, createdAt: Long) {
        dao.update(entityFor(id, plan, createdAt, System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun entityFor(id: Long, plan: RoomPlan, created: Long, updated: Long) = ProjectEntity(
        id = id,
        name = plan.name,
        planJson = json.encodeToString(RoomPlan.serializer(), plan),
        area = Geometry.area(plan.corners),
        perimeter = Geometry.perimeter(plan.corners),
        createdAt = created,
        updatedAt = updated,
    )
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val unit: LengthUnit = LengthUnit.M,
    val autoSquare: Boolean = true,
    val squareTolerance: Double = 10.0,
    val refineWithPca: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/** Settings stored in SharedPreferences and exposed as a StateFlow. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        unit = runCatching { LengthUnit.valueOf(prefs.getString("unit", "M")!!) }.getOrDefault(LengthUnit.M),
        autoSquare = prefs.getBoolean("autoSquare", true),
        squareTolerance = prefs.getFloat("squareTolerance", 10f).toDouble(),
        refineWithPca = prefs.getBoolean("refineWithPca", true),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("unit", s.unit.name)
            .putBoolean("autoSquare", s.autoSquare)
            .putFloat("squareTolerance", s.squareTolerance.toFloat())
            .putBoolean("refineWithPca", s.refineWithPca)
            .putString("theme", s.theme.name)
            .apply()
        _settings.value = s
    }
}
