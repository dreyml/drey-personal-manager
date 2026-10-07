package com.nxuslab.dreymanager.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nxuslab.dreymanager.R
import com.nxuslab.dreymanager.data.PersonalDatabase
import kotlinx.coroutines.flow.first

class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val count = PersonalDatabase.get(applicationContext).taskDao().observeAll()
            .first()
            .count { !it.completed }
        if (count > 0) NotificationHelper.show(applicationContext, count)
        return Result.success()
    }
}

object NotificationHelper {
    private const val CHANNEL_ID = "drey_reminders"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Lembretes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tarefas e lembretes do Drey Manager"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun show(context: Context, pendingCount: Int) {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Drey Manager")
            .setContentText("Você tem $pendingCount tarefa(s) pendente(s).")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1001, notification)
    }
}
