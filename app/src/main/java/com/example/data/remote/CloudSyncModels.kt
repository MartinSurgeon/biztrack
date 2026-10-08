package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CloudSyncPushRequest(
    val action: String = "PUSH",
    val secretKey: String,
    val clientTimestamp: Long = System.currentTimeMillis(),
    val appVersion: String = "1.0",
    val backupData: com.example.data.model.BackupData
)

@JsonClass(generateAdapter = true)
data class CloudSyncPullRequest(
    val action: String = "PULL",
    val secretKey: String,
    val clientTimestamp: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class CloudSyncResponse(
    val status: String = "error", // "success" or "error"
    val message: String = "",
    val serverTimestamp: Long = 0L,
    @Json(name = "backupData")
    val backupData: com.example.data.model.BackupData? = null,
    val summary: CloudSyncSummary? = null
)

@JsonClass(generateAdapter = true)
data class CloudSyncSummary(
    val totalProducts: Int = 0,
    val totalCustomers: Int = 0,
    val totalSalesOrders: Int = 0,
    val totalPayments: Int = 0,
    val totalInventoryLogs: Int = 0,
    val serverDatabaseVersion: String = "1.0"
)

data class CloudSyncResult(
    val success: Boolean,
    val message: String,
    val backupData: com.example.data.model.BackupData? = null,
    val recordsCount: Int = 0
)
