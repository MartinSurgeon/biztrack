package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.remote.CloudSyncResult
import com.example.data.remote.CloudSyncService
import com.example.data.remote.SmsBalanceResult
import com.example.data.remote.SmsSendResult
import com.example.data.remote.SmsService
import com.example.data.repository.FarmRepository
import com.example.util.DatabaseBackupHelper
import com.example.util.Formatters
import com.example.util.NotificationHelper
import com.example.util.work.BackupWorkScheduler
import com.example.widget.FarmWidgetProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class SmsPreviewState(
    val recipientName: String,
    val recipientPhone: String,
    val messageText: String,
    val messageType: String, // "PURCHASE", "REMINDER", "PAYMENT_ACK"
    val relatedOrderId: Long? = null,
    val relatedCustomerId: Long? = null
)

class FarmViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)

    private val repository = FarmRepository(
        productDao = db.productDao(),
        customerDao = db.customerDao(),
        saleOrderDao = db.saleOrderDao(),
        paymentDao = db.paymentDao(),
        appSettingsDao = db.appSettingsDao(),
        inventoryLogDao = db.inventoryLogDao()
    )

    // Backup & Restore State Flows
    private val _lastBackupTime = MutableStateFlow(0L)
    val lastBackupTime: StateFlow<Long> = _lastBackupTime.asStateFlow()

    private val _lastBackupStatus = MutableStateFlow("Auto-backup scheduled")
    val lastBackupStatus: StateFlow<String> = _lastBackupStatus.asStateFlow()

    private val _lastBackupCount = MutableStateFlow(0)
    val lastBackupCount: StateFlow<Int> = _lastBackupCount.asStateFlow()

    private val _savedBackupFiles = MutableStateFlow<List<BackupFileInfo>>(emptyList())
    val savedBackupFiles: StateFlow<List<BackupFileInfo>> = _savedBackupFiles.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    // Cloud Sync State Flows
    private val _isCloudSyncing = MutableStateFlow(false)
    val isCloudSyncing: StateFlow<Boolean> = _isCloudSyncing.asStateFlow()

    private val _cloudSyncStatusMessage = MutableStateFlow<String?>(null)
    val cloudSyncStatusMessage: StateFlow<String?> = _cloudSyncStatusMessage.asStateFlow()

    val products: StateFlow<List<ProductEntity>> = repository.allProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lowStockProducts: StateFlow<List<ProductEntity>> = repository.lowStockProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryLogs: StateFlow<List<InventoryLogEntity>> = repository.inventoryLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers: StateFlow<List<CustomerEntity>> = repository.allCustomers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debtors: StateFlow<List<CustomerEntity>> = repository.debtors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val orders: StateFlow<List<SaleOrderEntity>> = repository.allOrders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPayments: StateFlow<List<PaymentEntity>> = repository.allPayments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettingsEntity> = repository.settings
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsEntity())

    val totalOutstandingDebt: StateFlow<Double> = repository.totalOutstandingDebt
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalRevenue: StateFlow<Double> = repository.totalRevenue
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalCollected: StateFlow<Double> = repository.totalCollected
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalSalesCount: StateFlow<Int> = repository.totalSalesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Current Cart State for New Sale
    private val _cart = MutableStateFlow<Map<Long, CartItem>>(emptyMap())
    val cart: StateFlow<Map<Long, CartItem>> = _cart.asStateFlow()

    // Active customer selection for new sale
    val selectedCustomer = MutableStateFlow<CustomerEntity?>(null)
    val customCustomerName = MutableStateFlow("")
    val customCustomerPhone = MutableStateFlow("")
    val customCustomerWhatsapp = MutableStateFlow("")
    val discountInput = MutableStateFlow("")
    val amountPaidInput = MutableStateFlow("")
    val paymentMethod = MutableStateFlow(PaymentMethod.CASH)
    val saleNotes = MutableStateFlow("")

    // UI Feedback & Dialogs
    val activeReceiptOrder = MutableStateFlow<SaleOrderEntity?>(null)
    val orderToEdit = MutableStateFlow<SaleOrderEntity?>(null)
    val smsPreview = MutableStateFlow<SmsPreviewState?>(null)
    val customerForPayment = MutableStateFlow<CustomerEntity?>(null)
    val customerForDetail = MutableStateFlow<CustomerEntity?>(null)
    val customerForPaymentHistory = MutableStateFlow<CustomerEntity?>(null)
    val orderForPayment = MutableStateFlow<SaleOrderEntity?>(null)

    private val _statusMessage = MutableSharedFlow<String>()
    val statusMessage = _statusMessage.asSharedFlow()

    private val _isSendingSms = MutableStateFlow(false)
    val isSendingSms: StateFlow<Boolean> = _isSendingSms.asStateFlow()

    init {
        // Schedule periodic 24-hour WorkManager auto-backup
        try {
            BackupWorkScheduler.schedulePeriodicBackup(application)
        } catch (e: Exception) {
            android.util.Log.e("FarmViewModel", "Failed to schedule periodic backup", e)
        }
        try {
            loadBackupInfo()
        } catch (e: Exception) {
            android.util.Log.e("FarmViewModel", "Failed to load backup info", e)
        }
    }

    fun updateCartItemQuantity(product: ProductEntity, delta: Double) {
        val current = _cart.value.toMutableMap()
        val existing = current[product.id]
        val newQty = (existing?.quantity ?: 0.0) + delta

        if (newQty <= 0.0) {
            current.remove(product.id)
        } else {
            current[product.id] = CartItem(
                productId = product.id,
                productName = product.name,
                category = product.category,
                unit = product.unit,
                unitPrice = product.unitPrice,
                quantity = newQty,
                lineTotal = newQty * product.unitPrice,
                imageUri = product.imageUri
            )
        }
        _cart.value = current
    }

    fun setCartItemQuantityDirect(product: ProductEntity, quantity: Double) {
        val current = _cart.value.toMutableMap()
        if (quantity <= 0.0) {
            current.remove(product.id)
        } else {
            current[product.id] = CartItem(
                productId = product.id,
                productName = product.name,
                category = product.category,
                unit = product.unit,
                unitPrice = product.unitPrice,
                quantity = quantity,
                lineTotal = quantity * product.unitPrice,
                imageUri = product.imageUri
            )
        }
        _cart.value = current
    }

    fun clearCart() {
        _cart.value = emptyMap()
        selectedCustomer.value = null
        customCustomerName.value = ""
        customCustomerPhone.value = ""
        customCustomerWhatsapp.value = ""
        discountInput.value = ""
        amountPaidInput.value = ""
        saleNotes.value = ""
    }

    fun selectCustomer(customer: CustomerEntity?) {
        selectedCustomer.value = customer
        if (customer != null) {
            customCustomerName.value = customer.name
            customCustomerPhone.value = customer.phone
            customCustomerWhatsapp.value = if (customer.whatsapp.isNotBlank()) customer.whatsapp else customer.phone
        }
    }

    val cartTotal: StateFlow<Double> = _cart.map { map ->
        map.values.sumOf { it.lineTotal }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /**
     * Completes and saves a new Sale
     */
    fun completeSale(
        onSuccess: (SaleOrderEntity) -> Unit
    ) {
        val itemsList = _cart.value.values.toList()
        if (itemsList.isEmpty()) {
            viewModelScope.launch { _statusMessage.emit("Cart is empty! Add products first.") }
            return
        }

        val name = if (selectedCustomer.value != null) selectedCustomer.value!!.name else customCustomerName.value.trim()
        val phone = if (selectedCustomer.value != null) selectedCustomer.value!!.phone else customCustomerPhone.value.trim()
        val whatsapp = if (selectedCustomer.value != null) selectedCustomer.value!!.whatsapp else customCustomerWhatsapp.value.trim()

        if (name.isBlank()) {
            viewModelScope.launch { _statusMessage.emit("Please enter or select a customer name.") }
            return
        }

        val subtotal = itemsList.sumOf { it.lineTotal }
        val discount = (discountInput.value.toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
        val finalTotal = (subtotal - discount).coerceAtLeast(0.0)
        val paid = amountPaidInput.value.toDoubleOrNull() ?: finalTotal // default to full payment if not specified

        viewModelScope.launch {
            try {
                val order = repository.createSaleOrder(
                    customerId = selectedCustomer.value?.id ?: 0L,
                    customerName = name,
                    customerPhone = phone,
                    customerWhatsapp = if (whatsapp.isNotBlank()) whatsapp else phone,
                    itemsJson = Formatters.serializeCartItems(itemsList),
                    totalAmount = finalTotal,
                    discountAmount = discount,
                    amountPaid = paid.coerceAtMost(finalTotal),
                    paymentMethod = paymentMethod.value,
                    notes = saleNotes.value.trim()
                )

                clearCart()
                activeReceiptOrder.value = order

                val currentSettings = settings.value

                // Trigger App Notification for New Sale
                NotificationHelper.showSaleCompletedNotification(
                    context = getApplication(),
                    invoiceNumber = order.invoiceNumber,
                    customerName = order.customerName,
                    totalAmountFormatted = Formatters.formatCurrency(order.totalAmount, currentSettings.currencySymbol),
                    amountPaidFormatted = Formatters.formatCurrency(order.amountPaid, currentSettings.currencySymbol),
                    balanceDue = order.balanceDue,
                    currencySymbol = currentSettings.currencySymbol
                )

                // Update Home Screen Widget
                FarmWidgetProvider.updateAllWidgets(getApplication())

                // Automated SMS Alert via SMSOnlineGH on new Transaction / Sale
                if (currentSettings.smsAutoSendOnSale && order.customerPhone.isNotBlank()) {
                    if (currentSettings.smsApiKey.isNotBlank()) {
                        val smsText = Formatters.buildPurchaseSms(order, currentSettings)
                        val smsResult = repository.sendSmsViaSmsOnlineGh(
                            recipientPhone = order.customerPhone,
                            message = smsText
                        )
                        when (smsResult) {
                            is SmsSendResult.Success -> {
                                repository.markSmsSent(order.id)
                                _statusMessage.emit("Sale recorded! Automated SMS receipt sent via SMSOnlineGH to ${order.customerName}.")
                                NotificationHelper.showSmsStatusNotification(
                                    context = getApplication(),
                                    title = "SMS Sent to ${order.customerName}",
                                    message = "Receipt #${order.invoiceNumber} delivered successfully via SMSOnlineGH.",
                                    isSuccess = true
                                )
                            }
                            is SmsSendResult.Failure -> {
                                // Enqueue into WorkManager for automatic network retry
                                repository.enqueueBackgroundSms(
                                    context = getApplication(),
                                    recipientPhone = order.customerPhone,
                                    message = smsText,
                                    orderId = order.id,
                                    customerName = order.customerName,
                                    smsType = "Receipt #${order.invoiceNumber}"
                                )
                                _statusMessage.emit("Sale recorded! SMS receipt queued in background with auto-retry.")
                            }
                        }
                    } else {
                        _statusMessage.emit("Sale #${order.invoiceNumber} recorded! (Set SMSOnlineGH API Key in Settings to enable automated SMS)")
                    }
                } else {
                    _statusMessage.emit("Sale recorded successfully! Invoice #${order.invoiceNumber}")
                }

                onSuccess(order)
            } catch (e: Exception) {
                _statusMessage.emit("Error recording sale: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Record a customer payment against debt
     */
    fun recordPayment(
        customer: CustomerEntity,
        amount: Double,
        method: PaymentMethod,
        notes: String,
        sendSmsAck: Boolean,
        onSuccess: () -> Unit
    ) {
        if (amount <= 0) {
            viewModelScope.launch { _statusMessage.emit("Please enter a valid payment amount.") }
            return
        }

        viewModelScope.launch {
            try {
                val newBal = repository.recordCustomerPayment(
                    customerId = customer.id,
                    amount = amount,
                    paymentMethod = method,
                    notes = notes
                )
                customerForPayment.value = null

                // Refresh active receipt order if it belongs to this customer
                activeReceiptOrder.value?.let { currentOrder ->
                    if (currentOrder.customerId == customer.id) {
                        activeReceiptOrder.value = repository.getOrderById(currentOrder.id)
                    }
                }

                val currentSettings = settings.value

                // Trigger App Notification for Payment
                NotificationHelper.showPaymentReceivedNotification(
                    context = getApplication(),
                    customerName = customer.name,
                    amountPaidFormatted = Formatters.formatCurrency(amount, currentSettings.currencySymbol),
                    remainingBalance = newBal,
                    currencySymbol = currentSettings.currencySymbol
                )

                // Update Home Screen Widget
                FarmWidgetProvider.updateAllWidgets(getApplication())

                onSuccess()

                // Automated SMS Alert via SMSOnlineGH on Payment
                if (sendSmsAck && customer.phone.isNotBlank()) {
                    val smsText = Formatters.buildPaymentReceivedSms(
                        customerName = customer.name,
                        amountReceived = amount,
                        remainingBalance = newBal,
                        settings = currentSettings
                    )

                    if (currentSettings.smsAutoSendOnPayment && currentSettings.smsApiKey.isNotBlank()) {
                        val smsResult = repository.sendSmsViaSmsOnlineGh(
                            recipientPhone = customer.phone,
                            message = smsText
                        )
                        when (smsResult) {
                            is SmsSendResult.Success -> {
                                _statusMessage.emit("Payment of ${Formatters.formatCurrency(amount, currentSettings.currencySymbol)} recorded & SMS alert sent via SMSOnlineGH!")
                            }
                            is SmsSendResult.Failure -> {
                                val isNetwork = smsResult.errorMessage.contains("Network", ignoreCase = true) ||
                                        smsResult.errorMessage.contains("Connection", ignoreCase = true) ||
                                        smsResult.errorMessage.contains("timeout", ignoreCase = true)
                                if (isNetwork) {
                                    repository.enqueueBackgroundSms(
                                        context = getApplication(),
                                        recipientPhone = customer.phone,
                                        message = smsText,
                                        orderId = 0L,
                                        customerName = customer.name,
                                        smsType = "Payment Receipt"
                                    )
                                    _statusMessage.emit("Payment recorded! SMS queued in background with auto-retry once connected.")
                                } else {
                                    _statusMessage.emit("Payment recorded, but automated SMS failed: ${smsResult.errorMessage}")
                                    prepareSmsPreview(
                                        name = customer.name,
                                        phone = customer.phone,
                                        message = smsText,
                                        type = "PAYMENT_ACK",
                                        customerId = customer.id
                                    )
                                }
                            }
                        }
                    } else {
                        _statusMessage.emit("Payment of ${Formatters.formatCurrency(amount, currentSettings.currencySymbol)} recorded!")
                        prepareSmsPreview(
                            name = customer.name,
                            phone = customer.phone,
                            message = smsText,
                            type = "PAYMENT_ACK",
                            customerId = customer.id
                        )
                    }
                } else {
                    _statusMessage.emit("Payment of ${Formatters.formatCurrency(amount, currentSettings.currencySymbol)} recorded!")
                }
            } catch (e: Exception) {
                _statusMessage.emit("Error recording payment: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Record payment directly against a specific sales order
     */
    fun recordOrderPayment(
        order: SaleOrderEntity,
        amount: Double,
        method: PaymentMethod,
        notes: String,
        sendSmsAck: Boolean,
        onSuccess: () -> Unit
    ) {
        if (amount <= 0) {
            viewModelScope.launch { _statusMessage.emit("Please enter a valid payment amount.") }
            return
        }

        viewModelScope.launch {
            try {
                val remainingBal = repository.recordOrderPayment(
                    orderId = order.id,
                    amount = amount,
                    paymentMethod = method,
                    notes = notes
                )
                
                val currentSettings = settings.value

                // Trigger App Notification for Payment
                NotificationHelper.showPaymentReceivedNotification(
                    context = getApplication(),
                    customerName = order.customerName,
                    amountPaidFormatted = Formatters.formatCurrency(amount, currentSettings.currencySymbol),
                    remainingBalance = remainingBal,
                    currencySymbol = currentSettings.currencySymbol
                )

                // Update Home Screen Widget
                FarmWidgetProvider.updateAllWidgets(getApplication())

                // Refresh active receipt order
                activeReceiptOrder.value = repository.getOrderById(order.id)
                onSuccess()

                // Automated SMS Alert via SMSOnlineGH on Order Payment
                if (sendSmsAck && order.customerPhone.isNotBlank()) {
                    val customer = repository.getCustomerById(order.customerId)
                    val remainingTotalBal = customer?.currentBalance ?: remainingBal
                    val smsText = Formatters.buildPaymentReceivedSms(
                        customerName = order.customerName,
                        amountReceived = amount,
                        remainingBalance = remainingTotalBal,
                        settings = currentSettings
                    )

                    if (currentSettings.smsAutoSendOnPayment && currentSettings.smsApiKey.isNotBlank()) {
                        val smsResult = repository.sendSmsViaSmsOnlineGh(
                            recipientPhone = order.customerPhone,
                            message = smsText
                        )
                        when (smsResult) {
                            is SmsSendResult.Success -> {
                                repository.markSmsSent(order.id)
                                _statusMessage.emit("Payment recorded for #${order.invoiceNumber} & SMS alert sent via SMSOnlineGH!")
                            }
                            is SmsSendResult.Failure -> {
                                _statusMessage.emit("Payment recorded, but SMS failed: ${smsResult.errorMessage}")
                                prepareSmsPreview(
                                    name = order.customerName,
                                    phone = order.customerPhone,
                                    message = smsText,
                                    type = "PAYMENT_ACK",
                                    orderId = order.id,
                                    customerId = order.customerId
                                )
                            }
                        }
                    } else {
                        _statusMessage.emit("Payment of ${Formatters.formatCurrency(amount, currentSettings.currencySymbol)} recorded for Inv #${order.invoiceNumber}!")
                        prepareSmsPreview(
                            name = order.customerName,
                            phone = order.customerPhone,
                            message = smsText,
                            type = "PAYMENT_ACK",
                            orderId = order.id,
                            customerId = order.customerId
                        )
                    }
                } else {
                    _statusMessage.emit("Payment of ${Formatters.formatCurrency(amount, currentSettings.currencySymbol)} recorded for Inv #${order.invoiceNumber}!")
                }
            } catch (e: Exception) {
                _statusMessage.emit("Error recording payment: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Send a test SMS to verify SMSOnlineGH credentials and integration
     */
    fun testSmsConnection(testPhone: String, onComplete: (SmsSendResult) -> Unit) {
        _isSendingSms.value = true
        viewModelScope.launch {
            val currentSettings = settings.value
            val testMessage = "Test alert from ${currentSettings.businessName} via SMSOnlineGH. Automated SMS integration is active and working properly!"
            val result = repository.sendSmsViaSmsOnlineGh(
                recipientPhone = testPhone,
                message = testMessage
            )
            _isSendingSms.value = false
            when (result) {
                is SmsSendResult.Success -> {
                    _statusMessage.emit("Test SMS delivered successfully via SMSOnlineGH!")
                }
                is SmsSendResult.Failure -> {
                    _statusMessage.emit("SMS Test Error: ${result.errorMessage}")
                }
            }
            onComplete(result)
        }
    }

    /**
     * Check SMS account units/balance on SMSOnlineGH
     */
    fun checkSmsBalance(customApiKey: String? = null, onComplete: (SmsBalanceResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.checkSmsBalance(customApiKey)
            onComplete(result)
        }
    }

    /**
     * Prepares SMS preview dialog
     */
    fun prepareSmsPreview(
        name: String,
        phone: String,
        message: String,
        type: String,
        orderId: Long? = null,
        customerId: Long? = null
    ) {
        smsPreview.value = SmsPreviewState(
            recipientName = name,
            recipientPhone = phone,
            messageText = message,
            messageType = type,
            relatedOrderId = orderId,
            relatedCustomerId = customerId
        )
    }

    /**
     * Dispatch SMS using SMSOnlineGH API
     */
    val activeSmsQueueCount: Flow<Int> = com.example.util.work.SmsWorkScheduler.observeActiveSmsCount(getApplication())

    /**
     * Dispatch SMS using SMSOnlineGH API with automatic background WorkManager fallback
     */
    fun sendSmsOnlineGh(preview: SmsPreviewState, onComplete: () -> Unit) {
        _isSendingSms.value = true
        viewModelScope.launch {
            val result = repository.sendSmsViaSmsOnlineGh(
                recipientPhone = preview.recipientPhone,
                message = preview.messageText
            )
            _isSendingSms.value = false

            when (result) {
                is SmsSendResult.Success -> {
                    _statusMessage.emit("SMS delivered successfully via SMSOnlineGH!")
                    preview.relatedOrderId?.let { repository.markSmsSent(it) }
                    NotificationHelper.showSmsStatusNotification(
                        context = getApplication(),
                        title = "SMS Sent to ${preview.recipientName}",
                        message = "Message sent successfully via SMSOnlineGH.",
                        isSuccess = true
                    )
                    smsPreview.value = null
                    onComplete()
                }
                is SmsSendResult.Failure -> {
                    // If network error, enqueue for background retry
                    val isNetwork = result.errorMessage.contains("Network", ignoreCase = true) ||
                            result.errorMessage.contains("Connection", ignoreCase = true) ||
                            result.errorMessage.contains("timeout", ignoreCase = true)
                    if (isNetwork) {
                        repository.enqueueBackgroundSms(
                            context = getApplication(),
                            recipientPhone = preview.recipientPhone,
                            message = preview.messageText,
                            orderId = preview.relatedOrderId ?: 0L,
                            customerName = preview.recipientName,
                            smsType = preview.messageType
                        )
                        _statusMessage.emit("Network unavailable. SMS queued in background with auto-retry once connected!")
                        smsPreview.value = null
                        onComplete()
                    } else {
                        _statusMessage.emit(result.errorMessage)
                        NotificationHelper.showSmsStatusNotification(
                            context = getApplication(),
                            title = "SMS Dispatch Failed",
                            message = "Failed sending SMS to ${preview.recipientName}: ${result.errorMessage}",
                            isSuccess = false
                        )
                    }
                }
            }
        }
    }

    /**
     * Explicitly queue SMS in background with WorkManager
     */
    fun enqueueBackgroundSms(
        recipientPhone: String,
        message: String,
        orderId: Long = 0L,
        customerName: String = "",
        smsType: String = "Receipt"
    ) {
        repository.enqueueBackgroundSms(
            context = getApplication(),
            recipientPhone = recipientPhone,
            message = message,
            orderId = orderId,
            customerName = customerName,
            smsType = smsType
        )
    }

    // Product CRUD
    fun saveProduct(product: ProductEntity) {
        viewModelScope.launch {
            if (product.id == 0L) {
                repository.insertProduct(product)
                _statusMessage.emit("Product added successfully!")
            } else {
                repository.updateProduct(product)
                _statusMessage.emit("Product updated successfully!")
            }
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    fun deleteProduct(product: ProductEntity) {
        viewModelScope.launch {
            repository.deleteProduct(product)
            _statusMessage.emit("Product removed.")
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    fun adjustProductStock(productId: Long, quantityChange: Double, logType: String = "ADJUSTMENT", notes: String = "") {
        viewModelScope.launch {
            repository.adjustProductStock(productId, quantityChange, logType, notes)
            _statusMessage.emit("Inventory stock updated.")
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    // Customer CRUD
    fun saveCustomer(customer: CustomerEntity) {
        viewModelScope.launch {
            if (customer.id == 0L) {
                repository.insertCustomer(customer)
                _statusMessage.emit("Customer added successfully!")
            } else {
                repository.updateCustomer(customer)
                _statusMessage.emit("Customer details updated!")
            }
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    fun deleteCustomer(customer: CustomerEntity) {
        viewModelScope.launch {
            repository.deleteCustomer(customer)
            _statusMessage.emit("Customer removed.")
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    // Sale Order CRUD
    fun updateSaleOrder(updatedOrder: SaleOrderEntity, originalOrder: SaleOrderEntity, onSuccess: ((SaleOrderEntity) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val saved = repository.updateSaleOrder(updatedOrder, originalOrder)
                _statusMessage.emit("Invoice #${saved.invoiceNumber} updated successfully!")
                
                // If this order is currently shown in active receipt, update it
                if (activeReceiptOrder.value?.id == saved.id) {
                    activeReceiptOrder.value = saved
                }
                orderToEdit.value = null
                FarmWidgetProvider.updateAllWidgets(getApplication())
                onSuccess?.invoke(saved)
            } catch (e: Exception) {
                _statusMessage.emit("Error updating invoice: ${e.localizedMessage}")
            }
        }
    }

    fun voidSaleOrder(order: SaleOrderEntity) {
        viewModelScope.launch {
            repository.voidSaleOrder(order)
            _statusMessage.emit("Invoice #${order.invoiceNumber} voided. Stock and customer balances restored.")
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    // Settings
    fun saveSettings(newSettings: AppSettingsEntity) {
        viewModelScope.launch {
            repository.saveSettings(newSettings)
            _statusMessage.emit("Settings saved successfully!")
            FarmWidgetProvider.updateAllWidgets(getApplication())
        }
    }

    // ==========================================
    // Database Backup & Restore Operations
    // ==========================================

    fun loadBackupInfo() {
        val (time, status, count) = DatabaseBackupHelper.getLastBackupInfo(getApplication())
        _lastBackupTime.value = time
        _lastBackupStatus.value = status
        _lastBackupCount.value = count
        _savedBackupFiles.value = DatabaseBackupHelper.getSavedBackupFiles(getApplication())
    }

    fun performManualBackup(
        onSuccess: ((File, BackupData) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val (file, backupData) = DatabaseBackupHelper.exportDatabaseToJson(
                    context = getApplication(),
                    database = db,
                    isAutoBackup = false
                )
                loadBackupInfo()
                _isBackingUp.value = false
                _statusMessage.emit("Backup saved: ${file.name} (${backupData.summary.totalProducts} products, ${backupData.summary.totalCustomers} customers, ${backupData.summary.totalSalesOrders} sales)")
                onSuccess?.invoke(file, backupData)
            } catch (e: Exception) {
                _isBackingUp.value = false
                val err = e.localizedMessage ?: "Failed to export backup"
                _statusMessage.emit("Backup failed: $err")
                onError?.invoke(err)
            }
        }
    }

    fun restoreFromUri(
        uri: Uri,
        onResult: (RestoreResult) -> Unit
    ) {
        viewModelScope.launch {
            _isRestoring.value = true
            try {
                val result = DatabaseBackupHelper.restoreFromUri(
                    context = getApplication(),
                    uri = uri,
                    database = db
                )
                _isRestoring.value = false
                if (result.success) {
                    _statusMessage.emit(result.message)
                    loadBackupInfo()
                    FarmWidgetProvider.updateAllWidgets(getApplication())
                } else {
                    _statusMessage.emit(result.message)
                }
                onResult(result)
            } catch (e: Exception) {
                _isRestoring.value = false
                val res = RestoreResult(
                    success = false,
                    message = "Restore failed: ${e.localizedMessage ?: "Unknown error"}",
                    error = e.localizedMessage
                )
                _statusMessage.emit(res.message)
                onResult(res)
            }
        }
    }

    fun restoreFromFile(
        file: File,
        onResult: (RestoreResult) -> Unit
    ) {
        viewModelScope.launch {
            _isRestoring.value = true
            try {
                val jsonString = file.readText()
                val backupData = DatabaseBackupHelper.parseBackupJson(jsonString)
                if (backupData == null) {
                    val failRes = RestoreResult(
                        success = false,
                        message = "Invalid backup file contents."
                    )
                    _isRestoring.value = false
                    _statusMessage.emit(failRes.message)
                    onResult(failRes)
                    return@launch
                }

                val result = DatabaseBackupHelper.restoreDatabase(db, backupData)
                _isRestoring.value = false
                if (result.success) {
                    _statusMessage.emit(result.message)
                    loadBackupInfo()
                    FarmWidgetProvider.updateAllWidgets(getApplication())
                } else {
                    _statusMessage.emit(result.message)
                }
                onResult(result)
            } catch (e: Exception) {
                _isRestoring.value = false
                val res = RestoreResult(
                    success = false,
                    message = "Restore failed: ${e.localizedMessage ?: "Unknown error"}",
                    error = e.localizedMessage
                )
                _statusMessage.emit(res.message)
                onResult(res)
            }
        }
    }

    fun writeBackupToUri(
        uri: Uri,
        backupData: BackupData,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val success = DatabaseBackupHelper.writeBackupToUri(getApplication(), uri, backupData)
            if (success) {
                _statusMessage.emit("Backup exported successfully to selected folder!")
            } else {
                _statusMessage.emit("Failed to save backup to chosen location.")
            }
            onResult(success)
        }
    }

    /**
     * Push all local Room data to Namecheap MySQL database.
     */
    fun syncToCloud(
        customUrl: String? = null,
        customKey: String? = null,
        onResult: (CloudSyncResult) -> Unit
    ) {
        viewModelScope.launch {
            _isCloudSyncing.value = true
            _cloudSyncStatusMessage.value = "Preparing cloud sync payload..."
            try {
                val currentSettings = settings.value
                val syncUrl = (customUrl ?: currentSettings.cloudSyncUrl).trim()
                val secretKey = (customKey ?: currentSettings.cloudSyncSecretKey).trim()

                if (syncUrl.isBlank()) {
                    val failRes = CloudSyncResult(
                        success = false,
                        message = "Please configure your Namecheap Sync URL in Settings first."
                    )
                    _isCloudSyncing.value = false
                    _cloudSyncStatusMessage.value = failRes.message
                    _statusMessage.emit(failRes.message)
                    onResult(failRes)
                    return@launch
                }

                // Auto-persist active cloud credentials if they changed
                if (syncUrl != currentSettings.cloudSyncUrl || secretKey != currentSettings.cloudSyncSecretKey) {
                    repository.saveSettings(
                        currentSettings.copy(
                            cloudSyncUrl = syncUrl,
                            cloudSyncSecretKey = secretKey
                        )
                    )
                }

                // 1. Gather all current Room records
                val backupData = DatabaseBackupHelper.createBackupData(db)

                _cloudSyncStatusMessage.value = "Sending data to Namecheap MySQL server..."
                val syncResult = CloudSyncService.pushToCloud(
                    syncUrl = syncUrl,
                    secretKey = secretKey,
                    backupData = backupData
                )

                _isCloudSyncing.value = false
                _cloudSyncStatusMessage.value = syncResult.message

                if (syncResult.success) {
                    val now = System.currentTimeMillis()
                    repository.saveSettings(
                        currentSettings.copy(
                            cloudSyncUrl = syncUrl,
                            cloudSyncSecretKey = secretKey,
                            lastCloudSyncTime = now,
                            lastCloudSyncStatus = "Synced ${syncResult.recordsCount} records"
                        )
                    )
                    _statusMessage.emit("Cloud Sync complete: ${syncResult.message}")
                } else {
                    _statusMessage.emit(syncResult.message)
                }
                onResult(syncResult)
            } catch (e: Exception) {
                _isCloudSyncing.value = false
                val fail = CloudSyncResult(
                    success = false,
                    message = "Cloud sync failed: ${e.localizedMessage ?: "Unknown error"}"
                )
                _cloudSyncStatusMessage.value = fail.message
                _statusMessage.emit(fail.message)
                onResult(fail)
            }
        }
    }

    /**
     * Pull all records from Namecheap MySQL database and restore to local Room.
     */
    fun restoreFromCloud(
        customUrl: String? = null,
        customKey: String? = null,
        onResult: (RestoreResult) -> Unit
    ) {
        viewModelScope.launch {
            _isCloudSyncing.value = true
            _cloudSyncStatusMessage.value = "Connecting to Namecheap MySQL server..."
            try {
                val currentSettings = settings.value
                val syncUrl = (customUrl ?: currentSettings.cloudSyncUrl).trim()
                val secretKey = (customKey ?: currentSettings.cloudSyncSecretKey).trim()

                if (syncUrl.isBlank()) {
                    val failRes = RestoreResult(
                        success = false,
                        message = "Please configure your Namecheap Sync URL in Settings first."
                    )
                    _isCloudSyncing.value = false
                    _cloudSyncStatusMessage.value = failRes.message
                    _statusMessage.emit(failRes.message)
                    onResult(failRes)
                    return@launch
                }

                // Auto-persist active cloud credentials if they changed
                if (syncUrl != currentSettings.cloudSyncUrl || secretKey != currentSettings.cloudSyncSecretKey) {
                    repository.saveSettings(
                        currentSettings.copy(
                            cloudSyncUrl = syncUrl,
                            cloudSyncSecretKey = secretKey
                        )
                    )
                }

                val pullResult = CloudSyncService.pullFromCloud(
                    syncUrl = syncUrl,
                    secretKey = secretKey
                )

                if (!pullResult.success || pullResult.backupData == null) {
                    _isCloudSyncing.value = false
                    val res = RestoreResult(
                        success = false,
                        message = pullResult.message
                    )
                    _cloudSyncStatusMessage.value = pullResult.message
                    _statusMessage.emit(pullResult.message)
                    onResult(res)
                    return@launch
                }

                _cloudSyncStatusMessage.value = "Restoring cloud records into phone database..."
                val restoreResult = DatabaseBackupHelper.restoreDatabase(db, pullResult.backupData)
                _isCloudSyncing.value = false

                if (restoreResult.success) {
                    val now = System.currentTimeMillis()
                    repository.saveSettings(
                        currentSettings.copy(
                            cloudSyncUrl = syncUrl,
                            cloudSyncSecretKey = secretKey,
                            lastCloudSyncTime = now,
                            lastCloudSyncStatus = "Restored from cloud (${pullResult.recordsCount} records)"
                        )
                    )
                    loadBackupInfo()
                    FarmWidgetProvider.updateAllWidgets(getApplication())
                    _cloudSyncStatusMessage.value = "Successfully restored ${pullResult.recordsCount} items from cloud!"
                    _statusMessage.emit("Cloud restore successful!")
                } else {
                    _cloudSyncStatusMessage.value = restoreResult.message
                    _statusMessage.emit(restoreResult.message)
                }
                onResult(restoreResult)
            } catch (e: Exception) {
                _isCloudSyncing.value = false
                val fail = RestoreResult(
                    success = false,
                    message = "Cloud restore error: ${e.localizedMessage ?: "Unknown error"}",
                    error = e.localizedMessage
                )
                _cloudSyncStatusMessage.value = fail.message
                _statusMessage.emit(fail.message)
                onResult(fail)
            }
        }
    }

    /**
     * Test connection to Namecheap MySQL server endpoint.
     */
    fun testCloudConnection(
        url: String,
        key: String,
        onResult: (CloudSyncResult) -> Unit
    ) {
        viewModelScope.launch {
            _isCloudSyncing.value = true
            val res = CloudSyncService.testConnection(url, key)
            _isCloudSyncing.value = false
            onResult(res)
        }
    }
}
