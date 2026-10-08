package com.example.util.work

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Helper to enqueue and manage background SMS jobs via WorkManager.
 */
object SmsWorkScheduler {

    /**
     * Enqueues an SMS for reliable delivery with network constraints and exponential backoff retry.
     */
    fun enqueueSms(
        context: Context,
        recipientPhone: String,
        messageText: String,
        senderId: String = "",
        apiKey: String = "",
        orderId: Long = 0L,
        customerName: String = "",
        smsType: String = "Purchase Receipt"
    ): UUID {
        val inputData = workDataOf(
            SmsOnlineGhWorker.KEY_PHONE to recipientPhone,
            SmsOnlineGhWorker.KEY_MESSAGE to messageText,
            SmsOnlineGhWorker.KEY_SENDER_ID to senderId,
            SmsOnlineGhWorker.KEY_API_KEY to apiKey,
            SmsOnlineGhWorker.KEY_ORDER_ID to orderId,
            SmsOnlineGhWorker.KEY_CUSTOMER_NAME to customerName,
            SmsOnlineGhWorker.KEY_SMS_TYPE to smsType
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SmsOnlineGhWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag(SmsOnlineGhWorker.TAG_SMS_WORK)
            .addTag("order_$orderId")
            .addTag("phone_$recipientPhone")
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        return workRequest.id
    }

    /**
     * Observe queued or running SMS jobs.
     */
    fun observeSmsWork(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(SmsOnlineGhWorker.TAG_SMS_WORK)
    }

    /**
     * Count active/pending SMS jobs.
     */
    fun observeActiveSmsCount(context: Context): Flow<Int> {
        return observeSmsWork(context).map { workInfos ->
            workInfos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
    }
}
