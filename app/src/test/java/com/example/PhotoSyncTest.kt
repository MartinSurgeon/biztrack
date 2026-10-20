package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.BackupData
import com.example.data.model.ProductEntity
import com.example.data.remote.CloudSyncService
import com.example.util.DatabaseBackupHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoSyncTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun localPhoto(name: String): File = File(context.filesDir, name).apply {
        Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, outputStream())
    }

    /** A cloud restore must never erase a photo that is saved on this phone. */
    @Test fun restore_keeps_local_photos_and_adds_web_copies() = runBlocking {
        val photo = localPhoto("croissant.jpg")
        db.productDao().insertAll(listOf(
            ProductEntity(id = 1, name = "Croissant", category = "Pastries", unit = "Piece", unitPrice = 12.0, imageUri = photo.absolutePath),
            ProductEntity(id = 3, name = "Old", category = "GENERAL", unit = "Piece", unitPrice = 1.0,
                imageUri = "/data/missing/photo.jpg", remoteImageUrl = "https://shop.example/uploads/products/old.jpg")
        ))

        DatabaseBackupHelper.restoreDatabase(db, BackupData(products = listOf(
            // New server: photo address in remoteImageUrl, nothing usable in imageUri.
            ProductEntity(id = 1, name = "Croissant", category = "Pastries", unit = "Piece", unitPrice = 14.0,
                imageUri = "https://shop.example/uploads/products/a.jpg", remoteImageUrl = "https://shop.example/uploads/products/a.jpg"),
            // Older server format: address only in imageUri.
            ProductEntity(id = 2, name = "Water", category = "FOOD_DRINKS", unit = "Bottle", unitPrice = 3.5,
                imageUri = "https://shop.example/uploads/products/b.jpg"),
            // Server has no photo; local file is gone too: keep the known web copy.
            ProductEntity(id = 3, name = "Old", category = "GENERAL", unit = "Piece", unitPrice = 1.0, imageUri = "")
        )))

        val byId = db.productDao().getAllProductsDirect().associateBy { it.id }
        assertEquals("local photo kept", photo.absolutePath, byId.getValue(1).imageUri)
        assertEquals("https://shop.example/uploads/products/a.jpg", byId.getValue(1).remoteImageUrl)
        assertEquals("other fields still restored", 14.0, byId.getValue(1).unitPrice, 0.0)
        assertEquals("", byId.getValue(2).imageUri)
        assertEquals("https://shop.example/uploads/products/b.jpg", byId.getValue(2).remoteImageUrl)
        assertEquals("", byId.getValue(3).imageUri)
        assertEquals("https://shop.example/uploads/products/old.jpg", byId.getValue(3).remoteImageUrl)
    }

    /**
     * Real upload from the app's code to sync.php. Runs only when a local test server is
     * up (php -S 127.0.0.1:8099 with the dashboard files); otherwise it is skipped.
     */
    @Test fun upload_to_local_sync_server() = runBlocking {
        val server = System.getProperty("biztrack.syncUrl") ?: "http://127.0.0.1:8099/sync.php"
        val reachable = runCatching {
            (URL(server).openConnection() as HttpURLConnection).run { connectTimeout = 800; responseCode; true }
        }.getOrDefault(false)
        assumeTrue("local sync server not running", reachable)

        val url = CloudSyncService.uploadProductImage(server, "local-ui-test-key-0123456789abcdef", 2, localPhoto("upload.jpg"))
        assertTrue("expected a photo URL, got $url", url != null && url.contains("/uploads/products/") && url.endsWith(".jpg"))
        val code = (URL(url).openConnection() as HttpURLConnection).responseCode
        assertEquals(200, code)

        val wrongKey = CloudSyncService.uploadProductImage(server, "wrong-key-wrong-key-wrong-key", 2, localPhoto("upload2.jpg"))
        assertEquals(null, wrongKey)
    }
}
