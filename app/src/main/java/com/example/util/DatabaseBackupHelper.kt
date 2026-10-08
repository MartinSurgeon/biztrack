package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseBackupHelper {

    private const val TAG = "DatabaseBackupHelper"
    private const val PREFS_BACKUP = "farm_database_backups"
    private const val PREF_LAST_BACKUP_TIME = "last_backup_time"
    private const val PREF_LAST_BACKUP_STATUS = "last_backup_status"
    private const val PREF_LAST_BACKUP_COUNT = "last_backup_item_count"
    private const val PREF_LAST_BACKUP_PATH = "last_backup_file_path"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val backupAdapter = moshi.adapter(BackupData::class.java).indent("  ")

    /**
     * Reads all tables directly from Room and constructs a BackupData object in memory.
     */
    suspend fun createBackupData(database: AppDatabase): BackupData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dateFormatted = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(now))

        val settings = database.appSettingsDao().getSettingsDirect()
        val products = database.productDao().getAllProductsDirect()
        val customers = database.customerDao().getAllCustomersDirect()
        val salesOrders = database.saleOrderDao().getAllOrdersDirect()
        val payments = database.paymentDao().getAllPaymentsDirect()
        val inventoryLogs = database.inventoryLogDao().getAllLogsDirect()

        val totalDebt = customers.sumOf { it.currentBalance }
        val totalRevenue = salesOrders.sumOf { it.totalAmount }

        val summary = BackupSummary(
            totalProducts = products.size,
            totalCustomers = customers.size,
            totalSalesOrders = salesOrders.size,
            totalPayments = payments.size,
            totalInventoryLogs = inventoryLogs.size,
            totalOutstandingDebt = totalDebt,
            totalRevenue = totalRevenue
        )

        BackupData(
            formatVersion = 1,
            appName = "BizTrack POS",
            exportedAt = now,
            exportedAtFormatted = dateFormatted,
            businessName = settings?.businessName ?: "BizTrack Business",
            settings = settings,
            products = products,
            customers = customers,
            salesOrders = salesOrders,
            payments = payments,
            inventoryLogs = inventoryLogs,
            summary = summary
        )
    }

    /**
     * Reads all tables from Room and serializes them into a timestamped JSON file.
     * Stored in both external app files directory and internal files directory.
     */
    suspend fun exportDatabaseToJson(
        context: Context,
        database: AppDatabase,
        isAutoBackup: Boolean = false
    ): Pair<File, BackupData> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fileDateSuffix = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(now))

        val backupData = createBackupData(database)
        val jsonString = backupAdapter.toJson(backupData)

        // 2. Prepare target directories (External storage & Internal storage)
        val extDir = try { context.getExternalFilesDir(null) } catch (e: Exception) { null }
        val externalBackupDir = if (extDir != null) File(extDir, "backups").apply { mkdirs() } else null
        val internalBackupDir = File(context.filesDir, "backups").apply { mkdirs() }

        val prefix = if (isAutoBackup) "naadriel_autobackup" else "naadriel_farm_backup"
        val timestampedFileName = "${prefix}_${fileDateSuffix}.json"
        val latestFileName = "naadriel_farm_backup_latest.json"

        // Save to internal storage (guaranteed)
        val internalTargetFile = File(internalBackupDir, timestampedFileName)
        internalTargetFile.writeText(jsonString)
        val internalLatestFile = File(internalBackupDir, latestFileName)
        internalLatestFile.writeText(jsonString)

        // Save to external storage if accessible
        val targetFile = if (externalBackupDir != null && externalBackupDir.exists()) {
            val extTarget = File(externalBackupDir, timestampedFileName)
            extTarget.writeText(jsonString)
            val extLatest = File(externalBackupDir, latestFileName)
            extLatest.writeText(jsonString)
            pruneOldBackups(externalBackupDir, maxKeep = 15)
            extTarget
        } else {
            internalTargetFile
        }

        pruneOldBackups(internalBackupDir, maxKeep = 15)

        // 3. Save status in SharedPreferences
        val totalItems = backupData.products.size + backupData.customers.size + backupData.salesOrders.size + backupData.payments.size
        saveBackupMetadata(
            context = context,
            timestamp = now,
            status = "Success • $totalItems items saved",
            itemCount = totalItems,
            filePath = targetFile.absolutePath
        )

        Log.d(TAG, "Database exported successfully to ${targetFile.absolutePath} ($totalItems records)")
        Pair(targetFile, backupData)
    }

    /**
     * Parse backup JSON string safely with automatic resilient fallback.
     */
    fun parseBackupJson(jsonString: String): BackupData? {
        val trimmed = jsonString.trim()
        if (trimmed.isBlank()) return null

        // 1. Try standard Moshi adapter
        try {
            val parsed = backupAdapter.fromJson(trimmed)
            if (parsed != null && (parsed.products.isNotEmpty() || parsed.customers.isNotEmpty() || parsed.salesOrders.isNotEmpty() || parsed.settings != null)) {
                return parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Moshi parsing failed, falling back to resilient JSONObject parser", e)
        }

        // 2. Resilient fallback using org.json.JSONObject to survive type mismatches / nulls
        return try {
            val root = org.json.JSONObject(trimmed)
            val dataObj = if (root.has("backupData") && !root.isNull("backupData")) {
                root.getJSONObject("backupData")
            } else {
                root
            }

            val products = mutableListOf<ProductEntity>()
            if (dataObj.has("products") && !dataObj.isNull("products")) {
                val arr = dataObj.getJSONArray("products")
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    products.add(
                        ProductEntity(
                            id = p.optLong("id", 0L),
                            name = p.optString("name", "Product"),
                            category = p.optString("category", "OTHER"),
                            unit = p.optString("unit", "unit"),
                            unitPrice = if (p.has("unitPrice")) p.optDouble("unitPrice", 0.0) else p.optDouble("price", 0.0),
                            costPrice = p.optDouble("costPrice", 0.0),
                            stockQuantity = p.optDouble("stockQuantity", 0.0),
                            minStockThreshold = p.optDouble("minStockThreshold", 5.0),
                            inStock = p.optBoolean("inStock", p.optDouble("stockQuantity", 0.0) > 0),
                            description = p.optString("description", ""),
                            imageUri = p.optString("imageUri", ""),
                            lastRestockedAt = p.optLong("lastRestockedAt", System.currentTimeMillis())
                        )
                    )
                }
            }

            val customers = mutableListOf<CustomerEntity>()
            if (dataObj.has("customers") && !dataObj.isNull("customers")) {
                val arr = dataObj.getJSONArray("customers")
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val debt = when {
                        c.has("currentBalance") && !c.isNull("currentBalance") && c.optDouble("currentBalance", 0.0) > 0.001 -> c.optDouble("currentBalance", 0.0)
                        c.has("totalDebt") && !c.isNull("totalDebt") && c.optDouble("totalDebt", 0.0) > 0.001 -> c.optDouble("totalDebt", 0.0)
                        c.has("currentBalance") && !c.isNull("currentBalance") -> c.optDouble("currentBalance", 0.0)
                        else -> c.optDouble("totalDebt", 0.0)
                    }
                    val purchases = when {
                        c.has("totalPurchases") && !c.isNull("totalPurchases") && c.optDouble("totalPurchases", 0.0) > 0.001 -> c.optDouble("totalPurchases", 0.0)
                        c.has("totalPurchased") && !c.isNull("totalPurchased") && c.optDouble("totalPurchased", 0.0) > 0.001 -> c.optDouble("totalPurchased", 0.0)
                        c.has("totalPurchases") && !c.isNull("totalPurchases") -> c.optDouble("totalPurchases", 0.0)
                        else -> c.optDouble("totalPurchased", 0.0)
                    }
                    customers.add(
                        CustomerEntity(
                            id = c.optLong("id", 0L),
                            name = c.optString("name", "Customer"),
                            phone = c.optString("phone", ""),
                            whatsapp = c.optString("whatsapp", ""),
                            address = c.optString("address", ""),
                            notes = c.optString("notes", ""),
                            totalPurchases = purchases,
                            totalPaid = c.optDouble("totalPaid", 0.0),
                            currentBalance = debt,
                            createdAt = if (c.has("createdAt")) c.optLong("createdAt", System.currentTimeMillis()) else c.optLong("lastTransactionDate", System.currentTimeMillis())
                        )
                    )
                }
            }

            val salesOrders = mutableListOf<SaleOrderEntity>()
            if (dataObj.has("salesOrders") && !dataObj.isNull("salesOrders")) {
                val arr = dataObj.getJSONArray("salesOrders")
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val id = s.optLong("id", 0L)
                    val rawLastSms = if (s.has("lastSmsTimestamp") && !s.isNull("lastSmsTimestamp")) s.optLong("lastSmsTimestamp") else null
                    val balance = when {
                        s.has("balanceDue") && !s.isNull("balanceDue") && s.optDouble("balanceDue", 0.0) > 0.001 -> s.optDouble("balanceDue", 0.0)
                        s.has("debtAmount") && !s.isNull("debtAmount") && s.optDouble("debtAmount", 0.0) > 0.001 -> s.optDouble("debtAmount", 0.0)
                        s.has("balanceDue") && !s.isNull("balanceDue") -> s.optDouble("balanceDue", 0.0)
                        else -> s.optDouble("debtAmount", 0.0)
                    }
                    salesOrders.add(
                        SaleOrderEntity(
                            id = id,
                            invoiceNumber = s.optString("invoiceNumber", "INV-$id"),
                            customerId = s.optLong("customerId", 0L),
                            customerName = s.optString("customerName", "Walk-in Customer"),
                            customerPhone = s.optString("customerPhone", ""),
                            customerWhatsapp = s.optString("customerWhatsapp", ""),
                            itemsJson = s.optString("itemsJson", "[]"),
                            totalAmount = s.optDouble("totalAmount", 0.0),
                            discountAmount = s.optDouble("discountAmount", 0.0),
                            amountPaid = s.optDouble("amountPaid", 0.0),
                            balanceDue = balance,
                            paymentStatus = s.optString("paymentStatus", "PAID"),
                            paymentMethod = s.optString("paymentMethod", "CASH"),
                            notes = s.optString("notes", ""),
                            timestamp = s.optLong("timestamp", System.currentTimeMillis()),
                            smsSentCount = s.optInt("smsSentCount", 0),
                            lastSmsTimestamp = rawLastSms
                        )
                    )
                }
            }

            val payments = mutableListOf<PaymentEntity>()
            if (dataObj.has("payments") && !dataObj.isNull("payments")) {
                val arr = dataObj.getJSONArray("payments")
                for (i in 0 until arr.length()) {
                    val pm = arr.getJSONObject(i)
                    val saleOrderId = if (pm.has("saleOrderId") && !pm.isNull("saleOrderId")) pm.optLong("saleOrderId") else null
                    payments.add(
                        PaymentEntity(
                            id = pm.optLong("id", 0L),
                            customerId = pm.optLong("customerId", 0L),
                            customerName = pm.optString("customerName", ""),
                            saleOrderId = saleOrderId,
                            amount = pm.optDouble("amount", 0.0),
                            paymentMethod = pm.optString("paymentMethod", "CASH"),
                            notes = pm.optString("notes", ""),
                            timestamp = pm.optLong("timestamp", System.currentTimeMillis()),
                            balanceAfterPayment = pm.optDouble("balanceAfterPayment", 0.0)
                        )
                    )
                }
            }

            val logs = mutableListOf<InventoryLogEntity>()
            if (dataObj.has("inventoryLogs") && !dataObj.isNull("inventoryLogs")) {
                val arr = dataObj.getJSONArray("inventoryLogs")
                for (i in 0 until arr.length()) {
                    val l = arr.getJSONObject(i)
                    logs.add(
                        InventoryLogEntity(
                            id = l.optLong("id", 0L),
                            productId = l.optLong("productId", 0L),
                            productName = l.optString("productName", ""),
                            changeType = l.optString("changeType", "ADJUSTMENT"),
                            quantityChanged = l.optDouble("quantityChanged", 0.0),
                            quantityAfter = l.optDouble("quantityAfter", 0.0),
                            unit = l.optString("unit", "unit"),
                            notes = l.optString("notes", ""),
                            timestamp = l.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }

            val settingsEntity = if (dataObj.has("settings") && !dataObj.isNull("settings")) {
                val st = dataObj.getJSONObject("settings")
                AppSettingsEntity(
                    id = st.optInt("id", 1),
                    businessName = st.optString("businessName", "BizTrack Business"),
                    businessTagline = st.optString("businessTagline", "Quality products & reliable service"),
                    businessPhone = st.optString("businessPhone", "024 000 0000"),
                    businessLocation = st.optString("businessLocation", "Ghana"),
                    momoPaymentDetails = st.optString("momoPaymentDetails", ""),
                    smsApiKey = st.optString("smsApiKey", ""),
                    smsSenderId = st.optString("smsSenderId", "BizTrack"),
                    smsAutoSendOnSale = st.optBoolean("smsAutoSendOnSale", true),
                    smsAutoSendOnPayment = st.optBoolean("smsAutoSendOnPayment", true),
                    currencySymbol = st.optString("currencySymbol", "GH₵"),
                    cloudSyncUrl = st.optString("cloudSyncUrl", ""),
                    cloudSyncSecretKey = st.optString("cloudSyncSecretKey", ""),
                    cloudAutoSyncOnSale = st.optBoolean("cloudAutoSyncOnSale", false),
                    lastCloudSyncTime = st.optLong("lastCloudSyncTime", 0L),
                    lastCloudSyncStatus = st.optString("lastCloudSyncStatus", "Synced")
                )
            } else {
                null
            }

            BackupData(
                formatVersion = dataObj.optInt("formatVersion", 1),
                appName = dataObj.optString("appName", "BizTrack POS"),
                exportedAt = dataObj.optLong("exportedAt", System.currentTimeMillis()),
                exportedAtFormatted = dataObj.optString("exportedAtFormatted", ""),
                businessName = dataObj.optString("businessName", "BizTrack Business"),
                settings = settingsEntity,
                products = products,
                customers = customers,
                salesOrders = salesOrders,
                payments = payments,
                inventoryLogs = logs,
                summary = BackupSummary(
                    totalProducts = products.size,
                    totalCustomers = customers.size,
                    totalSalesOrders = salesOrders.size,
                    totalPayments = payments.size,
                    totalInventoryLogs = logs.size,
                    totalOutstandingDebt = customers.sumOf { it.currentBalance },
                    totalRevenue = salesOrders.sumOf { it.totalAmount }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Resilient parser also failed on JSON", e)
            null
        }
    }

    /**
     * Restores full database from a parsed BackupData entity using Room transaction.
     */
    suspend fun restoreDatabase(
        database: AppDatabase,
        backupData: BackupData
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                // 1. Settings (Safely merge existing phone cloud/SMS config)
                backupData.settings?.let { newSettings ->
                    val existing = database.appSettingsDao().getSettingsDirect()
                    val merged = if (existing != null) {
                        newSettings.copy(
                            cloudSyncUrl = newSettings.cloudSyncUrl.ifBlank { existing.cloudSyncUrl },
                            cloudSyncSecretKey = newSettings.cloudSyncSecretKey.ifBlank { existing.cloudSyncSecretKey },
                            smsApiKey = newSettings.smsApiKey.ifBlank { existing.smsApiKey },
                            smsSenderId = newSettings.smsSenderId.ifBlank { existing.smsSenderId }
                        )
                    } else {
                        newSettings
                    }
                    database.appSettingsDao().saveSettings(merged)
                }

                // 2. Products
                if (backupData.products.isNotEmpty()) {
                    database.productDao().insertAll(backupData.products)
                }

                // 3. Customers
                if (backupData.customers.isNotEmpty()) {
                    database.customerDao().insertAll(backupData.customers)
                }

                // 4. Sales Orders
                if (backupData.salesOrders.isNotEmpty()) {
                    database.saleOrderDao().insertAll(backupData.salesOrders)

                    // Reconcile debtors: Ensure any customer who has unpaid orders (balanceDue > 0) has their customer record created and currentBalance updated
                    val allCustomersMap = database.customerDao().getAllCustomersDirect().associateBy { it.id }.toMutableMap()
                    for (order in backupData.salesOrders) {
                        if (order.balanceDue > 0.009) {
                            val existingCustomer = allCustomersMap[order.customerId]
                            if (existingCustomer == null && order.customerId > 0) {
                                val newCust = CustomerEntity(
                                    id = order.customerId,
                                    name = order.customerName.ifBlank { "Debtor #${order.customerId}" },
                                    phone = order.customerPhone,
                                    whatsapp = order.customerWhatsapp,
                                    notes = "Restored from Invoice #${order.invoiceNumber}",
                                    totalPurchases = order.totalAmount,
                                    totalPaid = order.amountPaid,
                                    currentBalance = order.balanceDue,
                                    createdAt = order.timestamp
                                )
                                database.customerDao().insertCustomer(newCust)
                                allCustomersMap[newCust.id] = newCust
                            } else if (existingCustomer != null && existingCustomer.currentBalance < order.balanceDue) {
                                val updated = existingCustomer.copy(currentBalance = order.balanceDue)
                                database.customerDao().updateCustomer(updated)
                                allCustomersMap[updated.id] = updated
                            }
                        }
                    }
                }

                // 5. Payments
                if (backupData.payments.isNotEmpty()) {
                    database.paymentDao().insertAll(backupData.payments)
                }

                // 6. Inventory Logs
                if (backupData.inventoryLogs.isNotEmpty()) {
                    database.inventoryLogDao().insertAll(backupData.inventoryLogs)
                }
            }

            RestoreResult(
                success = true,
                message = "Restored successfully: ${backupData.products.size} products, ${backupData.customers.size} customers, ${backupData.salesOrders.size} sales, and ${backupData.payments.size} payment entries.",
                productsCount = backupData.products.size,
                customersCount = backupData.customers.size,
                salesCount = backupData.salesOrders.size,
                paymentsCount = backupData.payments.size,
                logsCount = backupData.inventoryLogs.size,
                settingsRestored = backupData.settings != null,
                backupTimestamp = backupData.exportedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore database from backup", e)
            RestoreResult(
                success = false,
                message = "Failed to restore backup: ${e.localizedMessage ?: "Unknown error"}",
                error = e.localizedMessage
            )
        }
    }

    /**
     * Reads a JSON backup from an Android Content Uri (e.g. from File Picker) and restores it.
     */
    suspend fun restoreFromUri(
        context: Context,
        uri: Uri,
        database: AppDatabase
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() }
            if (jsonString.isNullOrBlank()) {
                return@withContext RestoreResult(
                    success = false,
                    message = "The selected backup file is empty or could not be read."
                )
            }

            val backupData = parseBackupJson(jsonString)
                ?: return@withContext RestoreResult(
                    success = false,
                    message = "Invalid backup format. Ensure the selected file is a valid JSON backup exported from BizTrack."
                )

            restoreDatabase(database, backupData)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from URI $uri", e)
            RestoreResult(
                success = false,
                message = "Failed to open or read backup: ${e.localizedMessage ?: "File read error"}",
                error = e.localizedMessage
            )
        }
    }

    /**
     * Writes backup JSON directly into a user-selected file Uri (from CreateDocument launcher).
     */
    suspend fun writeBackupToUri(
        context: Context,
        uri: Uri,
        backupData: BackupData
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonString = backupAdapter.toJson(backupData)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write backup to Uri $uri", e)
            false
        }
    }

    /**
     * Lists all saved local backup files on device storage.
     */
    fun getSavedBackupFiles(context: Context): List<BackupFileInfo> {
        val list = mutableListOf<BackupFileInfo>()
        val extDir = try { context.getExternalFilesDir(null) } catch (e: Exception) { null }
        val externalBackupDir = if (extDir != null) File(extDir, "backups") else null
        val internalBackupDir = File(context.filesDir, "backups")

        val files = mutableListOf<File>()
        try {
            if (externalBackupDir != null && externalBackupDir.exists()) {
                externalBackupDir.listFiles()?.filter { it.extension.lowercase() == "json" }?.let { files.addAll(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing external backups", e)
        }

        try {
            if (internalBackupDir.exists()) {
                internalBackupDir.listFiles()?.filter { it.extension.lowercase() == "json" }?.let { files.addAll(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing internal backups", e)
        }

        // Distinct by filename and sort newest first
        val distinctFiles = files.distinctBy { it.name }.sortedByDescending { it.lastModified() }
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        for (file in distinctFiles) {
            try {
                val sizeKb = file.length() / 1024.0
                val formattedSize = if (sizeKb < 1.0) "${file.length()} B" else String.format(Locale.US, "%.1f KB", sizeKb)
                val isAuto = file.name.contains("autobackup", ignoreCase = true)
                list.add(
                    BackupFileInfo(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        formattedSize = formattedSize,
                        timestamp = file.lastModified(),
                        formattedDate = sdf.format(Date(file.lastModified())),
                        isAutoBackup = isAuto
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing backup file item", e)
            }
        }
        return list
    }

    /**
     * Creates an Android Share Sheet Intent for the backup JSON file.
     */
    fun createShareIntent(context: Context, backupFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            backupFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BizTrack Database Backup (${backupFile.name})")
            putExtra(
                Intent.EXTRA_TEXT,
                "Here is the local database backup for BizTrack POS. You can restore this file anytime in the app's Settings screen."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun pruneOldBackups(dir: File, maxKeep: Int) {
        try {
            val timestampedFiles = dir.listFiles()
                ?.filter { it.name.startsWith("naadriel_") && !it.name.contains("latest") && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() } ?: return

            if (timestampedFiles.size > maxKeep) {
                timestampedFiles.drop(maxKeep).forEach { oldFile ->
                    oldFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prune old backups", e)
        }
    }

    private fun saveBackupMetadata(
        context: Context,
        timestamp: Long,
        status: String,
        itemCount: Int,
        filePath: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(PREF_LAST_BACKUP_TIME, timestamp)
            .putString(PREF_LAST_BACKUP_STATUS, status)
            .putInt(PREF_LAST_BACKUP_COUNT, itemCount)
            .putString(PREF_LAST_BACKUP_PATH, filePath)
            .apply()
    }

    fun getLastBackupInfo(context: Context): Triple<Long, String, Int> {
        val prefs = context.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(PREF_LAST_BACKUP_TIME, 0L)
        val status = prefs.getString(PREF_LAST_BACKUP_STATUS, "No backup performed yet") ?: "No backup performed yet"
        val count = prefs.getInt(PREF_LAST_BACKUP_COUNT, 0)
        return Triple(timestamp, status, count)
    }
}
