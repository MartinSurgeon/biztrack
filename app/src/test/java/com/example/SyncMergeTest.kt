package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RecordIds
import com.example.data.model.*
import com.example.data.repository.FarmRepository
import com.example.util.DatabaseBackupHelper
import com.example.util.Formatters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two-way sync merge rules (DatabaseBackupHelper.restoreDatabase) and the app-side bookkeeping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncMergeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: FarmRepository

    @Before fun setUp() {
        RecordIds.resetForTests()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        RecordIds.init(context, db)
        repo = FarmRepository(db.productDao(), db.customerDao(), db.saleOrderDao(), db.paymentDao(), db.appSettingsDao(),
            db.inventoryLogDao(), db.deletedRecordDao())
    }

    @After fun tearDown() { db.close(); RecordIds.resetForTests() }

    private fun item(p: ProductEntity, qty: Double) =
        CartItem(p.id, p.name, p.category, p.unit, p.unitPrice, qty, qty * p.unitPrice)

    @Test fun sale_reduces_stock_and_void_restores_it() = runBlocking {
        val id = repo.insertProduct(ProductEntity(name = "Bread", category = "GROCERIES", unit = "Loaf", unitPrice = 8.0, stockQuantity = 10.0))
        val bread = db.productDao().getProductById(id)!!
        val order = repo.createSaleOrder(0, "Ama", "0240000001", "", Formatters.serializeCartItems(listOf(item(bread, 3.0))),
            24.0, 0.0, 10.0, PaymentMethod.CASH, "")
        assertEquals("sale takes stock out", 7.0, db.productDao().getProductById(id)!!.stockQuantity, 1e-9)
        assertEquals("owes the rest", 14.0, db.customerDao().getCustomerById(order.customerId)!!.currentBalance, 1e-9)

        repo.voidSaleOrder(db.saleOrderDao().getOrderById(order.id)!!)
        assertEquals("void puts it back", 10.0, db.productDao().getProductById(id)!!.stockQuantity, 1e-9)
        assertEquals(0.0, db.customerDao().getCustomerById(order.customerId)!!.currentBalance, 1e-9)
        assertTrue("void is remembered for sync", db.deletedRecordDao().getAll().any { it.tableName == "sales_orders" && it.recordId == order.id })
        val logs = db.inventoryLogDao().getAllLogsDirect().map { it.changeType }.sorted()
        assertEquals(listOf("RESTOCK", "SALE", "VOID_RESTORE"), logs)
    }

    @Test fun stock_changes_from_both_sides_all_count() = runBlocking {
        val id = repo.insertProduct(ProductEntity(name = "Water", category = "FOOD_DRINKS", unit = "Bottle", unitPrice = 3.0, stockQuantity = 50.0))
        repo.adjustProductStock(id, -5.0, "SALE", "sold on phone") // phone: 45
        val local = db.productDao().getProductById(id)!!
        // The web restocked +20 and also renamed the product later; its own stock number is 70.
        val incoming = BackupData(
            products = listOf(local.copy(name = "Water 500ml", stockQuantity = 70.0, updatedAt = local.updatedAt + 1000)),
            inventoryLogs = listOf(InventoryLogEntity(id = RecordIds.WEB_ID_BASE + 1, productId = id, productName = "Water",
                changeType = "RESTOCK", quantityChanged = 20.0, quantityAfter = 70.0, unit = "Bottle"))
        )
        DatabaseBackupHelper.restoreDatabase(db, incoming)
        val merged = db.productDao().getProductById(id)!!
        assertEquals("newer name wins", "Water 500ml", merged.name)
        assertEquals("phone sale and web restock both count", 65.0, merged.stockQuantity, 1e-9)

        DatabaseBackupHelper.restoreDatabase(db, incoming) // same data again
        assertEquals("a stock change is only applied once", 65.0, db.productDao().getProductById(id)!!.stockQuantity, 1e-9)
    }

    @Test fun deleted_on_web_stays_deleted_and_balances_follow() = runBlocking {
        val p = db.productDao().getProductById(
            repo.insertProduct(ProductEntity(name = "Cake", category = "FOOD_DRINKS", unit = "Piece", unitPrice = 40.0, stockQuantity = 5.0)))!!
        val order = repo.createSaleOrder(0, "Kofi", "0240000002", "", Formatters.serializeCartItems(listOf(item(p, 1.0))),
            40.0, 0.0, 0.0, PaymentMethod.CASH, "")
        assertEquals(40.0, db.customerDao().getCustomerById(order.customerId)!!.currentBalance, 1e-9)

        val deletedAt = db.saleOrderDao().getOrderById(order.id)!!.updatedAt + 5000
        // The web deleted the sale; an out-of-date copy of the sale is still in the same payload.
        DatabaseBackupHelper.restoreDatabase(db, BackupData(
            salesOrders = listOf(db.saleOrderDao().getOrderById(order.id)!!),
            deletedRecords = listOf(DeletedRecordEntity("sales_orders", order.id, deletedAt))
        ))
        assertNull("sale deleted and not brought back", db.saleOrderDao().getOrderById(order.id))
        assertEquals("customer no longer owes for it", 0.0, db.customerDao().getCustomerById(order.customerId)!!.currentBalance, 1e-9)
    }

    @Test fun newer_local_edit_is_not_overwritten() = runBlocking {
        val cid = repo.insertCustomer(CustomerEntity(name = "Efua", phone = "0240000003"))
        repo.updateCustomer(db.customerDao().getCustomerById(cid)!!.copy(address = "Madina"))
        val local = db.customerDao().getCustomerById(cid)!!
        DatabaseBackupHelper.restoreDatabase(db, BackupData(customers = listOf(local.copy(address = "Old address", updatedAt = local.updatedAt - 10_000))))
        assertEquals("Madina", db.customerDao().getCustomerById(cid)!!.address)
    }

    @Test fun legacy_backup_keeps_balances_unchanged() = runBlocking {
        // An old backup: customer owes 500 of which 300 is from a sale, no opening fields, no updatedAt.
        val sale = SaleOrderEntity(id = 77, invoiceNumber = "OLD-77", customerId = 5, customerName = "Yaw", customerPhone = "",
            customerWhatsapp = "", itemsJson = "[]", totalAmount = 300.0, amountPaid = 0.0, balanceDue = 300.0,
            paymentStatus = "UNPAID", paymentMethod = "CASH")
        DatabaseBackupHelper.restoreDatabase(db, BackupData(
            customers = listOf(CustomerEntity(id = 5, name = "Yaw", phone = "", totalPurchases = 900.0, totalPaid = 400.0, currentBalance = 500.0)),
            salesOrders = listOf(sale)
        ))
        val yaw = db.customerDao().getCustomerById(5)!!
        assertEquals(500.0, yaw.currentBalance, 1e-9)
        assertEquals(200.0, yaw.openingBalance, 1e-9)
        assertEquals(900.0, yaw.totalPurchases, 1e-9)
        assertEquals(400.0, yaw.totalPaid, 1e-9)
    }

    @Test fun payment_beyond_unpaid_sales_reduces_opening_balance() = runBlocking {
        val cid = repo.insertCustomer(CustomerEntity(name = "Akos", phone = "0240000004", currentBalance = 100.0, totalPurchases = 100.0))
        assertEquals(100.0, db.customerDao().getCustomerById(cid)!!.currentBalance, 1e-9)
        repo.recordCustomerPayment(cid, 60.0, PaymentMethod.MOMO, "")
        val c = db.customerDao().getCustomerById(cid)!!
        assertEquals(40.0, c.currentBalance, 1e-9)
        assertEquals(60.0, c.totalPaid, 1e-9)
    }
}
