package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.*
import com.example.util.DatabaseBackupHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Database(
    entities = [
        ProductEntity::class,
        InventoryLogEntity::class,
        CustomerEntity::class,
        SaleOrderEntity::class,
        PaymentEntity::class,
        AppSettingsEntity::class,
        DeletedRecordEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun inventoryLogDao(): InventoryLogDao
    abstract fun deletedRecordDao(): DeletedRecordDao
    abstract fun customerDao(): CustomerDao
    abstract fun saleOrderDao(): SaleOrderDao
    abstract fun paymentDao(): PaymentDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun safeExec(db: SupportSQLiteDatabase, sql: String) {
            try {
                db.execSQL(sql)
            } catch (e: Exception) {
                android.util.Log.w("AppDatabase", "Ignored migration exception for SQL: $sql", e)
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeExec(db, "ALTER TABLE products ADD COLUMN costPrice REAL NOT NULL DEFAULT 0.0")
                safeExec(db, "ALTER TABLE products ADD COLUMN lastRestockedAt INTEGER NOT NULL DEFAULT 0")
                safeExec(db, "ALTER TABLE customers ADD COLUMN whatsapp TEXT NOT NULL DEFAULT ''")
                safeExec(db, "ALTER TABLE customers ADD COLUMN address TEXT NOT NULL DEFAULT ''")
                safeExec(db, "ALTER TABLE customers ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeExec(db, """
                    CREATE TABLE IF NOT EXISTS inventory_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER NOT NULL,
                        productName TEXT NOT NULL,
                        changeType TEXT NOT NULL,
                        quantityChanged REAL NOT NULL,
                        quantityAfter REAL NOT NULL,
                        unit TEXT NOT NULL,
                        notes TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN smsAutoSendOnSale INTEGER NOT NULL DEFAULT 1")
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN smsAutoSendOnPayment INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeExec(db, "ALTER TABLE sales_orders ADD COLUMN discountAmount REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN cloudSyncUrl TEXT NOT NULL DEFAULT ''")
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN cloudSyncSecretKey TEXT NOT NULL DEFAULT ''")
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN cloudAutoSyncOnSale INTEGER NOT NULL DEFAULT 0")
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN lastCloudSyncTime INTEGER NOT NULL DEFAULT 0")
                safeExec(db, "ALTER TABLE app_settings ADD COLUMN lastCloudSyncStatus TEXT NOT NULL DEFAULT 'Not synced yet'")
            }
        }

        // v7: web address of the product photo uploaded to the cloud server (local file stays in imageUri).
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeExec(db, "ALTER TABLE products ADD COLUMN remoteImageUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        // v8: two-way sync. Per-record change times, deletion records, and opening balances
        // so customer totals can be derived from sales (see CustomerEntity).
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("products", "customers", "sales_orders", "payments", "app_settings")) {
                    safeExec(db, "ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                }
                safeExec(db, "ALTER TABLE customers ADD COLUMN openingBalance REAL NOT NULL DEFAULT 0")
                safeExec(db, "ALTER TABLE customers ADD COLUMN openingPurchases REAL NOT NULL DEFAULT 0")
                safeExec(db, "CREATE TABLE IF NOT EXISTS deleted_records (tableName TEXT NOT NULL, recordId INTEGER NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(tableName, recordId))")
                // Whatever today's balance doesn't get from sales becomes the opening amount,
                // so every balance stays exactly the same after the upgrade.
                safeExec(db, """
                    UPDATE customers SET
                        openingBalance = currentBalance - COALESCE((SELECT SUM(s.balanceDue) FROM sales_orders s WHERE s.customerId = customers.id), 0),
                        openingPurchases = totalPurchases - COALESCE((SELECT SUM(s.totalAmount) FROM sales_orders s WHERE s.customerId = customers.id), 0)
                """.trimIndent())
            }
        }

        val MIGRATION_1_6 = object : Migration(1, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_2_6 = object : Migration(2, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val instance = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "naadriel_farm_db"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    MIGRATION_1_6, MIGRATION_2_6, MIGRATION_3_6, MIGRATION_4_6
                )
                .addCallback(DatabaseCallback(appContext))
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Seed default products & settings in background, or restore existing backup if present
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        seedDatabase(database, context)
                    }
                }
            }
        }

        private suspend fun seedDatabase(database: AppDatabase, context: Context) {
            try {
                val productDao = database.productDao()
                val settingsDao = database.appSettingsDao()

                // First check if there is an existing JSON backup on the device storage
                val extDir = try { context.getExternalFilesDir(null) } catch (e: Exception) { null }
                val extBackupFile = if (extDir != null) File(extDir, "backups/naadriel_farm_backup_latest.json") else null
                val intBackupFile = File(context.filesDir, "backups/naadriel_farm_backup_latest.json")

                val targetBackup = when {
                    extBackupFile != null && extBackupFile.exists() -> extBackupFile
                    intBackupFile.exists() -> intBackupFile
                    else -> null
                }

                if (targetBackup != null) {
                    try {
                        val json = targetBackup.readText()
                        val backupData = DatabaseBackupHelper.parseBackupJson(json)
                        if (backupData != null && backupData.products.isNotEmpty()) {
                            DatabaseBackupHelper.restoreDatabase(database, backupData)
                            return
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AppDatabase", "Error auto-restoring backup on create", e)
                    }
                }

                // New installs start with an empty catalog so any kind of business can set up its own products.

            if (settingsDao.getSettingsDirect() == null) {
                settingsDao.saveSettings(
                    AppSettingsEntity(
                        id = 1,
                        businessName = "BizTrack Business",
                        businessTagline = "Quality products & reliable service",
                        businessPhone = "024 000 0000",
                        businessLocation = "Accra, Ghana",
                        momoPaymentDetails = "MTN MoMo: 0244XXXXXX (BizTrack)",
                        smsApiKey = "",
                        smsSenderId = "BizTrack",
                        smsAutoSendOnSale = true,
                        smsAutoSendOnPayment = true,
                        currencySymbol = "GH₵"
                    )
                )
            }

        } catch (e: Exception) {
            android.util.Log.e("AppDatabase", "Error seeding initial database", e)
        }
    }
}
}
