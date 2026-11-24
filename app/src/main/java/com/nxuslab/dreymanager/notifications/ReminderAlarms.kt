package com.nxuslab.dreymanager.notifications

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nxuslab.dreymanager.MainActivity
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.app.Routes
import com.nxuslab.dreymanager.data.repo.ReminderScheduler
import com.nxuslab.dreymanager.domain.notes.ReminderSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val EXTRA_ID = "reminder_id"
private const val ACTION_DONE = "com.nxuslab.dreymanager.REMINDER_DONE"
private const val ACTION_SNOOZE = "com.nxuslab.dreymanager.REMINDER_SNOOZE"
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Lembretes no AlarmManager: exato quando o Android permite, senão aproximado. */
class AlarmReminderScheduler(context: Context) : ReminderScheduler {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override fun schedule(id: Long, atMillis: Long) {
        val pending = firePending(id)
        if (canExact(app)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    override fun cancel(id: Long) = alarms.cancel(firePending(id))

    private fun firePending(id: Long): PendingIntent = PendingIntent.getBroadcast(
        app, id.toInt(),
        Intent(app, ReminderAlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        fun canExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        /** Tela do Android "Alarmes e lembretes" deste app. */
        fun permissionIntent(context: Context): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
            } else null
    }
}

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e("NxusLife", "Falha no lembrete", e)
        } finally {
            pending.finish()
        }
    }
}

/** O alarme tocou: mostra o aviso com Feito e Adiar 1h. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id == 0L) return
        runAsync {
            val notes = (context.applicationContext as NxusApp).notes
            val r = notes.reminder(id) ?: return@runAsync
            showReminder(context, id, r.text, "${r.rule.time} · ${ReminderSchedule.repeatLabel(r.rule)}")
            notes.fired(id)
        }
    }

    @SuppressLint("MissingPermission") // checado por areNotificationsEnabled()
    private fun showReminder(context: Context, id: Long, title: String, text: String) {
        if (!Notifier.enabled(context)) return
        Notifier.createChannels(context)
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java).setData(Uri.parse(Routes.DEEP_LINK_BASE + Routes.NOTAS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(name: String) = PendingIntent.getBroadcast(
            context, (id * 10 + if (name == ACTION_DONE) 1 else 2).toInt(),
            Intent(context, ReminderActionReceiver::class.java).setAction(name).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channel.LEMBRETES.id)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Feito", action(ACTION_DONE))
            .addAction(0, "Adiar 1h", action(ACTION_SNOOZE))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(id), n)
    }
}

/** Botões do aviso. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id == 0L) return
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        runAsync {
            val notes = (context.applicationContext as NxusApp).notes
            when (intent.action) {
                ACTION_DONE -> notes.doneFromNotification(id)
                ACTION_SNOOZE -> notes.snooze(id)
            }
        }
    }
}

/** Depois de reiniciar o celular, os alarmes somem: agenda tudo de novo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runAsync { (context.applicationContext as NxusApp).notes.rescheduleAll() }
    }
}

private fun notificationId(id: Long): Int = ("lembrete-$id").hashCode()
