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
    private val inventoryLogDao: InventoryLogDao? = null
) {
    // Products & Inventory
    val allProducts: Flow<List<ProductEntity>> = productDao.getAllProducts()
    val inStockProducts: Flow<List<ProductEntity>> = productDao.getInStockProducts()
    val lowStockProducts: Flow<List<ProductEntity>> = productDao.getLowStockProducts()

    suspend fun insertProduct(product: ProductEntity): Long = productDao.insertProduct(product)
    suspend fun updateProduct(product: ProductEntity) = productDao.updateProduct(product)
    suspend fun deleteProduct(product: ProductEntity) = productDao.deleteProduct(product)
    suspend fun adjustProductStock(productId: Long, quantityChange: Double, logType: String = "ADJUSTMENT", notes: String = "") {
        productDao.adjustStock(productId, quantityChange)
        val product = productDao.getProductById(productId)
        if (product != null && inventoryLogDao != null) {
            inventoryLogDao.insertLog(
                InventoryLogEntity(
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
    suspend fun insertCustomer(customer: CustomerEntity): Long = customerDao.insertCustomer(customer)
    suspend fun updateCustomer(customer: CustomerEntity) = customerDao.updateCustomer(customer)
    suspend fun deleteCustomer(customer: CustomerEntity) = customerDao.deleteCustomer(customer)

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

        // Adjust customer balances
        if (order.customerId > 0) {
            customerDao.updateCustomerBalances(
                customerId = order.customerId,
                amountDiff = -order.balanceDue,
                purchasesDiff = -order.totalAmount,
                paidDiff = -order.amountPaid
            )
        }

        // Delete order
        saleOrderDao.deleteOrder(order)
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
                    name = customerName,
                    phone = customerPhone,
                    whatsapp = if (customerWhatsapp.isNotBlank()) customerWhatsapp else customerPhone,
                    notes = notes,
                    totalPurchases = 0.0,
                    totalPaid = 0.0,
                    currentBalance = 0.0
                )
                effectiveCustomerId = customerDao.insertCustomer(newCust)
            }
        }

        val order = SaleOrderEntity(
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
            timestamp = System.currentTimeMillis()
        )

        val orderId = saleOrderDao.insertOrder(order)
        val savedOrder = order.copy(id = orderId)

        // Update customer balances:
        // currentBalance += balanceDue, totalPurchases += totalAmount, totalPaid += amountPaid
        customerDao.updateCustomerBalances(
            customerId = effectiveCustomerId,
            amountDiff = balanceDue,
            purchasesDiff = totalAmount,
            paidDiff = amountPaid
        )

        // If there was an upfront payment recorded, also log into payments table
        if (amountPaid > 0.0) {
            val currentCust = customerDao.getCustomerById(effectiveCustomerId)
            paymentDao.insertPayment(
                PaymentEntity(
                    customerId = effectiveCustomerId,
                    customerName = customerName,
                    saleOrderId = orderId,
                    amount = amountPaid,
                    paymentMethod = paymentMethod.name,
                    notes = "Initial payment for Invoice #$invoiceNo",
                    balanceAfterPayment = currentCust?.currentBalance ?: balanceDue
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
            paymentStatus = calculatedStatus
        )

        // 3. Reconcile customer balances
        if (originalOrder.customerId == finalizedOrder.customerId) {
            if (finalizedOrder.customerId > 0) {
                val balanceDiff = finalizedOrder.balanceDue - originalOrder.balanceDue
                val purchasesDiff = finalizedOrder.totalAmount - originalOrder.totalAmount
                val paidDiff = finalizedOrder.amountPaid - originalOrder.amountPaid
                customerDao.updateCustomerBalances(
                    customerId = finalizedOrder.customerId,
                    amountDiff = balanceDiff,
                    purchasesDiff = purchasesDiff,
                    paidDiff = paidDiff
                )
            }
        } else {
            // Customer changed: revert old customer and apply to new customer
            if (originalOrder.customerId > 0) {
                customerDao.updateCustomerBalances(
                    customerId = originalOrder.customerId,
                    amountDiff = -originalOrder.balanceDue,
                    purchasesDiff = -originalOrder.totalAmount,
                    paidDiff = -originalOrder.amountPaid
                )
            }
            if (finalizedOrder.customerId > 0) {
                customerDao.updateCustomerBalances(
                    customerId = finalizedOrder.customerId,
                    amountDiff = finalizedOrder.balanceDue,
                    purchasesDiff = finalizedOrder.totalAmount,
                    paidDiff = finalizedOrder.amountPaid
                )
            }
        }

        // 4. Update the order in the database
        saleOrderDao.updateOrder(finalizedOrder)

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
                    paymentStatus = newStatus
                )
            )
            lastAllocatedOrderId = order.id
            remainingToAllocate -= paymentForThisOrder
        }

        // Log payment in ledger
        paymentDao.insertPayment(
            PaymentEntity(
                customerId = customerId,
                customerName = customer.name,
                saleOrderId = lastAllocatedOrderId,
                amount = amount,
                paymentMethod = paymentMethod.name,
                notes = notes.ifBlank { "Debt payment" },
                timestamp = System.currentTimeMillis(),
                balanceAfterPayment = newCustomerBalance
            )
        )

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
                paymentStatus = newStatus
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
                customerId = order.customerId,
                customerName = order.customerName,
                saleOrderId = order.id,
                amount = actualPayment,
                paymentMethod = paymentMethod.name,
                notes = notes.ifBlank { "Installment for Invoice #${order.invoiceNumber}" },
                timestamp = System.currentTimeMillis(),
                balanceAfterPayment = newCustomerBalance
            )
        )

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
                lastSmsTimestamp = System.currentTimeMillis()
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
