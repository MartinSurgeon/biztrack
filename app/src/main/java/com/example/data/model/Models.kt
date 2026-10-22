package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

/**
 * Product categories, stored by [name] in ProductEntity.category.
 * Entries marked [legacy] come from the original poultry-only version: they are
 * kept so existing records still resolve, but are not offered for new products.
 */
enum class ProductCategory(val displayName: String, val legacy: Boolean = false) {
    GENERAL("General"),
    FOOD_DRINKS("Food & Drinks"),
    GROCERIES("Groceries"),
    FASHION("Fashion & Apparel"),
    ELECTRONICS("Electronics"),
    HEALTH_BEAUTY("Health & Beauty"),
    HOME_HARDWARE("Home & Hardware"),
    AGRICULTURE("Agriculture & Farm"),
    SERVICES("Services"),
    OTHER("Other"),

    EGGS("Eggs", legacy = true),
    BROILER_LIVE("Live Broiler Chicken", legacy = true),
    BROILER_DRESSED("Dressed Broiler Chicken", legacy = true),
    SASSO_LIVE("Live Sasso Chicken", legacy = true),
    SASSO_DRESSED("Dressed Sasso Chicken", legacy = true);

    companion object {
        val selectable: List<ProductCategory> get() = entries.filterNot { it.legacy }

        fun fromKey(key: String): ProductCategory? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

        /** Human label for a stored category key, including unknown/custom keys. */
        /**
         * Human label for a stored category. Built-in keys map to their display name,
         * old SHOUTY_KEYS are prettified, and categories a business created themselves
         * (stored as typed, e.g. "Pastries") are shown unchanged.
         */
        fun labelFor(key: String): String {
            fromKey(key)?.let { return it.displayName }
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return GENERAL.displayName
            return if (trimmed.matches(Regex("[A-Z0-9_]+"))) trimmed.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } else trimmed
        }

        /** Normalises a typed category name; reuses a built-in key when the name matches one. */
        fun fromUserInput(name: String): String {
            val clean = name.trim().replace(Regex("\\s+"), " ").take(40)
            if (clean.isEmpty()) return GENERAL.name
            return entries.firstOrNull { !it.legacy && (it.displayName.equals(clean, true) || it.name.equals(clean, true)) }?.name ?: clean
        }
    }
}

/** Common units offered as quick picks in the product form. */
val CommonUnits = listOf("Piece", "Kg", "Box", "Pack", "Bottle", "Litre", "Dozen", "Bag", "Hour", "Service")

@JsonClass(generateAdapter = true)
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: String, // ProductCategory.name, e.g. "GENERAL", "FOOD_DRINKS", "SERVICES"
    val unit: String,     // e.g. "Piece", "Kg", "Box", "Hour"
    val unitPrice: Double,
    val costPrice: Double = 0.0, // Production / procurement cost per unit
    val stockQuantity: Double = 0.0, // Inventory on hand
    val minStockThreshold: Double = 5.0, // Reorder / low stock alert threshold
    val inStock: Boolean = true,
    val description: String = "",
    val imageUri: String = "", // Photo on this phone (file path), or a web URL
    val lastRestockedAt: Long = System.currentTimeMillis(),
    /**
     * Web address of the photo's copy on the cloud server. "" = not uploaded yet,
     * [REMOTE_IMAGE_REMOVED] = the user removed the photo (tells the server to clear it).
     */
    val remoteImageUrl: String = "",
    /** Last local or web edit (ms). Sync keeps the newer version; 0 = unknown / legacy. */
    val updatedAt: Long = 0L
) {
    companion object {
        const val REMOTE_IMAGE_REMOVED = "none"
    }

    /** Web copy usable for display, if any. */
    val remoteImageOrBlank: String
        get() = if (remoteImageUrl.startsWith("http://") || remoteImageUrl.startsWith("https://")) remoteImageUrl else ""
}

@JsonClass(generateAdapter = true)
@Entity(tableName = "inventory_logs")
data class InventoryLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: Long,
    val productName: String,
    val changeType: String, // "RESTOCK", "SALE", "DAMAGE", "CORRECTION", "ADJUSTMENT" (+ legacy farm types)
    val quantityChanged: Double,
    val quantityAfter: Double,
    val unit: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val whatsapp: String = "",
    val address: String = "",
    val notes: String = "",
    val totalPurchases: Double = 0.0,
    val totalPaid: Double = 0.0,
    val currentBalance: Double = 0.0, // Positive = owes money (debt)
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Debt and purchases not tied to any sale (e.g. owed from before using the app).
     * currentBalance = openingBalance + sum(sales.balanceDue) and
     * totalPurchases = openingPurchases + sum(sales.totalAmount); totalPaid = purchases - balance.
     * Derived this way so changes made on the phone and on the web add up after sync.
     */
    val openingBalance: Double = 0.0,
    val openingPurchases: Double = 0.0,
    val updatedAt: Long = 0L
)

@JsonClass(generateAdapter = true)
data class CartItem(
    val productId: Long,
    val productName: String,
    val category: String,
    val unit: String,
    val unitPrice: Double,
    val quantity: Double,
    val lineTotal: Double,
    val imageUri: String = ""
)

enum class PaymentStatus {
    PAID,       // Fully paid
    PARTIAL,    // Partially paid (deposit)
    UNPAID      // Credit / owing full amount
}

enum class PaymentMethod(val displayName: String) {
    CASH("Cash"),
    MOMO("Mobile Money (MTN/Telecel/AT)"),
    BANK_TRANSFER("Bank Transfer")
}

@JsonClass(generateAdapter = true)
@Entity(tableName = "sales_orders")
data class SaleOrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val invoiceNumber: String,
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val customerWhatsapp: String,
    val itemsJson: String, // JSON serialized List<CartItem>
    val totalAmount: Double,
    val discountAmount: Double = 0.0,
    val amountPaid: Double,
    val balanceDue: Double,
    val paymentStatus: String, // PAID, PARTIAL, UNPAID
    val paymentMethod: String, // CASH, MOMO, BANK_TRANSFER
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val smsSentCount: Int = 0,
    val lastSmsTimestamp: Long? = null,
    val updatedAt: Long = 0L
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "payments")
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val customerId: Long,
    val customerName: String,
    val saleOrderId: Long? = null,
    val amount: Double,
    val paymentMethod: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val balanceAfterPayment: Double = 0.0,
    val updatedAt: Long = 0L
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val businessName: String = "BizTrack Business",
    val businessTagline: String = "Quality products & reliable service",
    val businessPhone: String = "024 000 0000",
    val businessLocation: String = "Ghana",
    val momoPaymentDetails: String = "MTN MoMo: 0244XXXXXX (BizTrack)",
    val smsApiKey: String = "",
    val smsSenderId: String = "BizTrack",
    val smsAutoSendOnSale: Boolean = true,
    val smsAutoSendOnPayment: Boolean = true,
    val currencySymbol: String = "GH₵",
    // Namecheap MySQL Cloud Sync Settings
    val cloudSyncUrl: String = "",
    val cloudSyncSecretKey: String = "",
    val cloudAutoSyncOnSale: Boolean = false,
    val lastCloudSyncTime: Long = 0L,
    val lastCloudSyncStatus: String = "Not synced yet",
    /** When the user last edited these settings (not sync bookkeeping). Newer side wins on sync. */
    val updatedAt: Long = 0L
)

/**
 * Remembers that a record was deleted, so sync removes it on the other side instead of
 * bringing it back. tableName is the Room/MySQL table name (e.g. "sales_orders").
 */
@JsonClass(generateAdapter = true)
@Entity(tableName = "deleted_records", primaryKeys = ["tableName", "recordId"])
data class DeletedRecordEntity(
    val tableName: String,
    val recordId: Long,
    val deletedAt: Long
)
