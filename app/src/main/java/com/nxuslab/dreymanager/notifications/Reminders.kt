package com.nxuslab.dreymanager.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nxuslab.dreymanager.MainActivity
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.app.Routes
import com.nxuslab.dreymanager.domain.model.WishStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private const val TAG = "NxusLife"

object Notifier {
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        Channel.entries.forEach { manager.createNotificationChannel(NotificationChannel(it.id, it.label, NotificationManager.IMPORTANCE_DEFAULT)) }
    }

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Mostra o aviso; devolve false se as notificações estão desligadas (o app avisa em Mais). */
    @SuppressLint("MissingPermission") // checado por areNotificationsEnabled()
    fun show(context: Context, notice: PlannedNotice): Boolean {
        if (!enabled(context)) return false
        createChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.parse(Routes.DEEP_LINK_BASE + notice.route))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(context, notice.key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notice.key.hashCode(), notification)
        return true
    }
}

/** Lembrete da noite, no horário escolhido nos ajustes. */
class EveningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runSafely {
        val snapshot = (applicationContext as NxusApp).repository.snapshot.first()
        ReminderPlanner.evening(snapshot, LocalDate.now())?.let { Notifier.show(applicationContext, it) }
    }
}

/** Avisos das 8h: vencimentos de amanhã, falta prevista, desejos prontos e resumo da semana. */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runSafely {
        val repository = (applicationContext as NxusApp).repository
        val snapshot = repository.snapshot.first()
        val today = LocalDate.now()
        ReminderPlanner.morning(snapshot, today).forEach { Notifier.show(applicationContext, it) }
        // Marca como pronto para não avisar de novo amanhã.
        ReminderPlanner.newlyReadyWishes(snapshot, today).forEach { repository.saveWish(it.copy(status = WishStatus.PRONTO)).getOrThrow() }
    }
}

private suspend fun CoroutineWorker.runSafely(block: suspend () -> Unit): androidx.work.ListenableWorker.Result = try {
    block()
    androidx.work.ListenableWorker.Result.success()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(TAG, "Falha ao preparar notificações", e)
    androidx.work.ListenableWorker.Result.retry()
}

object Reminders {
    private const val EVENING = "nxuslife-noite"
    private const val MORNING = "nxuslife-manha"
    private val MORNING_TIME: LocalTime = LocalTime.of(8, 0)

    /**
     * Agenda os dois avisos diários. [replace] = true quando o horário mudou (reinicia a contagem);
     * na abertura do app usa false para manter o agendamento que já existe.
     */
    fun schedule(context: Context, reminderMinutes: Int, replace: Boolean) {
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        val manager = WorkManager.getInstance(context)
        val evening = LocalTime.of(reminderMinutes / 60 % 24, reminderMinutes % 60)
        manager.enqueueUniquePeriodicWork(
            EVENING, policy,
            PeriodicWorkRequestBuilder<EveningWorker>(1, TimeUnit.DAYS).setInitialDelay(delayUntil(evening), TimeUnit.MILLISECONDS).build(),
        )
        manager.enqueueUniquePeriodicWork(
            MORNING, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MorningWorker>(1, TimeUnit.DAYS).setInitialDelay(delayUntil(MORNING_TIME), TimeUnit.MILLISECONDS).build(),
        )
    }

    /** Milissegundos até a próxima ocorrência de [time] (hoje, se ainda não passou; senão amanhã). */
    fun delayUntil(time: LocalTime, now: LocalDateTime = LocalDateTime.now()): Long {
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
