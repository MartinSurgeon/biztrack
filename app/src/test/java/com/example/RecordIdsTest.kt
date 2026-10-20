package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RecordIds
import com.example.data.model.CustomerEntity
import com.example.data.model.ProductEntity
import com.example.data.repository.FarmRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordIdsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: FarmRepository

    @Before fun setUp() {
        RecordIds.resetForTests()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        RecordIds.init(context, db)
        repo = FarmRepository(db.productDao(), db.customerDao(), db.saleOrderDao(), db.paymentDao(), db.appSettingsDao(), db.inventoryLogDao())
    }

    @After fun tearDown() {
        db.close()
        RecordIds.resetForTests()
    }

    @Test fun new_records_get_ids_in_this_phones_block() = runBlocking {
        val base = RecordIds.deviceBase
        assertTrue("block starts above old ids", base >= RecordIds.BLOCK)
        assertTrue("block ends below the web range", base + RecordIds.BLOCK <= RecordIds.WEB_ID_BASE)

        val first = repo.insertProduct(ProductEntity(name = "Tea", category = "FOOD_DRINKS", unit = "Cup", unitPrice = 2.0))
        val second = repo.insertProduct(ProductEntity(name = "Coffee", category = "FOOD_DRINKS", unit = "Cup", unitPrice = 3.0))
        assertEquals(base + 1, first)
        assertEquals(base + 2, second)
        assertEquals("tables count separately", base + 1, repo.insertCustomer(CustomerEntity(name = "Ama", phone = "024")))
    }

    /** Records pulled from the web (huge ids) or older records (small ids) don't shift the phone's counter. */
    @Test fun pulled_web_and_old_records_do_not_move_the_counter() = runBlocking {
        val base = RecordIds.deviceBase
        db.productDao().insertAll(listOf(
            ProductEntity(id = 7, name = "Old", category = "GENERAL", unit = "Piece", unitPrice = 1.0),
            ProductEntity(id = RecordIds.WEB_ID_BASE + 42, name = "Made on web", category = "GENERAL", unit = "Piece", unitPrice = 1.0)
        ))
        val id = repo.insertProduct(ProductEntity(name = "New", category = "GENERAL", unit = "Piece", unitPrice = 1.0))
        assertEquals(base + 1, id)
    }

    @Test fun counter_resumes_after_restart() = runBlocking {
        val base = RecordIds.deviceBase
        repeat(3) { repo.insertProduct(ProductEntity(name = "P$it", category = "GENERAL", unit = "Piece", unitPrice = 1.0)) }
        // Simulate an app restart: in-memory counters are gone, the stored block is kept.
        RecordIds.resetForTests()
        RecordIds.init(context, db)
        assertEquals("same block after restart", base, RecordIds.deviceBase)
        assertEquals(base + 4, repo.insertProduct(ProductEntity(name = "After restart", category = "GENERAL", unit = "Piece", unitPrice = 1.0)))
    }

    @Test fun concurrent_inserts_get_unique_ids() = runBlocking {
        val ids = (1..50).map { i ->
            async { repo.insertProduct(ProductEntity(name = "P$i", category = "GENERAL", unit = "Piece", unitPrice = 1.0)) }
        }.awaitAll()
        assertEquals(50, ids.toSet().size)
        assertEquals(50, db.productDao().getAllProductsDirect().size)
    }
}
