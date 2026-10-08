package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.*
import com.example.ui.theme.*
import com.example.util.Formatters

/**
 * Visual List Component showing the history of part payments per customer,
 * making it easy to track which transactions were paid in full and which carry remaining balances.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerPaymentHistoryComponent(
    customer: CustomerEntity,
    orders: List<SaleOrderEntity>,
    payments: List<PaymentEntity>,
    settings: AppSettingsEntity,
    onRecordPayment: (CustomerEntity) -> Unit,
    onRecordOrderPayment: (SaleOrderEntity) -> Unit,
    onViewReceipt: (SaleOrderEntity) -> Unit,
    onShareStatement: ((CustomerEntity) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedSectionTab by remember { mutableStateOf(0) } // 0: Transactions (Paid vs Owning), 1: Part Payments Ledger
    var transactionFilter by remember { mutableStateOf(0) } // 0: All, 1: Carrying Balance, 2: Paid In Full

    val paidOrders = remember(orders) { orders.filter { it.balanceDue <= 0.0 } }
    val unpaidOrders = remember(orders) { orders.filter { it.balanceDue > 0.0 } }

    val filteredOrders = remember(orders, transactionFilter) {
        when (transactionFilter) {
            1 -> unpaidOrders
            2 -> paidOrders
            else -> orders
        }
    }

    val customerPayments = remember(payments, customer.id) {
        payments.filter { it.customerId == customer.id }.sortedByDescending { it.timestamp }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("customer_payment_history_component_${customer.id}")
    ) {
        // 1. Customer Financial Summary Header
        CustomerFinancialSummaryHeader(
            customer = customer,
            ordersCount = orders.size,
            paidOrdersCount = paidOrders.size,
            unpaidOrdersCount = unpaidOrders.size,
            currencySymbol = settings.currencySymbol
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Navigation Tabs between Transactions Breakdown & Part Payments Ledger
        PrimaryTabRow(
            selectedTabIndex = selectedSectionTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = selectedSectionTab == 0,
                onClick = { selectedSectionTab = 0 },
                modifier = Modifier.testTag("tab_transactions_breakdown"),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Transactions (${orders.size})",
                            fontWeight = if (selectedSectionTab == 0) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            )
            Tab(
                selected = selectedSectionTab == 1,
                onClick = { selectedSectionTab = 1 },
                modifier = Modifier.testTag("tab_part_payments_history"),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Payments,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Part Payments (${customerPayments.size})",
                            fontWeight = if (selectedSectionTab == 1) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Tab Content
        if (selectedSectionTab == 0) {
            // Filter Chips for Transactions: All / Remaining Balance / Paid in Full
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = transactionFilter == 0,
                    onClick = { transactionFilter = 0 },
                    label = { Text("All (${orders.size})", fontSize = 12.sp) },
                    modifier = Modifier.testTag("filter_all_transactions")
                )

                FilterChip(
                    selected = transactionFilter == 1,
                    onClick = { transactionFilter = 1 },
                    label = {
                        Text(
                            text = "Remaining Bal (${unpaidOrders.size})",
                            fontSize = 12.sp,
                            fontWeight = if (unpaidOrders.isNotEmpty()) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DebtRedContainer,
                        selectedLabelColor = DebtRed
                    ),
                    modifier = Modifier.testTag("filter_unpaid_transactions")
                )

                FilterChip(
                    selected = transactionFilter == 2,
                    onClick = { transactionFilter = 2 },
                    label = { Text("Paid in Full (${paidOrders.size})", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FarmGreenContainer,
                        selectedLabelColor = FarmGreen
                    ),
                    modifier = Modifier.testTag("filter_paid_transactions")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Transactions List
            if (filteredOrders.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (transactionFilter == 1) Icons.Default.CheckCircle else Icons.Default.Receipt,
                            contentDescription = null,
                            tint = if (transactionFilter == 1) FarmGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (transactionFilter) {
                                1 -> "No Outstanding Invoices!"
                                2 -> "No fully paid invoices yet."
                                else -> "No transactions recorded for this customer."
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (transactionFilter == 1) {
                            Text(
                                text = "All orders have been paid in full.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    filteredOrders.forEach { order ->
                        TransactionItemCard(
                            order = order,
                            currencySymbol = settings.currencySymbol,
                            onRecordOrderPayment = { onRecordOrderPayment(order) },
                            onViewReceipt = { onViewReceipt(order) }
                        )
                    }
                }
            }
        } else {
            // Part Payments Timeline / Ledger View
            PartPaymentsTimelineList(
                payments = customerPayments,
                currencySymbol = settings.currencySymbol,
                onRecordFirstPayment = { onRecordPayment(customer) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Bottom Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (customer.currentBalance > 0) {
                Button(
                    onClick = { onRecordPayment(customer) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("action_record_part_payment"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FarmGreen)
                ) {
                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Record Part Payment", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            if (onShareStatement != null) {
                OutlinedButton(
                    onClick = { onShareStatement(customer) },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("action_share_statement"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = WhatsAppGreen)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Statement", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Clean financial header showing total purchases, total paid, and debt balance.
 */
@Composable
private fun CustomerFinancialSummaryHeader(
    customer: CustomerEntity,
    ordersCount: Int,
    paidOrdersCount: Int,
    unpaidOrdersCount: Int,
    currencySymbol: String
) {
    val totalPurchases = customer.totalPurchases
    val totalPaid = customer.totalPaid
    val currentBalance = customer.currentBalance
    val isDebtFree = currentBalance <= 0.0

    val paymentProgress = if (totalPurchases > 0) {
        (totalPaid / totalPurchases).toFloat().coerceIn(0f, 1f)
    } else {
        1f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDebtFree) FarmGreenContainer.copy(alpha = 0.5f) else DebtRedContainer.copy(alpha = 0.45f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isDebtFree) FarmGreen.copy(alpha = 0.4f) else DebtRed.copy(alpha = 0.4f)
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Row 1: Customer Name and Debt Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = customer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (customer.phone.isNotBlank()) {
                        Text(
                            text = "📞 ${customer.phone}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDebtFree) FarmGreen else DebtRed
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isDebtFree) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isDebtFree) "CLEARED" else "OWES DEBT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: 3-Column Metrics (Purchases, Paid, Balance)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Column 1: Total Billed
                Column {
                    Text(
                        text = "Total Billed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Text(
                        text = Formatters.formatCurrency(totalPurchases, currencySymbol),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Column 2: Total Paid
                Column {
                    Text(
                        text = "Total Paid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Text(
                        text = Formatters.formatCurrency(totalPaid, currencySymbol),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = FarmGreen
                    )
                }

                // Column 3: Balance
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Balance Due",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Text(
                        text = Formatters.formatCurrency(currentBalance, currencySymbol),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isDebtFree) FarmGreen else DebtRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: Progress Bar for Payment Ratio
            LinearProgressIndicator(
                progress = { paymentProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (isDebtFree) FarmGreen else BrandAmberGold,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(paymentProgress * 100).toInt()}% of total billed paid",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$paidOrdersCount Paid • $unpaidOrdersCount Balance Due",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unpaidOrdersCount > 0) DebtRed else FarmGreen
                )
            }
        }
    }
}

/**
 * Card displaying an individual sales transaction, showing whether it is
 * PAID IN FULL or CARRYING A REMAINING BALANCE.
 */
@Composable
private fun TransactionItemCard(
    order: SaleOrderEntity,
    currencySymbol: String,
    onRecordOrderPayment: () -> Unit,
    onViewReceipt: () -> Unit
) {
    val isPaidInFull = order.balanceDue <= 0.0
    val items = remember(order.itemsJson) { Formatters.parseCartItems(order.itemsJson) }
    val percentPaid = if (order.totalAmount > 0) {
        (order.amountPaid / order.totalAmount).toFloat().coerceIn(0f, 1f)
    } else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transaction_item_card_${order.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPaidInFull) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isPaidInFull) FarmGreen.copy(alpha = 0.5f) else DebtRed.copy(alpha = 0.5f)
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Invoice Number, Date, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#${order.invoiceNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = Formatters.formatDate(order.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                // Explicit Status Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPaidInFull) FarmGreenContainer else DebtRedContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPaidInFull) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isPaidInFull) FarmGreen else DebtRed,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPaidInFull) "PAID IN FULL" else "REMAINING BALANCE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isPaidInFull) FarmGreen else DebtRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Items Purchased Breakdown
            if (items.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val qtyStr = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString()
                                Text(
                                    text = "• $qtyStr ${item.unit} ${item.productName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = Formatters.formatCurrency(item.lineTotal, currencySymbol),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Financial Summary Row: Total, Paid, Balance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Total: ${Formatters.formatCurrency(order.totalAmount, currencySymbol)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Paid: ${Formatters.formatCurrency(order.amountPaid, currencySymbol)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = FarmGreen
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isPaidInFull) "Status:" else "Balance Due:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Text(
                        text = if (isPaidInFull) {
                            "GH₵ 0.00 (Cleared)"
                        } else {
                            Formatters.formatCurrency(order.balanceDue, currencySymbol)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isPaidInFull) FarmGreen else DebtRed
                    )
                }
            }

            // Payment completion progress bar
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { percentPaid },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (isPaidInFull) FarmGreen else BrandAmberGold,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row: Receipt & Pay Order
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onViewReceipt,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View Receipt", fontSize = 12.sp)
                }

                if (!isPaidInFull) {
                    Button(
                        onClick = onRecordOrderPayment,
                        colors = ButtonDefaults.buttonColors(containerColor = FarmGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("pay_order_button_${order.id}")
                    ) {
                        Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pay Toward Order", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Visual timeline list of part-payments and installment entries for a customer.
 */
@Composable
private fun PartPaymentsTimelineList(
    payments: List<PaymentEntity>,
    currencySymbol: String,
    onRecordFirstPayment: () -> Unit
) {
    if (payments.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.HourglassEmpty,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(42.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "No Part Payments Logged",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Any initial deposit or installment payments recorded for this customer will appear here chronologically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onRecordFirstPayment,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Record Payment Now")
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Chronological Part Payments & Installments",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            payments.forEachIndexed { index, payment ->
                PartPaymentTimelineItem(
                    payment = payment,
                    isFirst = index == 0,
                    isLast = index == payments.size - 1,
                    currencySymbol = currencySymbol
                )
            }
        }
    }
}

/**
 * Individual timeline item for a recorded part payment.
 */
@Composable
private fun PartPaymentTimelineItem(
    payment: PaymentEntity,
    isFirst: Boolean,
    isLast: Boolean,
    currencySymbol: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("part_payment_item_${payment.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left icon indicator
            Surface(
                shape = CircleShape,
                color = FarmGreenContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Payment recorded",
                        tint = FarmGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "+${Formatters.formatCurrency(payment.amount, currencySymbol)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = FarmGreen
                    )

                    // Payment method badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = payment.paymentMethod,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "📅 ${Formatters.formatDate(payment.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                if (payment.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "📝 ${payment.notes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Remaining balance indicator at this step
                Surface(
                    color = if (payment.balanceAfterPayment <= 0.0) FarmGreenContainer.copy(alpha = 0.5f) else DebtRedContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (payment.balanceAfterPayment <= 0.0) {
                            "Account balance after this payment: Cleared (0.00)"
                        } else {
                            "Remaining debt balance after payment: ${Formatters.formatCurrency(payment.balanceAfterPayment, currencySymbol)}"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (payment.balanceAfterPayment <= 0.0) FarmGreen else DebtRed
                    )
                }
            }
        }
    }
}

/**
 * Dedicated Modal Dialog for inspecting the full payment history & transaction breakdown
 * of a specific customer.
 */
@Composable
fun CustomerPaymentHistoryDialog(
    customer: CustomerEntity,
    orders: List<SaleOrderEntity>,
    payments: List<PaymentEntity>,
    settings: AppSettingsEntity,
    onDismiss: () -> Unit,
    onRecordPayment: (CustomerEntity) -> Unit,
    onRecordOrderPayment: (SaleOrderEntity) -> Unit,
    onViewReceipt: (SaleOrderEntity) -> Unit,
    onShareStatement: ((CustomerEntity) -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .fillMaxHeight(0.9f)
                    .testTag("customer_payment_history_dialog_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Dialog Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BrandGreenPrimary.copy(alpha = 0.12f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.HistoryEdu,
                                        contentDescription = null,
                                        tint = BrandGreenPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Payment & Debt History",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = customer.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_payment_history_dialog")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable content inside the dialog
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            CustomerPaymentHistoryComponent(
                                customer = customer,
                                orders = orders,
                                payments = payments,
                                settings = settings,
                                onRecordPayment = onRecordPayment,
                                onRecordOrderPayment = onRecordOrderPayment,
                                onViewReceipt = onViewReceipt,
                                onShareStatement = onShareStatement
                            )
                        }
                    }
                }
            }
        }
    }
}
