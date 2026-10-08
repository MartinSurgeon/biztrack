package com.example.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupData(
    val formatVersion: Int = 1,
    val appName: String = "BizTrack POS",
    val exportedAt: Long = System.currentTimeMillis(),
    val exportedAtFormatted: String = "",
    val businessName: String = "BizTrack Business",
    val settings: AppSettingsEntity? = null,
    val products: List<ProductEntity> = emptyList(),
    val customers: List<CustomerEntity> = emptyList(),
    val salesOrders: List<SaleOrderEntity> = emptyList(),
    val payments: List<PaymentEntity> = emptyList(),
    val inventoryLogs: List<InventoryLogEntity> = emptyList(),
    val summary: BackupSummary = BackupSummary()
)

@JsonClass(generateAdapter = true)
data class BackupSummary(
    val totalProducts: Int = 0,
    val totalCustomers: Int = 0,
    val totalSalesOrders: Int = 0,
    val totalPayments: Int = 0,
    val totalInventoryLogs: Int = 0,
    val totalOutstandingDebt: Double = 0.0,
    val totalRevenue: Double = 0.0
)

data class BackupFileInfo(
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val formattedSize: String,
    val timestamp: Long,
    val formattedDate: String,
    val isAutoBackup: Boolean = false,
    val itemCountSummary: String = ""
)

data class RestoreResult(
    val success: Boolean,
    val message: String,
    val productsCount: Int = 0,
    val customersCount: Int = 0,
    val salesCount: Int = 0,
    val paymentsCount: Int = 0,
    val logsCount: Int = 0,
    val settingsRestored: Boolean = false,
    val backupTimestamp: Long = 0L,
    val error: String? = null
)
