package com.nxuslab.dreymanager.data.repo

import com.nxuslab.dreymanager.data.db.WaterDao
import com.nxuslab.dreymanager.data.db.WaterLogEntity
import com.nxuslab.dreymanager.data.db.WaterSettingsEntity
import com.nxuslab.dreymanager.domain.water.WaterSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Água: plano e goles. Separado das finanças; nada aqui mexe no FinanceRepository. */
class WaterRepository(private val dao: WaterDao, private val zone: () -> ZoneId = ZoneId::systemDefault) {

    val settings: Flow<WaterSettings?> = dao.observeSettings().map { it?.toDomain() }

    /** Total bebido por dia, desde [from] (inclusive). */
    fun totalsSince(from: LocalDate): Flow<Map<LocalDate, Int>> =
        dao.observeSince(startOf(from)).map { logs ->
            logs.groupBy { Instant.ofEpochMilli(it.at).atZone(zone()).toLocalDate() }
                .mapValues { (_, list) -> list.sumOf { it.ml } }
        }

    suspend fun settingsNow(): WaterSettings? = dao.settings()?.toDomain()

    suspend fun totalOn(day: LocalDate): Int = dao.sumBetween(startOf(day), startOf(day.plusDays(1)))

    suspend fun save(s: WaterSettings) = dao.upsertSettings(
        WaterSettingsEntity(1, s.goalMl, s.bottleMl, s.startMinute, s.endMinute, s.remindersOn, System.currentTimeMillis()),
    )

    suspend fun drink(ml: Int) {
        if (ml > 0) dao.insert(WaterLogEntity(at = System.currentTimeMillis(), ml = ml))
    }

    /** Apaga o último registro de hoje. */
    suspend fun undoLast(today: LocalDate) = dao.deleteLastSince(startOf(today))

    private fun startOf(day: LocalDate): Long = day.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun WaterSettingsEntity.toDomain() = WaterSettings(goalMl, bottleMl, startMinute, endMinute, remindersOn)
}
