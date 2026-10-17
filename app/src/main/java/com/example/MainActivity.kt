package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CustomerEntity
import com.example.data.model.SaleOrderEntity
import com.example.ui.components.AddCustomerDialog
import com.example.ui.components.EditInvoiceDialog
import com.example.ui.components.ReceiptDialog
import com.example.ui.components.RecordPaymentDialog
import com.example.ui.components.SmsPreviewDialog
import com.example.ui.screens.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.FarmViewModel
import com.example.util.Formatters
import com.example.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Declaration order is the tab bar order; NEW_SALE sits in the middle as the raised action.
// Enum names double as deep-link targets from notifications and the widget, so keep them stable.
enum class Screen(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("Home", Icons.Filled.Home, Icons.Outlined.Home),
    DEBTORS("Customers", Icons.Filled.People, Icons.Outlined.People),
    NEW_SALE("Sell", Icons.Filled.Add, Icons.Filled.Add),
    PRODUCTS("Products", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
private fun AppTabBar(
    currentScreen: Screen,
    onSelect: (Screen) -> Unit,
    debtorCount: Int,
    cartCount: Int
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.background(colors.surface)) {
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(64.dp)
                .testTag("main_navigation_bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Screen.entries.forEach { screen ->
                val isSelected = currentScreen == screen
                val tint = if (isSelected) colors.primary else colors.onSurfaceVariant
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(screen) }
                        )
                        .testTag("nav_item_${screen.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (screen == Screen.NEW_SALE) {
                        BadgedBox(badge = {
                            if (cartCount > 0) Badge(containerColor = AppTheme.status.danger, contentColor = AppTheme.status.onDanger) { Text(cartCount.toString()) }
                        }) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .shadow(8.dp, CircleShape, spotColor = colors.primary.copy(alpha = 0.5f))
                                    .clip(CircleShape)
                                    .background(colors.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = screen.title, tint = colors.onPrimary, modifier = Modifier.size(28.dp))
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BadgedBox(badge = {
                                if (screen == Screen.DEBTORS && debtorCount > 0) {
                                    Badge(containerColor = AppTheme.status.danger, contentColor = AppTheme.status.onDanger) { Text(debtorCount.toString()) }
                                }
                            }) {
                                Icon(
                                    imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = screen.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: FarmViewModel by viewModels()
    private val requestedScreenTarget = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Create Android 8+ Notification Channels
        NotificationHelper.createNotificationChannels(this)

        // Check if opened from Widget or Notification
        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                MainAppContent(
                    viewModel = viewModel,
                    requestedScreenTarget = requestedScreenTarget
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val targetScreen = intent?.getStringExtra(NotificationHelper.EXTRA_NAVIGATE_SCREEN)
        if (!targetScreen.isNullOrBlank()) {
            requestedScreenTarget.value = targetScreen
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    viewModel: FarmViewModel,
    requestedScreenTarget: MutableStateFlow<String?> = remember { MutableStateFlow(null) }
) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Android 13+ Notification Permission Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (!isGranted) {
                // User declined notification permission
            }
        }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Handle incoming screen navigation from Notification or Widget
    val navTarget by requestedScreenTarget.collectAsStateWithLifecycle()
    LaunchedEffect(navTarget) {
        navTarget?.let { target ->
            when (target) {
                "NEW_SALE" -> currentScreen = Screen.NEW_SALE
                "DEBTORS" -> currentScreen = Screen.DEBTORS
                "PRODUCTS" -> currentScreen = Screen.PRODUCTS
                "SETTINGS" -> currentScreen = Screen.SETTINGS
                "DASHBOARD" -> currentScreen = Screen.DASHBOARD
            }
            requestedScreenTarget.value = null
        }
    }

    // Observe ViewModel states
    val products by viewModel.products.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val debtors by viewModel.debtors.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val totalDebt by viewModel.totalOutstandingDebt.collectAsStateWithLifecycle()
    val totalRevenue by viewModel.totalRevenue.collectAsStateWithLifecycle()
    val totalCollected by viewModel.totalCollected.collectAsStateWithLifecycle()
    val cart by viewModel.cart.collectAsStateWithLifecycle()
    val cartTotal by viewModel.cartTotal.collectAsStateWithLifecycle()
    val allPayments by viewModel.allPayments.collectAsStateWithLifecycle()
    val orderForPayment by viewModel.orderForPayment.collectAsStateWithLifecycle()

    val activeReceiptOrder by viewModel.activeReceiptOrder.collectAsStateWithLifecycle()
    val orderToEdit by viewModel.orderToEdit.collectAsStateWithLifecycle()
    val smsPreview by viewModel.smsPreview.collectAsStateWithLifecycle()
    val customerForPayment by viewModel.customerForPayment.collectAsStateWithLifecycle()
    val isSendingSms by viewModel.isSendingSms.collectAsStateWithLifecycle()

    val lastBackupTime by viewModel.lastBackupTime.collectAsStateWithLifecycle()
    val lastBackupStatus by viewModel.lastBackupStatus.collectAsStateWithLifecycle()
    val lastBackupCount by viewModel.lastBackupCount.collectAsStateWithLifecycle()
    val savedBackupFiles by viewModel.savedBackupFiles.collectAsStateWithLifecycle()
    val isBackingUp by viewModel.isBackingUp.collectAsStateWithLifecycle()
    val isRestoring by viewModel.isRestoring.collectAsStateWithLifecycle()
    val isCloudSyncing by viewModel.isCloudSyncing.collectAsStateWithLifecycle()
    val cloudSyncStatusMsg by viewModel.cloudSyncStatusMessage.collectAsStateWithLifecycle()

    var showAddCustomerDialog by remember { mutableStateOf(false) }

    // Listen to status messages / snackbar
    LaunchedEffect(Unit) {
        viewModel.statusMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AppTabBar(
                currentScreen = currentScreen,
                onSelect = { currentScreen = it },
                debtorCount = debtors.size,
                cartCount = cart.values.sumOf { it.quantity }.toInt()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "ScreenTransition"
            ) { targetScreen ->
                when (targetScreen) {
                    Screen.DASHBOARD -> {
                        SalesDashboardScreen(
                            orders = orders,
                            debtors = debtors,
                            totalDebt = totalDebt,
                            totalRevenue = totalRevenue,
                            totalCollected = totalCollected,
                            settings = settings,
                            onNavigateToNewSale = { currentScreen = Screen.NEW_SALE },
                            onNavigateToDebtors = { currentScreen = Screen.DEBTORS },
                            onNavigateToProducts = { currentScreen = Screen.PRODUCTS },
                            onSelectOrderReceipt = { order -> viewModel.activeReceiptOrder.value = order },
                            onSendSms = { order ->
                                val msg = Formatters.buildPurchaseSms(order, settings)
                                viewModel.prepareSmsPreview(
                                    name = order.customerName,
                                    phone = order.customerPhone,
                                    message = msg,
                                    type = "PURCHASE",
                                    orderId = order.id,
                                    customerId = order.customerId
                                )
                            },
                            onEditOrder = { order -> viewModel.orderToEdit.value = order },
                            onVoidOrder = { order -> viewModel.voidSaleOrder(order) },
                            products = products
                        )
                    }
                    Screen.NEW_SALE -> {
                        NewSaleScreen(
                            viewModel = viewModel,
                            products = products,
                            customers = customers,
                            settings = settings,
                            cart = cart,
                            cartTotal = cartTotal,
                            onSaleCompleted = { order ->
                                // Trigger dialog
                                viewModel.activeReceiptOrder.value = order
                            }
                        )
                    }
                    Screen.DEBTORS -> {
                        CustomersDebtScreen(
                            customers = customers,
                            debtors = debtors,
                            totalDebt = totalDebt,
                            settings = settings,
                            orders = orders,
                            payments = allPayments,
                            onRecordPayment = { customer ->
                                viewModel.orderForPayment.value = null
                                viewModel.customerForPayment.value = customer
                            },
                            onSendReminderSms = { customer ->
                                val customerUnpaidOrders = orders.filter { it.customerId == customer.id && it.balanceDue > 0 }.sortedBy { it.timestamp }
                                val msg = Formatters.buildDebtReminderSms(
                                    customerName = customer.name,
                                    balance = customer.currentBalance,
                                    settings = settings,
                                    unpaidOrders = customerUnpaidOrders
                                )
                                viewModel.prepareSmsPreview(
                                    name = customer.name,
                                    phone = customer.phone,
                                    message = msg,
                                    type = "REMINDER",
                                    customerId = customer.id
                                )
                            },
                            onSaveCustomer = { customer -> viewModel.saveCustomer(customer) },
                            onDeleteCustomer = { customer -> viewModel.deleteCustomer(customer) },
                            onAddCustomer = { showAddCustomerDialog = true },
                            onRecordOrderPayment = { order ->
                                val cust = customers.find { it.id == order.customerId }
                                if (cust != null) {
                                    viewModel.orderForPayment.value = order
                                    viewModel.customerForPayment.value = cust
                                }
                            },
                            onViewReceipt = { order ->
                                viewModel.activeReceiptOrder.value = order
                            }
                        )
                    }
                    Screen.PRODUCTS -> {
                        ProductsCatalogScreen(
                            products = products,
                            settings = settings,
                            onSaveProduct = { product -> viewModel.saveProduct(product) },
                            onDeleteProduct = { product -> viewModel.deleteProduct(product) },
                            onAdjustStock = { id, change, logType, notes -> viewModel.adjustProductStock(id, change, logType, notes) }
                        )
                    }
                    Screen.SETTINGS -> {
                        SettingsScreen(
                            settings = settings,
                            onSaveSettings = { updated -> viewModel.saveSettings(updated) },
                            onTestSms = { phone, callback ->
                                viewModel.testSmsConnection(phone, callback)
                            },
                            onCheckBalance = { key, callback ->
                                viewModel.checkSmsBalance(key, callback)
                            },
                            isTestingSms = isSendingSms,
                            lastBackupTime = lastBackupTime,
                            lastBackupStatus = lastBackupStatus,
                            lastBackupCount = lastBackupCount,
                            savedBackupFiles = savedBackupFiles,
                            isBackingUp = isBackingUp,
                            isRestoring = isRestoring,
                            onManualBackup = { onSuccess, onError ->
                                viewModel.performManualBackup(onSuccess, onError)
                            },
                            onRestoreUri = { uri, onResult ->
                                viewModel.restoreFromUri(uri, onResult)
                            },
                            onRestoreFile = { file, onResult ->
                                viewModel.restoreFromFile(file, onResult)
                            },
                            onSaveBackupToUri = { uri, backupData, onResult ->
                                viewModel.writeBackupToUri(uri, backupData, onResult)
                            },
                            onRefreshBackups = {
                                viewModel.loadBackupInfo()
                            },
                            isCloudSyncing = isCloudSyncing,
                            cloudSyncStatusMsg = cloudSyncStatusMsg,
                            onSyncToCloud = { url, key, callback ->
                                viewModel.syncToCloud(url, key, callback)
                            },
                            onRestoreFromCloud = { url, key, callback ->
                                viewModel.restoreFromCloud(url, key, callback)
                            },
                            onTestCloudConnection = { url, key, callback ->
                                viewModel.testCloudConnection(url, key, callback)
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Dialogs
    // 1. Receipt Modal
    activeReceiptOrder?.let { order ->
        ReceiptDialog(
            order = order,
            settings = settings,
            onDismiss = { viewModel.activeReceiptOrder.value = null },
            onSendSms = { targetOrder ->
                val msg = Formatters.buildPurchaseSms(targetOrder, settings)
                viewModel.prepareSmsPreview(
                    name = targetOrder.customerName,
                    phone = targetOrder.customerPhone,
                    message = msg,
                    type = "PURCHASE",
                    orderId = targetOrder.id,
                    customerId = targetOrder.customerId
                )
            },
            onEditOrder = { targetOrder ->
                viewModel.orderToEdit.value = targetOrder
            },
            onRecordPayment = { targetOrder ->
                val cust = customers.find { it.id == targetOrder.customerId }
                if (cust != null) {
                    viewModel.orderForPayment.value = targetOrder
                    viewModel.customerForPayment.value = cust
                }
            }
        )
    }

    // 1b. Edit Invoice Modal
    orderToEdit?.let { order ->
        EditInvoiceDialog(
            order = order,
            products = products,
            customers = customers,
            settings = settings,
            onDismiss = { viewModel.orderToEdit.value = null },
            onSaveOrder = { updatedOrder, originalOrder ->
                viewModel.updateSaleOrder(updatedOrder, originalOrder)
            }
        )
    }

    // 2. SMS Preview Modal
    smsPreview?.let { preview ->
        SmsPreviewDialog(
            preview = preview,
            settings = settings,
            isSending = isSendingSms,
            onDismiss = { viewModel.smsPreview.value = null },
            onSendSmsOnlineGh = { updatedPreview ->
                viewModel.sendSmsOnlineGh(updatedPreview) {
                    // Completed
                }
            }
        )
    }

    // 3. Record Payment Modal
    customerForPayment?.let { customer ->
        RecordPaymentDialog(
            customer = customer,
            settings = settings,
            order = orderForPayment,
            onDismiss = {
                viewModel.customerForPayment.value = null
                viewModel.orderForPayment.value = null
            },
            onConfirm = { amount, method, notes, sendSms ->
                val currentOrder = orderForPayment
                if (currentOrder != null) {
                    viewModel.recordOrderPayment(
                        order = currentOrder,
                        amount = amount,
                        method = method,
                        notes = notes,
                        sendSmsAck = sendSms,
                        onSuccess = {
                            viewModel.customerForPayment.value = null
                            viewModel.orderForPayment.value = null
                        }
                    )
                } else {
                    viewModel.recordPayment(
                        customer = customer,
                        amount = amount,
                        method = method,
                        notes = notes,
                        sendSmsAck = sendSms,
                        onSuccess = {
                            viewModel.customerForPayment.value = null
                            viewModel.orderForPayment.value = null
                        }
                    )
                }
            }
        )
    }

    // 4. Add Customer Modal
    if (showAddCustomerDialog) {
        AddCustomerDialog(
            onDismiss = { showAddCustomerDialog = false },
            onSaveCustomer = { newCust ->
                viewModel.saveCustomer(newCust)
                showAddCustomerDialog = false
            }
        )
    }
}
