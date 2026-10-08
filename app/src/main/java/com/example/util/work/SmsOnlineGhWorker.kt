package com.example.util.work

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.local.AppDatabase
import com.example.data.remote.SmsSendResult
import com.example.data.remote.SmsService
import com.example.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reliable Background Worker for sending SMS via SMSOnlineGH v5.
 * Features:
 * - Constraint-aware execution (requires NetworkType.CONNECTED)
 * - Automatic exponential backoff retries on network failures
 * - Database synchronization (updates order SMS timestamp)
 * - System notification updates
 */
class SmsOnlineGhWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SmsOnlineGhWorker"
        const val KEY_PHONE = "key_recipient_phone"
        const val KEY_MESSAGE = "key_message_text"
        const val KEY_SENDER_ID = "key_sender_id"
        const val KEY_API_KEY = "key_api_key"
        const val KEY_ORDER_ID = "key_order_id"
        const val KEY_CUSTOMER_NAME = "key_customer_name"
        const val KEY_SMS_TYPE = "key_sms_type"
        const val TAG_SMS_WORK = "tag_sms_online_gh"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val phone = inputData.getString(KEY_PHONE) ?: ""
        val message = inputData.getString(KEY_MESSAGE) ?: ""
        var apiKey = inputData.getString(KEY_API_KEY) ?: ""
        var senderId = inputData.getString(KEY_SENDER_ID) ?: ""
        val orderId = inputData.getLong(KEY_ORDER_ID, 0L)
        val customerName = inputData.getString(KEY_CUSTOMER_NAME) ?: "Customer"
        val smsType = inputData.getString(KEY_SMS_TYPE) ?: "Receipt"

        Log.d(TAG, "Starting SMS Worker for $customerName ($phone) - Attempt: $runAttemptCount")

        if (phone.isBlank() || message.isBlank()) {
            Log.e(TAG, "Missing phone or message body. Aborting work.")
            return@withContext Result.failure(workDataOf("error" to "Empty recipient phone or message"))
        }

        val database = AppDatabase.getDatabase(appContext)

        // Fallback to database settings if API key or Sender ID not passed
        if (apiKey.isBlank() || senderId.isBlank()) {
            val dbSettings = database.appSettingsDao().getSettingsDirect()
            if (dbSettings != null) {
                if (apiKey.isBlank()) apiKey = dbSettings.smsApiKey
                if (senderId.isBlank()) senderId = dbSettings.smsSenderId
            }
        }

        if (apiKey.isBlank()) {
            Log.w(TAG, "No SMSOnlineGH API key configured.")
            NotificationHelper.showSmsStatusNotification(
                context = appContext,
                title = "SMS Not Sent: API Key Missing",
                message = "Configure SMSOnlineGH API key in Settings to send automated SMS to $customerName.",
                isSuccess = false
            )
            return@withContext Result.failure(workDataOf("error" to "API Key not configured in Settings"))
        }

        try {
            val sendResult = SmsService.sendSmsOnlineGh(
                apiKey = apiKey,
                senderId = senderId,
                recipientPhone = phone,
                message = message
            )

            when (sendResult) {
                is SmsSendResult.Success -> {
                    Log.i(TAG, "SMS successfully sent to $phone via SMSOnlineGH! Batch: ${sendResult.batchId}")

                    // Update database timestamp if tied to a sale order
                    if (orderId > 0L) {
                        val order = database.saleOrderDao().getOrderById(orderId)
                        if (order != null) {
                            database.saleOrderDao().updateOrder(
                                order.copy(
                                    smsSentCount = order.smsSentCount + 1,
                                    lastSmsTimestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }

                    // Show success status notification
                    NotificationHelper.showSmsStatusNotification(
                        context = appContext,
                        title = "SMS Delivered to $customerName",
                        message = "$smsType delivered via SMSOnlineGH (${sendResult.responseMessage})",
                        isSuccess = true
                    )

                    Result.success(
                        workDataOf(
                            "batch_id" to (sendResult.batchId ?: ""),
                            "message" to sendResult.responseMessage
                        )
                    )
                }

                is SmsSendResult.Failure -> {
                    Log.w(TAG, "SMS failed for $phone: ${sendResult.errorMessage}. CanRetry: ${sendResult.canFallbackToNative}")

                    // If it's a network/connectivity failure and we haven't exceeded 3 attempts, retry with exponential backoff!
                    val isNetworkError = sendResult.errorMessage.contains("Network", ignoreCase = true) ||
                            sendResult.errorMessage.contains("Connection", ignoreCase = true) ||
                            sendResult.errorMessage.contains("timeout", ignoreCase = true) ||
                            sendResult.errorMessage.contains("502", ignoreCase = true) ||
                            sendResult.errorMessage.contains("503", ignoreCase = true)

                    if (isNetworkError && runAttemptCount < 3) {
                        Log.w(TAG, "Scheduling WorkManager retry for SMS to $phone (Attempt $runAttemptCount of 3)...")
                        Result.retry()
                    } else {
                        // Permanent failure or max retries exceeded
                        NotificationHelper.showSmsStatusNotification(
                            context = appContext,
                            title = "SMS Delivery Issue",
                            message = "Could not deliver to $customerName: ${sendResult.errorMessage}",
                            isSuccess = false
                        )
                        Result.failure(workDataOf("error" to sendResult.errorMessage))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network exception in SmsOnlineGhWorker", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                NotificationHelper.showSmsStatusNotification(
                    context = appContext,
                    title = "SMS Delivery Failed",
                    message = "Network error delivering SMS to $customerName after multiple retries.",
                    isSuccess = false
                )
                Result.failure(workDataOf("error" to (e.localizedMessage ?: "Network error")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception in SmsOnlineGhWorker", e)
            Result.failure(workDataOf("error" to (e.localizedMessage ?: "Unexpected error")))
        }
    }
}
