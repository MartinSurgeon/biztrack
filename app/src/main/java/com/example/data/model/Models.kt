package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

enum class ProductCategory(val displayName: String) {
    EGGS("Eggs"),
    BROILER_LIVE("Live Broiler Chicken"),
    BROILER_DRESSED("Dressed Broiler Chicken"),
    SASSO_LIVE("Live Sasso Chicken"),
    SASSO_DRESSED("Dressed Sasso Chicken"),
    OTHER("Other Farm Products")
}

@JsonClass(generateAdapter = true)
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: String, // e.g. "EGGS", "BROILER_LIVE", "BROILER_DRESSED", "SASSO_LIVE", "SASSO_DRESSED", "OTHER"
    val unit: String,     // "Crate", "Piece", "Bird", "Kg"
    val unitPrice: Double,
    val costPrice: Double = 0.0, // Production / procurement cost per unit
    val stockQuantity: Double = 0.0, // Live inventory on hand (e.g. crates of eggs, number of birds)
    val minStockThreshold: Double = 5.0, // Reorder / low stock alert threshold
    val inStock: Boolean = true,
    val description: String = "",
    val imageUri: String = "", // Custom image URI or image URL or drawable key
    val lastRestockedAt: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
@Entity(tableName = "inventory_logs")
data class InventoryLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: Long,
    val productName: String,
    val changeType: String, // "RESTOCK", "EGG_COLLECTION", "SALE", "MORTALITY", "DAMAGE", "ADJUSTMENT"
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
    val createdAt: Long = System.currentTimeMillis()
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
    val lastSmsTimestamp: Long? = null
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
    val balanceAfterPayment: Double = 0.0
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
    val lastCloudSyncStatus: String = "Not synced yet"
)
