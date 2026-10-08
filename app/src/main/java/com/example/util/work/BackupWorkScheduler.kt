package com.example.util.work

import android.content.Context
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Scheduler for Database Backup WorkManager tasks.
 */
object BackupWorkScheduler {

    /**
     * Enqueues a periodic background backup job (default every 24 hours).
     * Uses ExistingPeriodicWorkPolicy.KEEP so it continues uninterrupted across app restarts.
     */
    fun schedulePeriodicBackup(context: Context, intervalHours: Long = 24) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<DatabaseBackupWorker>(
            intervalHours,
            TimeUnit.HOURS,
            15,
            TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.MINUTES
            )
            .addTag(DatabaseBackupWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DatabaseBackupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    /**
     * Triggers an immediate one-time backup task in background.
     */
    fun triggerImmediateBackup(context: Context): UUID {
        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<DatabaseBackupWorker>()
            .addTag(DatabaseBackupWorker.TAG)
            .addTag("manual_backup")
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
        return oneTimeWorkRequest.id
    }
}
