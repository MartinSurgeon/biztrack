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
        AppSettingsEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun inventoryLogDao(): InventoryLogDao
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
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_1_6, MIGRATION_2_6, MIGRATION_3_6, MIGRATION_4_6
                )
                .fallbackToDestructiveMigration()
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

                if (productDao.getProductCount() == 0) {
                val defaultProducts = listOf(
                    ProductEntity(
                        name = "Fresh Eggs (Crate of 30)",
                        category = ProductCategory.EGGS.name,
                        unit = "Crate",
                        unitPrice = 55.0,
                        costPrice = 42.0,
                        stockQuantity = 80.0,
                        minStockThreshold = 15.0,
                        inStock = true,
                        description = "High quality farm fresh layer eggs"
                    ),
                    ProductEntity(
                        name = "Fresh Eggs (Single Piece)",
                        category = ProductCategory.EGGS.name,
                        unit = "Piece",
                        unitPrice = 2.0,
                        costPrice = 1.4,
                        stockQuantity = 240.0,
                        minStockThreshold = 50.0,
                        inStock = true,
                        description = "Individual fresh farm egg"
                    ),
                    ProductEntity(
                        name = "Live Broiler Chicken",
                        category = ProductCategory.BROILER_LIVE.name,
                        unit = "Bird",
                        unitPrice = 90.0,
                        costPrice = 65.0,
                        stockQuantity = 120.0,
                        minStockThreshold = 20.0,
                        inStock = true,
                        description = "Healthy live commercial broiler chicken"
                    ),
                    ProductEntity(
                        name = "Dressed Broiler Chicken",
                        category = ProductCategory.BROILER_DRESSED.name,
                        unit = "Bird",
                        unitPrice = 95.0,
                        costPrice = 70.0,
                        stockQuantity = 45.0,
                        minStockThreshold = 10.0,
                        inStock = true,
                        description = "Clean, hygienically slaughtered and dressed broiler"
                    ),
                    ProductEntity(
                        name = "Live Sasso Chicken",
                        category = ProductCategory.SASSO_LIVE.name,
                        unit = "Bird",
                        unitPrice = 110.0,
                        costPrice = 80.0,
                        stockQuantity = 75.0,
                        minStockThreshold = 15.0,
                        inStock = true,
                        description = "Premium free-range dual-purpose live Sasso chicken"
                    ),
                    ProductEntity(
                        name = "Dressed Sasso Chicken",
                        category = ProductCategory.SASSO_DRESSED.name,
                        unit = "Bird",
                        unitPrice = 115.0,
                        costPrice = 85.0,
                        stockQuantity = 30.0,
                        minStockThreshold = 10.0,
                        inStock = true,
                        description = "Clean, dressed organic-flavor Sasso chicken"
                    )
                )
                productDao.insertAll(defaultProducts)
            }

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

            val customerDao = database.customerDao()
            val saleOrderDao = database.saleOrderDao()
            val paymentDao = database.paymentDao()

            if (customerDao.getCustomerCount() == 0) {
                val now = System.currentTimeMillis()
                val oneDay = 24 * 60 * 60 * 1000L

                // 1. Madam Joyce Mensah - Egg retailer (Has paid in full order + part-paid order with installments)
                val joyceId = customerDao.insertCustomer(
                    CustomerEntity(
                        name = "Madam Joyce Mensah",
                        phone = "0244123456",
                        whatsapp = "0244123456",
                        address = "Madina New Market, Stall #42",
                        notes = "Egg Retailer • Regular weekly buyer",
                        totalPurchases = 1575.0,
                        totalPaid = 1175.0,
                        currentBalance = 400.0,
                        createdAt = now - (5 * oneDay)
                    )
                )

                // Joyce Order 1: 20 Crates Fresh Eggs (Partially paid with installments)
                val joyceItems1 = listOf(
                    CartItem(
                        productId = 1,
                        productName = "Fresh Eggs (Crate of 30)",
                        category = ProductCategory.EGGS.name,
                        unit = "Crate",
                        unitPrice = 55.0,
                        quantity = 20.0,
                        lineTotal = 1100.0
                    )
                )
                val joyceOrder1Id = saleOrderDao.insertOrder(
                    SaleOrderEntity(
                        invoiceNumber = "NE-20260829-091500",
                        customerId = joyceId,
                        customerName = "Madam Joyce Mensah",
                        customerPhone = "0244123456",
                        customerWhatsapp = "0244123456",
                        itemsJson = com.example.util.Formatters.serializeCartItems(joyceItems1),
                        totalAmount = 1100.0,
                        amountPaid = 700.0,
                        balanceDue = 400.0,
                        paymentStatus = PaymentStatus.PARTIAL.name,
                        paymentMethod = PaymentMethod.MOMO.name,
                        notes = "Delivery to Madina Market",
                        timestamp = now - (3 * oneDay)
                    )
                )

                // Joyce Order 2: 5 Dressed Broilers (PAID IN FULL)
                val joyceItems2 = listOf(
                    CartItem(
                        productId = 4,
                        productName = "Dressed Broiler Chicken",
                        category = ProductCategory.BROILER_DRESSED.name,
                        unit = "Bird",
                        unitPrice = 95.0,
                        quantity = 5.0,
                        lineTotal = 475.0
                    )
                )
                val joyceOrder2Id = saleOrderDao.insertOrder(
                    SaleOrderEntity(
                        invoiceNumber = "NE-20260831-140215",
                        customerId = joyceId,
                        customerName = "Madam Joyce Mensah",
                        customerPhone = "0244123456",
                        customerWhatsapp = "0244123456",
                        itemsJson = com.example.util.Formatters.serializeCartItems(joyceItems2),
                        totalAmount = 475.0,
                        amountPaid = 475.0,
                        balanceDue = 0.0,
                        paymentStatus = PaymentStatus.PAID.name,
                        paymentMethod = PaymentMethod.CASH.name,
                        notes = "Paid cash upon pickup",
                        timestamp = now - (1 * oneDay)
                    )
                )

                // Joyce Part Payments History
                paymentDao.insertPayment(
                    PaymentEntity(
                        customerId = joyceId,
                        customerName = "Madam Joyce Mensah",
                        saleOrderId = joyceOrder1Id,
                        amount = 500.0,
                        paymentMethod = PaymentMethod.MOMO.name,
                        notes = "Initial deposit for Invoice #NE-20260829-091500",
                        timestamp = now - (3 * oneDay),
                        balanceAfterPayment = 600.0
                    )
                )
                paymentDao.insertPayment(
                    PaymentEntity(
                        customerId = joyceId,
                        customerName = "Madam Joyce Mensah",
                        saleOrderId = joyceOrder2Id,
                        amount = 475.0,
                        paymentMethod = PaymentMethod.CASH.name,
                        notes = "Full payment for Invoice #NE-20260831-140215",
                        timestamp = now - (1 * oneDay),
                        balanceAfterPayment = 600.0
                    )
                )
                paymentDao.insertPayment(
                    PaymentEntity(
                        customerId = joyceId,
                        customerName = "Madam Joyce Mensah",
                        saleOrderId = joyceOrder1Id,
                        amount = 200.0,
                        paymentMethod = PaymentMethod.MOMO.name,
                        notes = "Installment part payment via MTN MoMo (Ref: 2489102)",
                        timestamp = now - (12 * 60 * 60 * 1000L),
                        balanceAfterPayment = 400.0
                    )
                )

                // 2. Chef Sammy Osei - Restaurant & Catering
                val sammyId = customerDao.insertCustomer(
                    CustomerEntity(
                        name = "Chef Sammy Osei",
                        phone = "0208765432",
                        whatsapp = "0208765432",
                        address = "East Legon, Lagos Avenue",
                        notes = "Flame & Grill Restaurant",
                        totalPurchases = 2450.0,
                        totalPaid = 1600.0,
                        currentBalance = 850.0,
                        createdAt = now - (7 * oneDay)
                    )
                )

                // Sammy Order 1: 10 Live Sasso Chicken (PAID IN FULL)
                val sammyItems1 = listOf(
                    CartItem(
                        productId = 5,
                        productName = "Live Sasso Chicken",
                        category = ProductCategory.SASSO_LIVE.name,
                        unit = "Bird",
                        unitPrice = 110.0,
                        quantity = 10.0,
                        lineTotal = 1100.0
                    )
                )
                val sammyOrder1Id = saleOrderDao.insertOrder(
                    SaleOrderEntity(
                        invoiceNumber = "NE-20260827-113045",
                        customerId = sammyId,
                        customerName = "Chef Sammy Osei",
                        customerPhone = "0208765432",
                        customerWhatsapp = "0208765432",
                        itemsJson = com.example.util.Formatters.serializeCartItems(sammyItems1),
                        totalAmount = 1100.0,
                        amountPaid = 1100.0,
                        balanceDue = 0.0,
                        paymentStatus = PaymentStatus.PAID.name,
                        paymentMethod = PaymentMethod.BANK_TRANSFER.name,
                        notes = "Paid via Bank Transfer in full",
                        timestamp = now - (4 * oneDay)
                    )
                )

                // Sammy Order 2: 15 Live Broiler Chicken (PARTIALLY PAID / OWING)
                val sammyItems2 = listOf(
                    CartItem(
                        productId = 3,
                        productName = "Live Broiler Chicken",
                        category = ProductCategory.BROILER_LIVE.name,
                        unit = "Bird",
                        unitPrice = 90.0,
                        quantity = 15.0,
                        lineTotal = 1350.0
                    )
                )
                val sammyOrder2Id = saleOrderDao.insertOrder(
                    SaleOrderEntity(
                        invoiceNumber = "NE-20260901-081030",
                        customerId = sammyId,
                        customerName = "Chef Sammy Osei",
                        customerPhone = "0208765432",
                        customerWhatsapp = "0208765432",
                        itemsJson = com.example.util.Formatters.serializeCartItems(sammyItems2),
                        totalAmount = 1350.0,
                        amountPaid = 500.0,
                        balanceDue = 850.0,
                        paymentStatus = PaymentStatus.PARTIAL.name,
                        paymentMethod = PaymentMethod.MOMO.name,
                        notes = "Deposit paid, balance due at weekend",
                        timestamp = now - (5 * 60 * 60 * 1000L)
                    )
                )

                // Sammy Payments History
                paymentDao.insertPayment(
                    PaymentEntity(
                        customerId = sammyId,
                        customerName = "Chef Sammy Osei",
                        saleOrderId = sammyOrder1Id,
                        amount = 1100.0,
                        paymentMethod = PaymentMethod.BANK_TRANSFER.name,
                        notes = "Full payment for Sasso Chicken order",
                        timestamp = now - (4 * oneDay),
                        balanceAfterPayment = 0.0
                    )
                )
                paymentDao.insertPayment(
                    PaymentEntity(
                        customerId = sammyId,
                        customerName = "Chef Sammy Osei",
                        saleOrderId = sammyOrder2Id,
                        amount = 500.0,
                        paymentMethod = PaymentMethod.MOMO.name,
                        notes = "Deposit part payment for Live Broilers",
                        timestamp = now - (5 * 60 * 60 * 1000L),
                        balanceAfterPayment = 850.0
                    )
                )

                // 3. Akosua Serwaa - Neighborhood Customer (All Cleared)
                val akosuaId = customerDao.insertCustomer(
                    CustomerEntity(
                        name = "Akosua Serwaa",
                        phone = "0551239876",
                        address = "Adenta Barrier",
                        notes = "Buys fresh eggs for household",
                        totalPurchases = 110.0,
                        totalPaid = 110.0,
                        currentBalance = 0.0,
                        createdAt = now - (4 * oneDay)
                    )
                )
                val akosuaItems = listOf(
                    CartItem(
                        productId = 1,
                        productName = "Fresh Eggs (Crate of 30)",
                        category = ProductCategory.EGGS.name,
                        unit = "Crate",
                        unitPrice = 55.0,
                        quantity = 2.0,
                        lineTotal = 110.0
                    )
                )
                val akosuaOrderId = saleOrderDao.insertOrder(
                    SaleOrderEntity(
                        invoiceNumber = "NE-20260830-174512",
                        customerId = akosuaId,
                        customerName = "Akosua Serwaa",
                        customerPhone = "0551239876",
                        customerWhatsapp = "",
                        itemsJson = com.example.util.Formatters.serializeCartItems(akosuaItems),
                        totalAmount = 110.0,
                        amountPaid = 110.0,
                        balanceDue = 0.0,
                        paymentStatus = PaymentStatus.PAID.name,
                        paymentMethod = PaymentMethod.CASH.name,
                        notes = "Direct cash payment at farm gate",
                        timestamp = now - (2 * oneDay)
                    )
                )
                paymentDao.insertPayment(
                    PaymentEntity(
                        customerId = akosuaId,
                        customerName = "Akosua Serwaa",
                        saleOrderId = akosuaOrderId,
                        amount = 110.0,
                        paymentMethod = PaymentMethod.CASH.name,
                        notes = "Full cash payment on collection",
                        timestamp = now - (2 * oneDay),
                        balanceAfterPayment = 0.0
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AppDatabase", "Error seeding initial database", e)
        }
    }
}
}
