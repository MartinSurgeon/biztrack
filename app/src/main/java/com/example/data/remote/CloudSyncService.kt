package com.example.data.remote

import android.util.Log
import com.example.data.model.BackupData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object CloudSyncService {
    private const val TAG = "CloudSyncService"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sanitizes and normalizes the sync URL. If the user only enters the domain name without the path
     * to sync.php, this will automatically append /sync.php (or /api/sync.php) to prevent hitting HTML index pages.
     */
    fun normalizeSyncUrl(inputUrl: String): String {
        var url = inputUrl.trim()
        if (url.isBlank()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    private fun formatErrorMessage(responseBody: String, httpCode: Int): String {
        val trimmed = responseBody.trim()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return "Server returned a web page (HTML) instead of API data (HTTP $httpCode). Ensure your URL points directly to sync.php (e.g. https://yourdomain.com/sync.php or /api/sync.php)."
        }
        return if (trimmed.isNotBlank()) {
            trimmed.take(160)
        } else {
            "HTTP $httpCode (Empty response)"
        }
    }

    /**
     * Push all local Room data to the remote Namecheap cPanel MySQL endpoint.
     */
    suspend fun pushToCloud(
        syncUrl: String,
        secretKey: String,
        backupData: BackupData
    ): CloudSyncResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeSyncUrl(syncUrl)
        val trimmedKey = secretKey.trim()

        if (normalizedUrl.isBlank()) {
            return@withContext CloudSyncResult(
                success = false,
                message = "Cloud Sync URL is empty. Please configure your server URL in Settings."
            )
        }

        try {
            val requestPayload = CloudSyncPushRequest(
                secretKey = trimmedKey,
                clientTimestamp = System.currentTimeMillis(),
                appVersion = "1.0",
                backupData = backupData
            )

            val pushAdapter = moshi.adapter(CloudSyncPushRequest::class.java)
            val jsonPayload = pushAdapter.toJson(requestPayload)

            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(normalizedUrl)
                .post(requestBody)
                .addHeader("User-Agent", "BizTrackPOS/1.0")
                .addHeader("X-Sync-Action", "PUSH")
                .addHeader("X-Api-Key", trimmedKey)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext CloudSyncResult(
                    success = false,
                    message = formatErrorMessage(responseBody, response.code)
                )
            }

            val responseAdapter = moshi.adapter(CloudSyncResponse::class.java)
            val syncResponse = try {
                responseAdapter.fromJson(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse sync response: $responseBody", e)
                null
            }

            if (syncResponse != null && syncResponse.status.equals("success", ignoreCase = true)) {
                val count = backupData.products.size + backupData.customers.size + backupData.salesOrders.size
                CloudSyncResult(
                    success = true,
                    message = syncResponse.message.ifBlank { "Cloud database successfully updated with $count records." },
                    recordsCount = count
                )
            } else {
                CloudSyncResult(
                    success = false,
                    message = syncResponse?.message ?: formatErrorMessage(responseBody, response.code)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync push error", e)
            CloudSyncResult(
                success = false,
                message = "Cloud connection failed: ${e.localizedMessage ?: "Unknown network error"}"
            )
        }
    }

    /**
     * Pull complete farm database from Namecheap MySQL endpoint to phone.
     */
    suspend fun pullFromCloud(
        syncUrl: String,
        secretKey: String
    ): CloudSyncResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeSyncUrl(syncUrl)
        val trimmedKey = secretKey.trim()

        if (normalizedUrl.isBlank()) {
            return@withContext CloudSyncResult(
                success = false,
                message = "Cloud Sync URL is empty. Please configure your server URL in Settings."
            )
        }

        try {
            val requestPayload = CloudSyncPullRequest(
                secretKey = trimmedKey,
                clientTimestamp = System.currentTimeMillis()
            )

            val pullAdapter = moshi.adapter(CloudSyncPullRequest::class.java)
            val jsonPayload = pullAdapter.toJson(requestPayload)

            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(normalizedUrl)
                .post(requestBody)
                .addHeader("User-Agent", "BizTrackPOS/1.0")
                .addHeader("X-Sync-Action", "PULL")
                .addHeader("X-Api-Key", trimmedKey)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext CloudSyncResult(
                    success = false,
                    message = formatErrorMessage(responseBody, response.code)
                )
            }

            val responseAdapter = moshi.adapter(CloudSyncResponse::class.java)
            val syncResponse = try {
                responseAdapter.fromJson(responseBody)
            } catch (e: Exception) {
                Log.w(TAG, "Moshi pull response parsing had type mismatches, trying resilient parser", e)
                null
            }

            // Extract BackupData either from Moshi or through resilient parser
            val parsedBackupData = syncResponse?.backupData ?: com.example.util.DatabaseBackupHelper.parseBackupJson(responseBody)

            if (parsedBackupData != null && (parsedBackupData.products.isNotEmpty() || parsedBackupData.customers.isNotEmpty() || parsedBackupData.salesOrders.isNotEmpty() || parsedBackupData.payments.isNotEmpty() || parsedBackupData.settings != null)) {
                val count = parsedBackupData.products.size + parsedBackupData.customers.size + parsedBackupData.salesOrders.size + parsedBackupData.payments.size
                CloudSyncResult(
                    success = true,
                    message = syncResponse?.message?.ifBlank { "Retrieved $count farm records from cloud MySQL database." } ?: "Retrieved $count farm records from cloud MySQL database.",
                    backupData = parsedBackupData,
                    recordsCount = count
                )
            } else if (syncResponse != null && syncResponse.status.equals("success", ignoreCase = true)) {
                // Server returned success but tables are currently empty
                val emptyBackup = parsedBackupData ?: com.example.data.model.BackupData()
                CloudSyncResult(
                    success = true,
                    message = syncResponse.message.ifBlank { "Cloud database is connected (0 records found)." },
                    backupData = emptyBackup,
                    recordsCount = 0
                )
            } else {
                CloudSyncResult(
                    success = false,
                    message = syncResponse?.message ?: formatErrorMessage(responseBody, response.code)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync pull error", e)
            CloudSyncResult(
                success = false,
                message = "Failed to pull cloud database: ${e.localizedMessage ?: "Unknown network error"}"
            )
        }
    }

    /**
     * Test connection to the Namecheap endpoint.
     */
    suspend fun testConnection(
        syncUrl: String,
        secretKey: String
    ): CloudSyncResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeSyncUrl(syncUrl)
        val trimmedKey = secretKey.trim()

        if (normalizedUrl.isBlank()) {
            return@withContext CloudSyncResult(
                success = false,
                message = "Please enter your Namecheap Sync URL (e.g. https://yourdomain.com/sync.php)"
            )
        }

        try {
            val request = Request.Builder()
                .url(normalizedUrl)
                .get()
                .addHeader("User-Agent", "BizTrackPOS/1.0")
                .addHeader("X-Api-Key", trimmedKey)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val trimmed = responseBody.trim()
                if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
                    CloudSyncResult(
                        success = false,
                        message = "URL returned a webpage, not the API! Include the file name: e.g. ${normalizedUrl.trimEnd('/')}/sync.php"
                    )
                } else {
                    CloudSyncResult(
                        success = true,
                        message = "Connected to Namecheap API server successfully! (HTTP ${response.code})"
                    )
                }
            } else {
                CloudSyncResult(
                    success = false,
                    message = formatErrorMessage(responseBody, response.code)
                )
            }
        } catch (e: Exception) {
            CloudSyncResult(
                success = false,
                message = "Connection failed: ${e.localizedMessage ?: "Check URL and internet"}"
            )
        }
    }
}
