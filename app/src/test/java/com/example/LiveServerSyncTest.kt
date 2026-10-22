package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RecordIds
import com.example.data.model.CartItem
import com.example.data.model.PaymentMethod
import com.example.data.remote.CloudSyncService
import com.example.data.repository.FarmRepository
import com.example.util.DatabaseBackupHelper
import com.example.util.Formatters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app's real pull -> merge -> push cycle against sync.php. Runs only when a local test
 * server is up (php -S 127.0.0.1:8099 with the dashboard files and a test database);
 * otherwise it is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveServerSyncTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val server = System.getProperty("biztrack.syncUrl") ?: "http://127.0.0.1:8099/sync.php"
    private val key = "local-ui-test-key-0123456789abcdef"
    private val dbs = mutableListOf<AppDatabase>()

    private fun newPhone(): AppDatabase {
        RecordIds.resetForTests()
        // A different device: forget the stored ID block so this phone draws its own.
        context.getSharedPreferences("record_ids", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        RecordIds.init(context, db)
        dbs += db
        return db
    }

    private fun repo(db: AppDatabase) = FarmRepository(db.productDao(), db.customerDao(), db.saleOrderDao(), db.paymentDao(),
        db.appSettingsDao(), db.inventoryLogDao(), db.deletedRecordDao())

    /** Same order as FarmViewModel.syncToCloud: pull and merge, then push everything. */
    private suspend fun sync(db: AppDatabase) {
        val pulled = CloudSyncService.pullFromCloud(server, key)
        assertTrue("pull: ${pulled.message}", pulled.success)
        val merged = DatabaseBackupHelper.restoreDatabase(db, pulled.backupData!!)
        assertTrue("merge: ${merged.message}", merged.success)
        val pushed = CloudSyncService.pushToCloud(server, key, DatabaseBackupHelper.createBackupData(db))
        assertTrue("push: ${pushed.message}", pushed.success)
    }

    @After fun tearDown() { dbs.forEach { it.close() }; RecordIds.resetForTests() }

    @Test fun two_phones_and_the_web_stay_in_step() = runBlocking {
        val reachable = runCatching {
            (URL(server).openConnection() as HttpURLConnection).run { connectTimeout = 800; responseCode; true }
        }.getOrDefault(false)
        assumeTrue("local sync server not running", reachable)

        // Phone A joins: gets the server's products, customers and stock as they are.
        val a = newPhone()
        sync(a)
        val breadOnServer = a.productDao().getProductById(1)!!
        val yawBefore = a.customerDao().getCustomerById(5)!!.currentBalance
        assertTrue("web-deleted product not copied", a.productDao().getProductById(2) == null)

        // Phone A sells 2 bread to Yaw on credit, then syncs.
        val item = CartItem(1, breadOnServer.name, breadOnServer.category, breadOnServer.unit, breadOnServer.unitPrice, 2.0, 2 * breadOnServer.unitPrice)
        repo(a).createSaleOrder(5, "Yaw", "0240000005", "", Formatters.serializeCartItems(listOf(item)),
            item.lineTotal, 0.0, 0.0, PaymentMethod.CASH, "")
        sync(a)

        // Phone B joins later and sees phone A's sale, stock and balance.
        val b = newPhone()
        sync(b)
        assertEquals("stock after A's sale", breadOnServer.stockQuantity - 2, b.productDao().getProductById(1)!!.stockQuantity, 1e-9)
        assertEquals("Yaw owes for A's sale", yawBefore + item.lineTotal, b.customerDao().getCustomerById(5)!!.currentBalance, 1e-9)

        // Phone B voids that sale; phone A picks the void up and doesn't send the sale back.
        val sale = b.saleOrderDao().getAllOrdersDirect().first { it.totalAmount == item.lineTotal && it.customerId == 5L && it.balanceDue > 0 }
        repo(b).voidSaleOrder(sale)
        sync(b)
        sync(a)
        assertTrue("void reached phone A", a.saleOrderDao().getOrderById(sale.id) == null)
        assertEquals("stock restored on A", breadOnServer.stockQuantity, a.productDao().getProductById(1)!!.stockQuantity, 1e-9)
        assertEquals(yawBefore, a.customerDao().getCustomerById(5)!!.currentBalance, 1e-9)

        // Syncing again changes nothing.
        sync(a); sync(b)
        assertEquals(breadOnServer.stockQuantity, b.productDao().getProductById(1)!!.stockQuantity, 1e-9)
        assertEquals(breadOnServer.stockQuantity, a.productDao().getProductById(1)!!.stockQuantity, 1e-9)
    }
}
