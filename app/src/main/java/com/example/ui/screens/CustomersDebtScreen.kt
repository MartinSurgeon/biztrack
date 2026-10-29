package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AppSettingsEntity
import com.example.data.model.CustomerEntity
import com.example.data.model.PaymentEntity
import com.example.data.model.SaleOrderEntity
import com.example.data.remote.SmsService
import com.example.ui.components.*
import com.example.ui.theme.AppTheme
import com.example.util.DigitalReceiptGenerator
import com.example.util.Formatters

private enum class CustomerTab(val label: String) { DEBTORS("Owing"), ALL("All") }

@Composable
fun CustomersDebtScreen(
    customers: List<CustomerEntity>,
    debtors: List<CustomerEntity>,
    totalDebt: Double,
    settings: AppSettingsEntity,
    orders: List<SaleOrderEntity> = emptyList(),
    payments: List<PaymentEntity> = emptyList(),
    onRecordPayment: (CustomerEntity) -> Unit,
    onSendReminderSms: (CustomerEntity) -> Unit,
    onSaveCustomer: (CustomerEntity) -> Unit,
    onDeleteCustomer: (CustomerEntity) -> Unit,
    onAddCustomer: () -> Unit,
    onRecordOrderPayment: ((SaleOrderEntity) -> Unit)? = null,
    onViewReceipt: ((SaleOrderEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    // Open on "All" when nobody owes, so the default list is never empty for no reason.
    var selectedTab by remember { mutableStateOf(if (debtors.isNotEmpty()) CustomerTab.DEBTORS else CustomerTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var customerToEdit by remember { mutableStateOf<CustomerEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var customerForHistoryDialog by remember { mutableStateOf<CustomerEntity?>(null) }

    val listToDisplay = if (selectedTab == CustomerTab.DEBTORS) debtors.sortedByDescending { it.currentBalance } else customers
    val filteredList = if (searchQuery.isBlank()) listToDisplay else listToDisplay.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenHeader(title = "Customers", subtitle = "${customers.size} saved · ${debtors.size} owing")
            }

            item {
                val owes = totalDebt > 0.009
                val shown = figuresShown()
                AppCard(
                    contentPadding = PaddingValues(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 6.dp),
                    pattern = toneColors(if (owes) Tone.DANGER else Tone.SUCCESS).content.copy(alpha = if (AppTheme.status.isDark) 0.26f else 0.2f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            if (owes) Icons.Default.Schedule else Icons.Default.CheckCircle,
                            tone = if (owes) Tone.DANGER else Tone.SUCCESS,
                            size = 44.dp, iconSize = 22.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (owes) "Outstanding balance" else "All accounts settled",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (shown) Formatters.formatCurrency(totalDebt, settings.currencySymbol) else maskedAmount(settings.currencySymbol),
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (owes) toneColors(Tone.DANGER).content else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.maskedSemantics(!shown)
                            )
                        }
                        FiguresToggleButton(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchField(searchQuery, { searchQuery = it }, "Search name or phone", Modifier.testTag("customer_search_input"))
                    SegmentedControl(
                        options = CustomerTab.entries,
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                        label = { "${it.label} (${if (it == CustomerTab.DEBTORS) debtors.size else customers.size})" }
                    )
                }
            }

            if (filteredList.isEmpty()) {
                item {
                    AppCard {
                        when {
                            searchQuery.isNotBlank() -> EmptyState(Icons.Default.PersonSearch, "No matches", "No customer matches \"$searchQuery\".")
                            selectedTab == CustomerTab.DEBTORS -> EmptyState(
                                Icons.Default.CheckCircle, "Nobody owes you", "Every customer has cleared their balance.", tone = Tone.SUCCESS
                            )
                            else -> EmptyState(
                                Icons.Default.People, "No customers yet",
                                "Customers are saved automatically when you make a sale, or you can add one now.",
                                tone = Tone.PRIMARY, actionText = "Add customer", onAction = { showAddDialog = true }
                            )
                        }
                    }
                }
            } else {
                items(filteredList, key = { it.id }) { customer ->
                    val customerOrders = remember(orders, customer.id) { orders.filter { it.customerId == customer.id } }
                    val customerPayments = remember(payments, customer.id) { payments.filter { it.customerId == customer.id } }
                    CustomerDebtCard(
                        customer = customer,
                        orders = customerOrders,
                        payments = customerPayments,
                        settings = settings,
                        onRecordPayment = { onRecordPayment(customer) },
                        onSendReminderSms = { onSendReminderSms(customer) },
                        onEditCustomer = { customerToEdit = customer },
                        onViewPaymentHistory = { customerForHistoryDialog = customer },
                        onRecordOrderPayment = { order -> onRecordOrderPayment?.invoke(order) },
                        onViewReceipt = { order -> onViewReceipt?.invoke(order) },
                        onShareWhatsAppStatement = { shareStatement(context, customer, customerOrders, settings) }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
            text = { Text("Add customer", style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("add_customer_fab")
        )
    }

    if (showAddDialog || customerToEdit != null) {
        CustomerFormDialog(
            customer = customerToEdit,
            currencySymbol = settings.currencySymbol,
            onDismiss = {
                showAddDialog = false
                customerToEdit = null
            },
            onSaveCustomer = { updated ->
                onSaveCustomer(updated)
                showAddDialog = false
                customerToEdit = null
            },
            onDeleteCustomer = { toDelete ->
                onDeleteCustomer(toDelete)
                showAddDialog = false
                customerToEdit = null
            }
        )
    }

    customerForHistoryDialog?.let { targetCustomer ->
        val custOrders = remember(orders, targetCustomer.id) { orders.filter { it.customerId == targetCustomer.id } }
        val custPayments = remember(payments, targetCustomer.id) { payments.filter { it.customerId == targetCustomer.id } }
        CustomerPaymentHistoryDialog(
            customer = targetCustomer,
            orders = custOrders,
            payments = custPayments,
            settings = settings,
            onDismiss = { customerForHistoryDialog = null },
            onRecordPayment = { cust ->
                customerForHistoryDialog = null
                onRecordPayment(cust)
            },
            onRecordOrderPayment = { order ->
                customerForHistoryDialog = null
                onRecordOrderPayment?.invoke(order)
            },
            onViewReceipt = { order ->
                customerForHistoryDialog = null
                onViewReceipt?.invoke(order)
            },
            onShareStatement = { cust -> shareStatement(context, cust, custOrders, settings) }
        )
    }
}

private fun shareStatement(
    context: android.content.Context,
    customer: CustomerEntity,
    orders: List<SaleOrderEntity>,
    settings: AppSettingsEntity
) {
    val phone = customer.whatsapp.ifBlank { customer.phone }
    val imageFile = DigitalReceiptGenerator.generateCustomerStatementBitmap(context, customer, orders, settings)
    if (imageFile != null) {
        DigitalReceiptGenerator.shareDigitalFile(
            context = context,
            file = imageFile,
            mimeType = "image/png",
            captionText = "📋 Account statement for ${customer.name} from ${settings.businessName}",
            recipientPhone = phone,
            preferWhatsApp = true
        )
    } else {
        SmsService.shareViaWhatsApp(context, phone, Formatters.buildWhatsAppCustomerStatement(customer, orders, settings))
    }
}

@Composable
fun CustomerDebtCard(
    customer: CustomerEntity,
    orders: List<SaleOrderEntity> = emptyList(),
    payments: List<PaymentEntity> = emptyList(),
    settings: AppSettingsEntity,
    onRecordPayment: () -> Unit,
    onSendReminderSms: () -> Unit,
    onEditCustomer: () -> Unit,
    onViewPaymentHistory: () -> Unit = {},
    onRecordOrderPayment: ((SaleOrderEntity) -> Unit)? = null,
    onViewReceipt: ((SaleOrderEntity) -> Unit)? = null,
    onShareWhatsAppStatement: () -> Unit
) {
    val unpaidOrders = remember(orders) { orders.filter { it.balanceDue > 0.0 }.sortedBy { it.timestamp } }
    val unpaidDebtSum = remember(unpaidOrders) { unpaidOrders.sumOf { it.balanceDue } }
    val displayDebt = maxOf(customer.currentBalance, unpaidDebtSum)
    val owesMoney = displayDebt > 0.009
    var isExpandedInline by remember { mutableStateOf(false) }
    val paidOrders = remember(orders) { orders.filter { it.balanceDue <= 0.0 } }
    val currency = settings.currencySymbol
    val c = MaterialTheme.colorScheme

    AppCard(contentPadding = PaddingValues(14.dp), modifier = Modifier.testTag("customer_debt_card_${customer.id}")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onViewPaymentHistory)
                .testTag("view_payment_history_banner_${customer.id}")
        ) {
            Avatar(customer.name, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(customer.name, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(customer.phone.ifBlank { "No phone" }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Formatters.formatCurrency(displayDebt, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (owesMoney) toneColors(Tone.DANGER).content else c.onSurface
                )
                StatusBadge(if (owesMoney) "Owes" else "Settled", if (owesMoney) Tone.DANGER else Tone.SUCCESS)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniStat("Bought", Formatters.formatCurrency(customer.totalPurchases, currency))
            MiniStat("Paid", Formatters.formatCurrency(customer.totalPaid, currency))
            MiniStat(
                "Invoices", "${paidOrders.size} paid · ${unpaidOrders.size} open", alignEnd = true,
                modifier = Modifier.testTag("badge_carrying_balance_${customer.id}")
            )
        }

        if (customer.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(customer.notes, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        AnimatedVisibility(visible = isExpandedInline) {
            Column(Modifier.padding(top = 12.dp)) {
                CustomerPaymentHistoryComponent(
                    customer = customer,
                    orders = orders,
                    payments = payments,
                    settings = settings,
                    onRecordPayment = { onRecordPayment() },
                    onRecordOrderPayment = { order -> onRecordOrderPayment?.invoke(order) },
                    onViewReceipt = { order -> onViewReceipt?.invoke(order) },
                    onShareStatement = { onShareWhatsAppStatement() }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (owesMoney) {
                AppButton(
                    "Pay", onRecordPayment, variant = ButtonVariant.SUCCESS, icon = Icons.Default.Payments,
                    height = 42.dp, modifier = Modifier.weight(1f).testTag("pay_debt_button_${customer.id}")
                )
                CircleIconButton(
                    Icons.Default.Sms, "Send SMS reminder", onSendReminderSms, tone = Tone.PRIMARY, size = 42.dp,
                    modifier = Modifier.testTag("sms_reminder_button_${customer.id}")
                )
            } else {
                AppButton(
                    if (isExpandedInline) "Hide history" else "Show history", { isExpandedInline = !isExpandedInline },
                    variant = ButtonVariant.SECONDARY,
                    icon = if (isExpandedInline) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    height = 42.dp, modifier = Modifier.weight(1f).testTag("toggle_inline_history_${customer.id}")
                )
            }
            CircleIconButton(
                Icons.Default.History, "Payment history", onViewPaymentHistory, size = 42.dp,
                modifier = Modifier.testTag("payment_history_button_${customer.id}")
            )
            CircleIconButton(
                Icons.Default.Share, "Share statement on WhatsApp", onShareWhatsAppStatement, tone = Tone.SUCCESS, size = 42.dp,
                modifier = Modifier.testTag("whatsapp_statement_button_${customer.id}")
            )
            CircleIconButton(Icons.Default.Edit, "Edit customer", onEditCustomer, size = 42.dp)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Column(modifier = modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
