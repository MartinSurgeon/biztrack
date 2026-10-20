package com.example.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Hands out IDs for new records so the phone and the web dashboard never pick the same one.
 *
 * Sync matches records by ID. When both sides simply used "highest ID + 1", a sale made on
 * the web and one made on a phone could get the same ID, and the next sync overwrote one of
 * them. Ranges (must match WEB_ID_BASE in index.php):
 *   - existing records: unchanged (mostly below [BLOCK])
 *   - each phone:       a random block of [BLOCK] IDs in [BLOCK, WEB_ID_BASE), chosen once
 *                       per install; ~1 in 900,000 chance two phones share a block
 *   - web dashboard:    from [WEB_ID_BASE] upward
 * All values stay below 2^53, so JavaScript on the web dashboard handles them exactly.
 */
object RecordIds {
    const val BLOCK = 1_000_000_000L                 // 1e9 IDs per phone
    const val WEB_ID_BASE = 1_000_000_000_000_000L   // 1e15
    private const val MAX_SLOT = 900_000L            // slots 1..899,999 -> IDs below 9e14 < WEB_ID_BASE

    private const val PREFS = "record_ids"
    private const val KEY_SLOT = "device_slot"

    private val mutex = Mutex()
    private val lastIssued = mutableMapOf<String, Long>()
    @Volatile private var database: AppDatabase? = null
    @Volatile private var base: Long = 0L

    /** Call once with the app's database; until then [next] returns 0 (Room auto-generates). */
    fun init(context: Context, db: AppDatabase) {
        if (database != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var slot = prefs.getLong(KEY_SLOT, 0L)
        if (slot !in 1 until MAX_SLOT) {
            slot = 1 + (SecureRandom().nextDouble() * (MAX_SLOT - 1)).toLong()
            prefs.edit().putLong(KEY_SLOT, slot).apply()
        }
        base = slot * BLOCK
        database = db
    }

    /** Next ID for [table] in this phone's block, or 0 if not initialised. */
    suspend fun next(table: String): Long {
        val db = database ?: return 0L
        return mutex.withLock {
            val last = lastIssued[table] ?: withContext(Dispatchers.IO) {
                db.openHelper.readableDatabase.query(
                    "SELECT MAX(id) FROM `$table` WHERE id > ? AND id < ?", arrayOf<Any>(base, base + BLOCK)
                ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else base }
            }
            val id = last + 1
            check(id < base + BLOCK) { "ID block for $table is full" }
            lastIssued[table] = id
            id
        }
    }

    /** Base of this phone's block (0 until [init]). */
    val deviceBase: Long get() = base

    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        database = null
        base = 0L
        lastIssued.clear()
    }

    const val PRODUCTS = "products"
    const val CUSTOMERS = "customers"
    const val SALES = "sales_orders"
    const val PAYMENTS = "payments"
    const val INVENTORY_LOGS = "inventory_logs"
}
