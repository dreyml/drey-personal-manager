package com.nxuslab.dreymanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.app.Routes
import com.nxuslab.dreymanager.domain.water.WaterPlan
import com.nxuslab.dreymanager.feature.melhor.StoicBody
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** A cada 30 min confere o ritmo da água e avisa só se estiver atrasado (no máximo 1 por hora). */
class WaterReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val water = (applicationContext as NxusApp).water
        val settings = water.settingsNow()
        if (settings != null) {
            val now = LocalDateTime.now()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(LAST, 0L)
            val since = if (last == 0L) null else (System.currentTimeMillis() - last) / 60_000
            val total = water.totalOn(now.toLocalDate())
            WaterPlan.reminderDue(settings, total, now.toLocalTime(), since)?.let { behind ->
                val notice = PlannedNotice("agua", Channel.AGUA, "Hora da água", StoicBody.reminder(behind, now.hour), Routes.MELHOR)
                if (Notifier.show(applicationContext, notice)) prefs.edit().putLong(LAST, System.currentTimeMillis()).apply()
            }
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("NxusLife", "Falha no aviso de água", e)
        Result.success()
    }

    private companion object {
        const val PREFS = "water_reminders"
        const val LAST = "last_notified_at"
    }
}

object WaterReminders {
    private const val WORK = "nxuslife-agua"

    /** Liga ou desliga a checagem periódica conforme o plano. */
    fun sync(context: Context, on: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (on) {
            manager.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WaterReminderWorker>(30, TimeUnit.MINUTES).build(),
            )
        } else {
            manager.cancelUniqueWork(WORK)
        }
    }
}
