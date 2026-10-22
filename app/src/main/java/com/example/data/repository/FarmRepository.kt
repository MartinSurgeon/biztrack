package com.example.data.repository

import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.SmsBalanceResult
import com.example.data.remote.SmsSendResult
import com.example.data.remote.SmsService
import com.example.util.Formatters
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FarmRepository(
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val saleOrderDao: SaleOrderDao,
    private val paymentDao: PaymentDao,
    private val appSettingsDao: AppSettingsDao,
    private val inventoryLogDao: InventoryLogDao? = null,
    private val deletedRecordDao: DeletedRecordDao? = null
) {
    /*
     * Sync rules (see DatabaseBackupHelper.mergeBackup and biztrack_core.php):
     * - every insert/edit stamps updatedAt, so the newer version wins on the other side;
     * - every stock change is an inventory log, applied once on the other side;
     * - customer totals are derived from sales (recalculateCustomers);
     * - deletes leave a DeletedRecord so the other side deletes too.
     */
    private fun now() = System.currentTimeMillis()

    private suspend fun recordDeletion(table: String, id: Long) {
        deletedRecordDao?.insert(DeletedRecordEntity(table, id, now()))
    }

    /** Re-derives every customer's balance, purchases and paid amount from their sales. */
    suspend fun recalculateCustomers() {
        customerDao.recalculateBalances()
        customerDao.recalculatePaid()
    }

    // Products & Inventory
    val allProducts: Flow<List<ProductEntity>> = productDao.getAllProducts()
    val inStockProducts: Flow<List<ProductEntity>> = productDao.getInStockProducts()
    val lowStockProducts: Flow<List<ProductEntity>> = productDao.getLowStockProducts()

    /** New product; its opening stock is logged so the other side counts it exactly once. */
    suspend fun insertProduct(product: ProductEntity): Long {
        val p = product.copy(
            id = if (product.id == 0L) RecordIds.next(RecordIds.PRODUCTS) else product.id,
            updatedAt = now()
        )
        val id = productDao.insertProduct(p)
        if (p.stockQuantity != 0.0) logStock(p.copy(id = id), p.stockQuantity, p.stockQuantity, "RESTOCK", "Opening stock")
        return id
    }

    /**
     * Saves product details. A changed stock number is applied as an ADJUSTMENT log (a
     * difference), never by overwriting, so it merges with sales made elsewhere.
     */
    suspend fun updateProduct(product: ProductEntity) {
        val stored = productDao.getProductById(product.id)
        if (stored == null) {
            insertProduct(product)
            return
        }
        productDao.updateProduct(product.copy(stockQuantity = stored.stockQuantity, updatedAt = now()))
        val delta = product.stockQuantity - stored.stockQuantity
        if (kotlin.math.abs(delta) > 1e-9) {
            adjustProductStock(product.id, delta, "ADJUSTMENT", "Stock set to ${product.stockQuantity}")
        }
    }

    suspend fun deleteProduct(product: ProductEntity) {
        productDao.deleteProduct(product)
        recordDeletion(RecordIds.PRODUCTS, product.id)
    }

    private suspend fun logStock(product: ProductEntity, change: Double, after: Double, type: String, notes: String) {
        inventoryLogDao?.insertLog(
            InventoryLogEntity(
                id = RecordIds.next(RecordIds.INVENTORY_LOGS),
                productId = product.id,
                productName = product.name,
                changeType = type,
                quantityChanged = change,
                quantityAfter = after,
                unit = product.unit,
                notes = notes
            )
        )
    }

    suspend fun adjustProductStock(productId: Long, quantityChange: Double, logType: String = "ADJUSTMENT", notes: String = "") {
        productDao.adjustStock(productId, quantityChange)
        val product = productDao.getProductById(productId)
        if (product != null && inventoryLogDao != null) {
            inventoryLogDao.insertLog(
                InventoryLogEntity(
                    id = RecordIds.next(RecordIds.INVENTORY_LOGS),
                    productId = productId,
                    productName = product.name,
                    changeType = logType,
                    quantityChanged = quantityChange,
                    quantityAfter = product.stockQuantity,
                    unit = product.unit,
                    notes = notes
                )
            )
        }
    }

    // Inventory Logs
    val inventoryLogs: Flow<List<InventoryLogEntity>> = inventoryLogDao?.getAllLogs() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    // Customers
    val allCustomers: Flow<List<CustomerEntity>> = customerDao.getAllCustomers()
    val debtors: Flow<List<CustomerEntity>> = customerDao.getDebtors()
    val totalOutstandingDebt: Flow<Double?> = customerDao.getTotalOutstandingDebt()

    suspend fun getCustomerById(id: Long): CustomerEntity? = customerDao.getCustomerById(id)
    /** New customer. Any starting balance/purchases (no sales yet) become their opening amounts. */
    suspend fun insertCustomer(customer: CustomerEntity): Long {
        val c = customer.copy(
            id = if (customer.id == 0L) RecordIds.next(RecordIds.CUSTOMERS) else customer.id,
            openingBalance = customer.openingBalance.takeIf { it != 0.0 } ?: customer.currentBalance,
            openingPurchases = customer.openingPurchases.takeIf { it != 0.0 } ?: customer.totalPurchases,
            updatedAt = now()
        )
        val id = customerDao.insertCustomer(c)
        recalculateCustomers()
        return id
    }

    suspend fun updateCustomer(customer: CustomerEntity) {
        customerDao.updateCustomer(customer.copy(updatedAt = now()))
        recalculateCustomers() // totals come from sales, not from the (possibly stale) form copy
    }

    suspend fun deleteCustomer(customer: CustomerEntity) {
        customerDao.deleteCustomer(customer)
        recordDeletion(RecordIds.CUSTOMERS, customer.id)
    }

    // Sales Orders
    val allOrders: Flow<List<SaleOrderEntity>> = saleOrderDao.getAllOrders()
    val unpaidOrders: Flow<List<SaleOrderEntity>> = saleOrderDao.getUnpaidOrders()
    val totalRevenue: Flow<Double?> = saleOrderDao.getTotalRevenue()
    val totalCollected: Flow<Double?> = saleOrderDao.getTotalCollected()
    val totalSalesCount: Flow<Int> = saleOrderDao.getTotalSalesCount()

    fun getOrdersForCustomer(customerId: Long): Flow<List<SaleOrderEntity>> =
        saleOrderDao.getOrdersForCustomer(customerId)

    suspend fun getOrderById(id: Long): SaleOrderEntity? = saleOrderDao.getOrderById(id)

    // Payments
    val allPayments: Flow<List<PaymentEntity>> = paymentDao.getAllPayments()
    fun getPaymentsForCustomer(customerId: Long): Flow<List<PaymentEntity>> =
        paymentDao.getPaymentsForCustomer(customerId)
    suspend fun getPaymentsForCustomerDirect(customerId: Long): List<PaymentEntity> =
        paymentDao.getPaymentsForCustomerDirect(customerId)

    /**
     * Voids / deletes a sales order with complete reversal:
     * 1. Restores product inventory stock for all line items.
     * 2. Reverses customer balances (purchases, paid upfront, and remaining debt).
     * 3. Removes the sale order from the database.
     */
    suspend fun voidSaleOrder(order: SaleOrderEntity) {
        // Restore stock
        val items = Formatters.parseCartItems(order.itemsJson)
        for (item in items) {
            adjustProductStock(
                productId = item.productId,
                quantityChange = item.quantity,
                logType = "VOID_RESTORE",
                notes = "Stock restored from voided Invoice #${order.invoiceNumber}"
            )
        }

        // Delete order (recorded so sync deletes it elsewhere too), then re-derive balances.
        saleOrderDao.deleteOrder(order)
        recordDeletion(RecordIds.SALES, order.id)
        recalculateCustomers()
    }

    // App Settings
    val settings: Flow<AppSettingsEntity?> = appSettingsDao.getSettings()
    suspend fun getSettingsDirect(): AppSettingsEntity =
        appSettingsDao.getSettingsDirect() ?: AppSettingsEntity()

    suspend fun saveSettings(settings: AppSettingsEntity) = appSettingsDao.saveSettings(settings)

    /**
     * Creates a new Sale and updates customer balance accordingly.
     */
    suspend fun createSaleOrder(
        customerId: Long,
        customerName: String,
        customerPhone: String,
        customerWhatsapp: String,
        itemsJson: String,
        totalAmount: Double,
        discountAmount: Double = 0.0,
        amountPaid: Double,
        paymentMethod: PaymentMethod,
        notes: String
    ): SaleOrderEntity {
        val balanceDue = (totalAmount - amountPaid).coerceAtLeast(0.0)
        val status = when {
            balanceDue <= 0.0 -> PaymentStatus.PAID.name
            amountPaid > 0.0 -> PaymentStatus.PARTIAL.name
            else -> PaymentStatus.UNPAID.name
        }

        // Generate Invoice Number NE-YYYYMMDD-HHMMSS
        val timeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        val invoiceNo = "NE-${timeFormat.format(Date())}"

        var effectiveCustomerId = customerId

        // If new customer (id <= 0), create or find customer
        if (effectiveCustomerId <= 0) {
            val existing = customerDao.getCustomerByPhone(customerPhone)
            if (existing != null) {
                effectiveCustomerId = existing.id
            } else {
                val newCust = CustomerEntity(
                    id = RecordIds.next(RecordIds.CUSTOMERS),
                    name = customerName,
                    phone = customerPhone,
                    whatsapp = if (customerWhatsapp.isNotBlank()) customerWhatsapp else customerPhone,
                    notes = notes,
                    totalPurchases = 0.0,
                    totalPaid = 0.0,
                    currentBalance = 0.0,
                    updatedAt = System.currentTimeMillis()
                )
                effectiveCustomerId = customerDao.insertCustomer(newCust)
            }
        }

        val order = SaleOrderEntity(
            id = RecordIds.next(RecordIds.SALES),
            invoiceNumber = invoiceNo,
            customerId = effectiveCustomerId,
            customerName = customerName,
            customerPhone = customerPhone,
            customerWhatsapp = if (customerWhatsapp.isNotBlank()) customerWhatsapp else customerPhone,
            itemsJson = itemsJson,
            totalAmount = totalAmount,
            discountAmount = discountAmount,
            amountPaid = amountPaid,
            balanceDue = balanceDue,
            paymentStatus = status,
            paymentMethod = paymentMethod.name,
            notes = notes,
            timestamp = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val orderId = saleOrderDao.insertOrder(order)
        val savedOrder = order.copy(id = orderId)

        // Take the sold items out of stock. Each change is logged, so it syncs as a difference.
        for (item in Formatters.parseCartItems(itemsJson)) {
            adjustProductStock(item.productId, -item.quantity, "SALE", "Sold on Invoice #$invoiceNo")
        }

        // Customer totals are derived from sales.
        recalculateCustomers()

        // If there was an upfront payment recorded, also log into payments table
        if (amountPaid > 0.0) {
            val currentCust = customerDao.getCustomerById(effectiveCustomerId)
            paymentDao.insertPayment(
                PaymentEntity(
                    id = RecordIds.next(RecordIds.PAYMENTS),
                    customerId = effectiveCustomerId,
                    customerName = customerName,
                    saleOrderId = orderId,
                    amount = amountPaid,
                    paymentMethod = paymentMethod.name,
                    notes = "Initial payment for Invoice #$invoiceNo",
                    balanceAfterPayment = currentCust?.currentBalance ?: balanceDue,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        return savedOrder
    }

    /**
     * Updates an existing Sale Order, reconciling inventory differences and customer balances.
     */
    suspend fun updateSaleOrder(
        updatedOrder: SaleOrderEntity,
        originalOrder: SaleOrderEntity
    ): SaleOrderEntity {
        // 1. Reconcile inventory differences
        val oldItems = Formatters.parseCartItems(originalOrder.itemsJson)
        val newItems = Formatters.parseCartItems(updatedOrder.itemsJson)

        val oldItemMap = oldItems.associateBy { it.productId }
        val newItemMap = newItems.associateBy { it.productId }
        val allProductIds = (oldItemMap.keys + newItemMap.keys).toSet()

        for (pId in allProductIds) {
            val oldQty = oldItemMap[pId]?.quantity ?: 0.0
            val newQty = newItemMap[pId]?.quantity ?: 0.0
            val stockDelta = oldQty - newQty
            if (stockDelta != 0.0) {
                adjustProductStock(
                    productId = pId,
                    quantityChange = stockDelta,
                    logType = "INVOICE_EDIT",
                    notes = "Adjusted for edited Invoice #${originalOrder.invoiceNumber}"
                )
            }
        }

        // 2. Compute accurate payment status and balance
        val calculatedBalance = (updatedOrder.totalAmount - updatedOrder.amountPaid).coerceAtLeast(0.0)
        val calculatedStatus = when {
            calculatedBalance <= 0.0 -> PaymentStatus.PAID.name
            updatedOrder.amountPaid > 0.0 -> PaymentStatus.PARTIAL.name
            else -> PaymentStatus.UNPAID.name
        }

        val finalizedOrder = updatedOrder.copy(
            balanceDue = calculatedBalance,
            paymentStatus = calculatedStatus,
            updatedAt = System.currentTimeMillis()
        )

        // 3. Save, then re-derive customer totals (handles a changed customer too).
        saleOrderDao.updateOrder(finalizedOrder)
        recalculateCustomers()

        return finalizedOrder
    }

    /**
     * Records an installment or full payment against a customer's debt.
     * Updates customer balance and distributes payment across outstanding sales orders
     * (FIFO oldest first), maintaining cumulative amountPaid, remaining balanceDue,
     * and accurate PaymentStatus (PAID, PARTIAL, UNPAID).
     */
    suspend fun recordCustomerPayment(
        customerId: Long,
        amount: Double,
        paymentMethod: PaymentMethod,
        notes: String
    ): Double {
        if (amount <= 0.0) return 0.0
        val customer = customerDao.getCustomerById(customerId) ?: return 0.0
        val newCustomerBalance = (customer.currentBalance - amount).coerceAtLeast(0.0)
        
        // Update customer balance: reduce currentBalance, increase totalPaid
        customerDao.updateCustomerBalances(
            customerId = customerId,
            amountDiff = -amount,
            purchasesDiff = 0.0,
            paidDiff = amount
        )

        // Distribute installment payment across customer's unpaid/partially paid orders (oldest first)
        var remainingToAllocate = amount
        val unpaidOrders = saleOrderDao.getUnpaidOrdersForCustomerDirect(customerId)
        var lastAllocatedOrderId: Long? = null

        for (order in unpaidOrders) {
            if (remainingToAllocate <= 0.0) break
            val paymentForThisOrder = minOf(remainingToAllocate, order.balanceDue)
            val newAmountPaid = (order.amountPaid + paymentForThisOrder).coerceAtMost(order.totalAmount)
            val newBalanceDue = (order.totalAmount - newAmountPaid).coerceAtLeast(0.0)
            val newStatus = when {
                newBalanceDue <= 0.0 -> PaymentStatus.PAID.name
                newAmountPaid > 0.0 -> PaymentStatus.PARTIAL.name
                else -> PaymentStatus.UNPAID.name
            }

            saleOrderDao.updateOrder(
                order.copy(
                    amountPaid = newAmountPaid,
                    balanceDue = newBalanceDue,
                    paymentStatus = newStatus,
                    updatedAt = System.currentTimeMillis()
                )
            )
            lastAllocatedOrderId = order.id
            remainingToAllocate -= paymentForThisOrder
        }

        // Log payment in ledger
        paymentDao.insertPayment(
            PaymentEntity(
                id = RecordIds.next(RecordIds.PAYMENTS),
                customerId = customerId,
                customerName = customer.name,
                saleOrderId = lastAllocatedOrderId,
                amount = amount,
                paymentMethod = paymentMethod.name,
                notes = notes.ifBlank { "Debt payment" },
                timestamp = System.currentTimeMillis(),
                balanceAfterPayment = newCustomerBalance,
                updatedAt = System.currentTimeMillis()
            )
        )

        // Any amount beyond the unpaid sales pays off the opening (pre-app) balance.
        if (remainingToAllocate > 0.0 && customer.openingBalance > 0.0) {
            customerDao.updateCustomer(
                customer.copy(
                    openingBalance = (customer.openingBalance - remainingToAllocate).coerceAtLeast(0.0),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        recalculateCustomers()

        return newCustomerBalance
    }

    /**
     * Records a payment directly against a specific sales order.
     */
    suspend fun recordOrderPayment(
        orderId: Long,
        amount: Double,
        paymentMethod: PaymentMethod,
        notes: String
    ): Double {
        if (amount <= 0.0) return 0.0
        val order = saleOrderDao.getOrderById(orderId) ?: return 0.0
        val actualPayment = minOf(amount, order.balanceDue)
        val newAmountPaid = (order.amountPaid + actualPayment).coerceAtMost(order.totalAmount)
        val newBalanceDue = (order.totalAmount - newAmountPaid).coerceAtLeast(0.0)
        val newStatus = when {
            newBalanceDue <= 0.0 -> PaymentStatus.PAID.name
            newAmountPaid > 0.0 -> PaymentStatus.PARTIAL.name
            else -> PaymentStatus.UNPAID.name
        }

        saleOrderDao.updateOrder(
            order.copy(
                amountPaid = newAmountPaid,
                balanceDue = newBalanceDue,
                paymentStatus = newStatus,
                updatedAt = System.currentTimeMillis()
            )
        )

        // Also update the customer's total balance & paid amounts
        val customer = customerDao.getCustomerById(order.customerId)
        val newCustomerBalance = if (customer != null) {
            customerDao.updateCustomerBalances(
                customerId = customer.id,
                amountDiff = -actualPayment,
                purchasesDiff = 0.0,
                paidDiff = actualPayment
            )
            (customer.currentBalance - actualPayment).coerceAtLeast(0.0)
        } else {
            newBalanceDue
        }

        paymentDao.insertPayment(
            PaymentEntity(
                id = RecordIds.next(RecordIds.PAYMENTS),
                customerId = order.customerId,
                customerName = order.customerName,
                saleOrderId = order.id,
                amount = actualPayment,
                paymentMethod = paymentMethod.name,
                notes = notes.ifBlank { "Installment for Invoice #${order.invoiceNumber}" },
                timestamp = System.currentTimeMillis(),
                balanceAfterPayment = newCustomerBalance,
                updatedAt = System.currentTimeMillis()
            )
        )
        recalculateCustomers()

        return newBalanceDue
    }

    /**
     * Sends an SMS via SMSOnlineGH
     */
    suspend fun sendSmsViaSmsOnlineGh(
        recipientPhone: String,
        message: String
    ): SmsSendResult {
        val currentSettings = getSettingsDirect()
        return SmsService.sendSmsOnlineGh(
            apiKey = currentSettings.smsApiKey,
            senderId = currentSettings.smsSenderId,
            recipientPhone = recipientPhone,
            message = message
        )
    }

    /**
     * Checks account balance / SMS units via SMSOnlineGH
     */
    suspend fun checkSmsBalance(customApiKey: String? = null): SmsBalanceResult {
        val key = customApiKey ?: getSettingsDirect().smsApiKey
        return SmsService.checkAccountBalance(key)
    }

    suspend fun markSmsSent(orderId: Long) {
        val order = saleOrderDao.getOrderById(orderId) ?: return
        saleOrderDao.updateOrder(
            order.copy(
                smsSentCount = order.smsSentCount + 1,
                lastSmsTimestamp = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Enqueue a reliable background SMS with WorkManager (network constraint & exponential backoff retries).
     */
    fun enqueueBackgroundSms(
        context: android.content.Context,
        recipientPhone: String,
        message: String,
        orderId: Long = 0L,
        customerName: String = "",
        smsType: String = "Receipt"
    ) {
        com.example.util.work.SmsWorkScheduler.enqueueSms(
            context = context,
            recipientPhone = recipientPhone,
            messageText = message,
            orderId = orderId,
            customerName = customerName,
            smsType = smsType
        )
    }
}
