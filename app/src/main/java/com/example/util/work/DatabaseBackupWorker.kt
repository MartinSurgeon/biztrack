package com.example.util.work

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.local.AppDatabase
import com.example.util.DatabaseBackupHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic WorkManager Worker that exports the Room SQLite Database to JSON on phone storage.
 * Runs in the background to ensure user's sales, debtor balances, catalog, and settings are preserved.
 */
class DatabaseBackupWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "DatabaseBackupWorker"
        const val UNIQUE_WORK_NAME = "naadriel_farm_periodic_backup"
        const val KEY_RESULT_FILE_PATH = "result_file_path"
        const val KEY_RESULT_ITEM_COUNT = "result_item_count"
        const val KEY_RESULT_TIMESTAMP = "result_timestamp"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting periodic database auto-backup (Attempt: $runAttemptCount)")

        try {
            val database = AppDatabase.getDatabase(appContext)
            val (backupFile, backupData) = DatabaseBackupHelper.exportDatabaseToJson(
                context = appContext,
                database = database,
                isAutoBackup = true
            )

            val totalItems = backupData.products.size + backupData.customers.size +
                    backupData.salesOrders.size + backupData.payments.size

            Log.i(TAG, "Periodic database auto-backup completed successfully. File: ${backupFile.name} ($totalItems records)")

            val outputData = workDataOf(
                KEY_RESULT_FILE_PATH to backupFile.absolutePath,
                KEY_RESULT_ITEM_COUNT to totalItems,
                KEY_RESULT_TIMESTAMP to backupData.exportedAt
            )

            Result.success(outputData)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing periodic database auto-backup", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to (e.localizedMessage ?: "Backup failed")))
            }
        }
    }
}
