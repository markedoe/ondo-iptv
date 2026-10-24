package com.markedoe.ondo.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.markedoe.ondo.R
import java.util.Date
import java.util.concurrent.TimeUnit

/** Delivers programme reminders as notifications, [LEAD_TIME] before the programme starts. */
interface ReminderScheduler {
    fun schedule(reminder: Reminder, now: Long)

    fun cancel(reminder: Reminder)

    companion object {
        const val LEAD_TIME = 5 * 60_000L
    }
}

/** Tests: nothing is delivered. */
object NoReminderScheduler : ReminderScheduler {
    override fun schedule(reminder: Reminder, now: Long) = Unit

    override fun cancel(reminder: Reminder) = Unit
}

/** WorkManager keeps the reminders across app restarts and device reboots. */
class WorkManagerReminderScheduler(private val context: Context) : ReminderScheduler {
    override fun schedule(reminder: Reminder, now: Long) {
        if (reminder.start <= now) return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay((reminder.start - ReminderScheduler.LEAD_TIME - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            // WorkManager refuses data over 10 KB: a guide's title can be far longer (a notification shows less anyway).
            .setInputData(workDataOf(KEY_TITLE to reminder.title.take(MAX_TEXT), KEY_CHANNEL to reminder.channelName.take(MAX_TEXT), KEY_START to reminder.start))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(reminder), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(reminder: Reminder) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(reminder))
    }

    private fun workName(reminder: Reminder) = "reminder|${reminder.key}"

    internal companion object {
        const val KEY_TITLE = "title"
        const val KEY_CHANNEL = "channel"
        const val KEY_START = "start"

        /** Characters of the title and of the channel's name kept: both fit in WorkManager's 10 KB, 3 bytes a character. */
        const val MAX_TEXT = 1_000
    }
}

/** Shows the reminder: "Title — Starts at 21:00 on Channel"; a tap opens the app. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val title = inputData.getString(WorkManagerReminderScheduler.KEY_TITLE) ?: return Result.success()
        val channel = inputData.getString(WorkManagerReminderScheduler.KEY_CHANNEL).orEmpty()
        val start = inputData.getLong(WorkManagerReminderScheduler.KEY_START, 0)
        ReminderNotifications.show(applicationContext, id.hashCode(), title, channel, start)
        return Result.success()
    }
}

object ReminderNotifications {
    private const val CHANNEL_ID = "reminders"

    /** Android 13+ asks the viewer first; before, notifications are allowed unless turned off. */
    fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, notificationId: Int, title: String, channelName: String, start: Long) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.reminders))
                .build(),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val time = DateFormat.getTimeFormat(context).format(Date(start))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_body, time, channelName))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Notifications were turned off between the check and the call.
        }
    }
}
