package com.markedoe.ondo.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Programme reminders handed to WorkManager, which delivers them even when Ondo is not running. */
@RunWith(AndroidJUnit4::class)
class RemindersTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @Before
    fun startWorkManager() = WorkManager.initialize(context, Configuration.Builder().build())

    /**
     * A guide can give a programme a title of several thousand characters (a description in the wrong field): what a
     * reminder carries to WorkManager is limited to 10 KB, beyond which setting it stopped the app.
     */
    @Test
    fun aReminderForAProgrammeWithAVeryLongTitleIsSet() {
        val now = 1_760_000_000_000L
        val reminders = listOf(
            Reminder("p1", "c1", "Ondo News", "Breaking news ".repeat(1_000), start = now + 3_600_000, end = now + 7_200_000),
            // Three bytes a character once serialized, in the title and the channel's name.
            Reminder("p1", "c2", "新闻频道".repeat(1_000), "晚间新闻".repeat(1_000), start = now + 3_600_000, end = now + 7_200_000),
        )
        for (reminder in reminders) {
            WorkManagerReminderScheduler(context).schedule(reminder, now)
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("reminder|${reminder.key}").get()
            assertEquals(listOf(WorkInfo.State.ENQUEUED), work.map { it.state })
        }
    }
}
