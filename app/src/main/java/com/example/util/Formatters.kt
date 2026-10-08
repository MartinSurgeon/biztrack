package com.example.util

import com.example.data.model.AppSettingsEntity
import com.example.data.model.CartItem
import com.example.data.model.CustomerEntity
import com.example.data.model.SaleOrderEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val cartItemListType = Types.newParameterizedType(List::class.java, CartItem::class.java)
    private val cartItemAdapter = moshi.adapter<List<CartItem>>(cartItemListType)

    fun parseCartItems(json: String): List<CartItem> {
        return try {
            cartItemAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeCartItems(items: List<CartItem>): String {
        return cartItemAdapter.toJson(items)
    }

    fun formatCurrency(amount: Double, symbol: String = "GH₵"): String {
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "$symbol ${formatter.format(amount)}"
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatDateShort(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Builds a concise, clean WhatsApp Receipt with farm branding and order details.
     */
    fun buildWhatsAppReceipt(
        order: SaleOrderEntity,
        settings: AppSettingsEntity
    ): String {
        val items = parseCartItems(order.itemsJson)
        val sb = StringBuilder()

        sb.append("🧾 *${settings.businessName.uppercase()}*\n")
        if (settings.businessTagline.isNotBlank()) {
            sb.append("_${settings.businessTagline}_\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("Inv: *#${order.invoiceNumber}* • ${formatDateShort(order.timestamp)}\n")
        sb.append("Customer: *${order.customerName}*\n")
        sb.append("────────────────────\n")

        items.forEach { item ->
            val qtyStr = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString()
            sb.append("• *${item.productName}*: $qtyStr ${item.unit} @ ${formatCurrency(item.unitPrice, settings.currencySymbol)} = *${formatCurrency(item.lineTotal, settings.currencySymbol)}*\n")
        }

        sb.append("────────────────────\n")
        val subtotal = items.sumOf { it.lineTotal }
        if (order.discountAmount > 0) {
            sb.append("🛒 *Subtotal:* ${formatCurrency(subtotal, settings.currencySymbol)}\n")
            sb.append("🏷️ *Discount Granted:* -${formatCurrency(order.discountAmount, settings.currencySymbol)}\n")
            sb.append("💰 *TOTAL (Discounted):* *${formatCurrency(order.totalAmount, settings.currencySymbol)}*\n")
        } else {
            sb.append("💰 *TOTAL:* ${formatCurrency(order.totalAmount, settings.currencySymbol)}\n")
        }
        sb.append("💵 *PAID:* ${formatCurrency(order.amountPaid, settings.currencySymbol)}\n")

        if (order.balanceDue > 0) {
            val statusLabel = if (order.amountPaid > 0) "PARTIAL (Owing)" else "UNPAID (Owing)"
            sb.append("⚠️ *BALANCE DUE:* *${formatCurrency(order.balanceDue, settings.currencySymbol)}* ($statusLabel)\n")
        } else {
            sb.append("✅ *STATUS:* PAID IN FULL\n")
        }

        if (settings.momoPaymentDetails.isNotBlank()) {
            sb.append("💳 MoMo: ${settings.momoPaymentDetails}\n")
        }
        if (settings.businessPhone.isNotBlank()) {
            sb.append("📞 Tel: ${settings.businessPhone}\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("_Thank you for choosing our farm Products, we look forward to serving you again!_")

        return sb.toString()
    }

    /**
     * Builds a WhatsApp Account Statement for a customer.
     */
    fun buildWhatsAppCustomerStatement(
        customer: CustomerEntity,
        orders: List<SaleOrderEntity>,
        settings: AppSettingsEntity
    ): String {
        val sb = StringBuilder()
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("🐔 *${settings.businessName.uppercase()}*\n")
        sb.append("✨ _\"${settings.businessTagline}\"_\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("📑 *CUSTOMER STATEMENT*\n")
        sb.append("👤 Customer: *${customer.name}*\n")
        sb.append("📞 Phone: ${customer.phone}\n")
        sb.append("📅 Date: ${formatDate(System.currentTimeMillis())}\n")
        sb.append("────────────────────\n")
        sb.append("📊 *SUMMARY:*\n")
        sb.append("• Total Purchases: ${formatCurrency(customer.totalPurchases, settings.currencySymbol)}\n")
        sb.append("• Total Paid: ${formatCurrency(customer.totalPaid, settings.currencySymbol)}\n")
        
        if (customer.currentBalance > 0) {
            sb.append("⚠️ *CURRENT OUTSTANDING DEBT:* *${formatCurrency(customer.currentBalance, settings.currencySymbol)}*\n")
        } else {
            sb.append("✅ *CURRENT BALANCE:* Cleared (0.00)\n")
        }

        if (orders.isNotEmpty()) {
            sb.append("────────────────────\n")
            sb.append("*RECENT TRANSACTIONS:*\n")
            orders.take(5).forEach { order ->
                val statusText = if (order.balanceDue > 0) "Owing ${formatCurrency(order.balanceDue, settings.currencySymbol)}" else "Paid"
                sb.append("• ${formatDateShort(order.timestamp)} [${order.invoiceNumber}]: ${formatCurrency(order.totalAmount, settings.currencySymbol)} ($statusText)\n")
            }
        }

        sb.append("────────────────────\n")
        if (settings.momoPaymentDetails.isNotBlank()) {
            sb.append("💳 *Payment Info:*\n${settings.momoPaymentDetails}\n")
        }
        if (settings.businessPhone.isNotBlank()) {
            sb.append("📞 Contact: ${settings.businessPhone}\n")
        }
        sb.append("\n_Thank you for choosing our farm Products, we look forward to serving you again!_\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━")

        return sb.toString()
    }

    /**
     * Builds SMS text for Purchase Acknowledgment.
     */
    fun buildPurchaseSms(order: SaleOrderEntity, settings: AppSettingsEntity): String {
        val items = parseCartItems(order.itemsJson)
        val itemsSummary = if (items.isNotEmpty()) {
            items.joinToString(", ") { "${if (it.quantity % 1.0 == 0.0) it.quantity.toInt() else it.quantity} ${it.productName}" }
        } else {
            "farm products"
        }

        val discountNote = if (order.discountAmount > 0) " (Discount: -${formatCurrency(order.discountAmount, settings.currencySymbol)})" else ""

        return if (order.balanceDue > 0) {
            "Thank you ${order.customerName} for buying from ${settings.businessName}! Total: ${formatCurrency(order.totalAmount, settings.currencySymbol)}$discountNote, Paid: ${formatCurrency(order.amountPaid, settings.currencySymbol)}, Balance Due: ${formatCurrency(order.balanceDue, settings.currencySymbol)}. Inv: #${order.invoiceNumber}. ${settings.businessTagline}."
        } else {
            "Thank you ${order.customerName} for your purchase from ${settings.businessName}! Total: ${formatCurrency(order.totalAmount, settings.currencySymbol)}$discountNote (Paid in full). Inv: #${order.invoiceNumber}. ${settings.businessTagline}."
        }
    }

    /**
     * Builds SMS text for Payment Reminder (Debt) in simple, plain language.
     * Clearly breaks down old purchases vs today/new purchases without accounting jargon.
     */
    fun buildDebtReminderSms(
        customerName: String,
        balance: Double,
        settings: AppSettingsEntity,
        unpaidOrders: List<SaleOrderEntity> = emptyList()
    ): String {
        val currency = settings.currencySymbol
        val momo = if (settings.momoPaymentDetails.isNotBlank()) " Pay via MoMo to ${settings.momoPaymentDetails}." else ""
        val tel = if (settings.businessPhone.isNotBlank()) " Tel: ${settings.businessPhone}." else ""
        
        if (unpaidOrders.size >= 2) {
            val oldest = unpaidOrders.first()
            val newest = unpaidOrders.last()
            val breakdownStr = " (Old: $currency${formatCurrency(oldest.balanceDue, "")}, Recent: $currency${formatCurrency(newest.balanceDue, "")})"
            return "Hello $customerName, friendly reminder from ${settings.businessName}. Your outstanding balance for egg/poultry purchases is $currency${formatCurrency(balance, "")}$breakdownStr.$momo$tel Thank you!"
        }

        return "Hello $customerName, friendly reminder from ${settings.businessName} regarding your outstanding balance of ${formatCurrency(balance, currency)}.$momo$tel Thank you for choosing our farm Products, we look forward to serving you again!"
    }

    /**
     * Builds SMS text for Payment Acknowledgment.
     */
    fun buildPaymentReceivedSms(
        customerName: String,
        amountReceived: Double,
        remainingBalance: Double,
        settings: AppSettingsEntity
    ): String {
        return if (remainingBalance > 0) {
            "Payment of ${formatCurrency(amountReceived, settings.currencySymbol)} received with thanks, $customerName! Remaining balance: ${formatCurrency(remainingBalance, settings.currencySymbol)}. ${settings.businessName} - ${settings.businessTagline}."
        } else {
            "Payment of ${formatCurrency(amountReceived, settings.currencySymbol)} received with thanks, $customerName! Your account is now fully cleared. ${settings.businessName} - ${settings.businessTagline}."
        }
    }
}
