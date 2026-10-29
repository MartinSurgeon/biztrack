package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AppSettingsEntity
import com.example.data.model.CustomerEntity
import com.example.data.model.ProductEntity
import com.example.data.model.SaleOrderEntity
import com.example.data.remote.SmsService
import com.example.ui.components.*
import com.example.ui.theme.AppTheme
import com.example.util.DigitalReceiptGenerator
import com.example.util.Formatters
import java.util.Calendar

enum class DateRangeFilter(val label: String) {
    ALL("All"),
    TODAY("Today"),
    THIS_WEEK("Week"),
    THIS_MONTH("Month")
}

enum class PaymentStatusFilter(val label: String) {
    ALL("All"),
    PAID("Paid"),
    PARTIAL("Partial"),
    UNPAID("Unpaid")
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun SalesDashboardScreen(
    orders: List<SaleOrderEntity>,
    debtors: List<CustomerEntity>,
    totalDebt: Double,
    totalRevenue: Double,
    totalCollected: Double,
    settings: AppSettingsEntity,
    onNavigateToNewSale: () -> Unit,
    onNavigateToDebtors: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onSelectOrderReceipt: (SaleOrderEntity) -> Unit,
    onSendSms: (SaleOrderEntity) -> Unit,
    onEditOrder: ((SaleOrderEntity) -> Unit)? = null,
    onVoidOrder: ((SaleOrderEntity) -> Unit)? = null,
    products: List<ProductEntity> = emptyList()
) {
    val context = LocalContext.current
    val currency = settings.currencySymbol
    var orderToVoid by remember { mutableStateOf<SaleOrderEntity?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedDateRange by remember { mutableStateOf(DateRangeFilter.ALL) }
    var selectedStatus by remember { mutableStateOf(PaymentStatusFilter.ALL) }

    val todayStart = remember(orders) { startOfToday() }
    val todayOrders = remember(orders, todayStart) { orders.filter { it.timestamp >= todayStart } }
    val todaySales = todayOrders.sumOf { it.totalAmount }
    val lowStock = remember(products) { products.filter { it.stockQuantity <= it.minStockThreshold } }

    val filteredOrders = remember(orders, searchQuery, selectedDateRange, selectedStatus) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfToday()
        val startOfDay = cal.timeInMillis
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val startOfWeek = cal.timeInMillis
        cal.timeInMillis = startOfDay
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val startOfMonth = cal.timeInMillis
        val query = searchQuery.trim().lowercase()

        orders.filter { order ->
            val matchesSearch = query.isEmpty() ||
                order.customerName.lowercase().contains(query) ||
                order.invoiceNumber.lowercase().contains(query) ||
                order.customerPhone.contains(query) ||
                order.itemsJson.lowercase().contains(query) ||
                order.notes.lowercase().contains(query)
            val matchesDate = when (selectedDateRange) {
                DateRangeFilter.ALL -> true
                DateRangeFilter.TODAY -> order.timestamp >= startOfDay
                DateRangeFilter.THIS_WEEK -> order.timestamp >= startOfWeek
                DateRangeFilter.THIS_MONTH -> order.timestamp >= startOfMonth
            }
            val matchesStatus = selectedStatus == PaymentStatusFilter.ALL || order.paymentStatus == selectedStatus.name
            matchesSearch && matchesDate && matchesStatus
        }
    }
    val filtersActive = searchQuery.isNotBlank() || selectedDateRange != DateRangeFilter.ALL || selectedStatus != PaymentStatusFilter.ALL

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeader(
                title = settings.businessName.ifBlank { "My Business" },
                eyebrow = "${greeting()} · ${Formatters.formatDateShort(System.currentTimeMillis()).substringBefore(",")}",
                modifier = Modifier.testTag("farm_header_card")
            ) {
                Avatar(settings.businessName.ifBlank { "B" }, size = 44.dp)
            }
        }

        // Hero: total sales with collected / outstanding split
        item {
            HeroSalesCard(
                totalRevenue = totalRevenue,
                totalCollected = totalCollected,
                totalDebt = totalDebt,
                debtorCount = debtors.size,
                currency = currency,
                onClickDebt = onNavigateToDebtors
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("New sale", Icons.Default.AddShoppingCart, Tone.PRIMARY, Modifier.weight(1f).testTag("dashboard_new_sale_button"), onNavigateToNewSale)
                QuickAction("Collect", Icons.Default.Payments, Tone.SUCCESS, Modifier.weight(1f).testTag("dashboard_debtors_button"), onNavigateToDebtors)
                QuickAction("Products", Icons.Default.Inventory2, Tone.INFO, Modifier.weight(1f), onNavigateToProducts)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricMiniCard("Today", Formatters.formatCurrency(todaySales, currency), Icons.AutoMirrored.Filled.TrendingUp, Tone.PRIMARY, Modifier.weight(1f),
                    caption = "${todayOrders.size} ${if (todayOrders.size == 1) "sale" else "sales"}", hiddenValue = maskedAmount(currency))
                MetricMiniCard("Invoices", orders.size.toString(), Icons.AutoMirrored.Filled.ReceiptLong, Tone.NEUTRAL, Modifier.weight(1f),
                    caption = "all time")
            }
        }

        if (lowStock.isNotEmpty()) {
            item {
                AppCard(onClick = onNavigateToProducts, contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Inventory, tone = Tone.WARNING)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${lowStock.size} ${if (lowStock.size == 1) "product is" else "products are"} running low",
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(lowStock.take(3).joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(
                    title = "Recent sales",
                    trailing = if (filtersActive) "Reset" else "${orders.size} total",
                    onTrailingClick = if (filtersActive) ({
                        searchQuery = ""; selectedDateRange = DateRangeFilter.ALL; selectedStatus = PaymentStatusFilter.ALL
                    }) else null
                )
                SearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search customer, invoice, product",
                    modifier = Modifier.testTag("sales_search_input")
                )
                SegmentedControl(
                    options = DateRangeFilter.entries,
                    selected = selectedDateRange,
                    onSelect = { selectedDateRange = it },
                    label = { it.label }
                )
                ChipRow {
                    PaymentStatusFilter.entries.forEach { f ->
                        PillChip(
                            text = f.label,
                            selected = selectedStatus == f,
                            onClick = { selectedStatus = f },
                            count = if (f == PaymentStatusFilter.ALL) null else orders.count { it.paymentStatus == f.name }
                        )
                    }
                }
            }
        }

        when {
            orders.isEmpty() -> item {
                AppCard {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        title = "No sales yet",
                        message = "Record your first sale and it will show up here with its receipt.",
                        tone = Tone.PRIMARY,
                        actionText = "Start a sale",
                        onAction = onNavigateToNewSale
                    )
                }
            }
            filteredOrders.isEmpty() -> item {
                AppCard {
                    EmptyState(
                        icon = Icons.Default.FilterAltOff,
                        title = "No matching sales",
                        message = "Try a different search, date range or payment status.",
                        actionText = "Clear filters",
                        onAction = { searchQuery = ""; selectedDateRange = DateRangeFilter.ALL; selectedStatus = PaymentStatusFilter.ALL }
                    )
                }
            }
            else -> items(filteredOrders, key = { it.id }) { order ->
                SaleOrderCard(
                    order = order,
                    settings = settings,
                    onClickReceipt = { onSelectOrderReceipt(order) },
                    onWhatsAppShare = {
                        val phone = order.customerWhatsapp.ifBlank { order.customerPhone }
                        val imageFile = DigitalReceiptGenerator.generateReceiptBitmap(context, order, settings)
                        if (imageFile != null) {
                            DigitalReceiptGenerator.shareDigitalFile(
                                context = context,
                                file = imageFile,
                                mimeType = "image/png",
                                captionText = "🧾 Receipt #${order.invoiceNumber} from ${settings.businessName}",
                                recipientPhone = phone,
                                preferWhatsApp = true
                            )
                        } else {
                            SmsService.shareViaWhatsApp(context, phone, Formatters.buildWhatsAppReceipt(order, settings))
                        }
                    },
                    onSendSms = { onSendSms(order) },
                    onEditOrder = onEditOrder?.let { { it(order) } },
                    onVoidOrder = onVoidOrder?.let { { orderToVoid = order } }
                )
            }
        }
    }

    val target = orderToVoid
    if (target != null && onVoidOrder != null) {
        val debtMsg = if (target.balanceDue > 0) "\n• Reverse the customer's balance of ${Formatters.formatCurrency(target.balanceDue, currency)}" else ""
        ConfirmationDialog(
            title = "Void this sale?",
            message = "Invoice #${target.invoiceNumber} for ${target.customerName} will be removed.\n\n• Return all items to stock$debtMsg",
            confirmButtonText = "Void sale",
            cancelButtonText = "Keep",
            isDestructive = true,
            onConfirm = {
                orderToVoid = null
                onVoidOrder(target)
            },
            onDismiss = { orderToVoid = null }
        )
    }
}

@Composable
private fun HeroSalesCard(
    totalRevenue: Double,
    totalCollected: Double,
    totalDebt: Double,
    debtorCount: Int,
    currency: String,
    onClickDebt: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    // Solid indigo in light mode; a deep indigo container in dark mode so the card doesn't glare.
    val dark = AppTheme.status.isDark
    val onHero = if (dark) c.onPrimaryContainer else c.onPrimary
    val shown = figuresShown()
    val money = { amount: Double -> if (shown) Formatters.formatCurrency(amount, currency) else maskedAmount(currency) }
    AppCard(
        color = if (dark) c.primaryContainer else c.primary,
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
        pattern = onHero.copy(alpha = if (dark) 0.18f else 0.24f),
        modifier = Modifier.testTag("debt_hero_card")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total sales", style = MaterialTheme.typography.labelLarge, color = onHero.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            FiguresToggleButton(tint = onHero, modifier = Modifier.testTag("figures_toggle"))
        }
        Column(Modifier.padding(end = 12.dp)) {
            Text(money(totalRevenue), style = MaterialTheme.typography.headlineLarge, color = onHero,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.maskedSemantics(!shown))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("Collected", money(totalCollected), Icons.Default.CheckCircle, onHero, Modifier.weight(1f), masked = !shown)
                HeroStat(
                    label = if (debtorCount > 0) "Owed · $debtorCount" else "Owed",
                    value = money(totalDebt),
                    icon = Icons.Default.Schedule,
                    onHero = onHero,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).pressable(onClick = onClickDebt),
                    masked = !shown
                )
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, icon: ImageVector, onHero: Color, modifier: Modifier = Modifier, masked: Boolean = false) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(onHero.copy(alpha = 0.12f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = onHero, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = onHero.copy(alpha = 0.9f), maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = onHero, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.maskedSemantics(masked))
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    AppCard(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(icon, tone = tone, size = 44.dp, iconSize = 22.dp, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
fun MetricMiniCard(
    title: String,
    value: String,
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    caption: String? = null,
    hiddenValue: String? = null // money: shown instead of [value] while card figures are hidden
) {
    val context = LocalContext.current
    val sensitive = hiddenValue != null
    val masked = sensitive && !figuresShown()
    AppCard(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
        onClick = if (sensitive) ({ FigurePrivacy.toggle(context) }) else null,
        pattern = toneColors(tone).content.copy(alpha = if (AppTheme.status.isDark) 0.26f else 0.2f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tone = tone, size = 32.dp, iconSize = 16.dp, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (sensitive) {
                Icon(
                    if (masked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (masked) "Show amounts" else "Hide amounts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (masked) hiddenValue!! else value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.maskedSemantics(masked))
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun paymentStatusTone(status: String): Tone = when (status) {
    "PAID" -> Tone.SUCCESS
    "PARTIAL" -> Tone.WARNING
    else -> Tone.DANGER
}

fun paymentStatusLabel(status: String): String = when (status) {
    "PAID" -> "Paid"
    "PARTIAL" -> "Partial"
    else -> "Unpaid"
}

fun formatQty(qty: Double): String = if (qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()

@Composable
fun SaleOrderCard(
    order: SaleOrderEntity,
    settings: AppSettingsEntity,
    onClickReceipt: () -> Unit,
    onWhatsAppShare: () -> Unit,
    onSendSms: () -> Unit,
    onEditOrder: (() -> Unit)? = null,
    onVoidOrder: (() -> Unit)? = null
) {
    val items = remember(order.itemsJson) { Formatters.parseCartItems(order.itemsJson) }
    val currency = settings.currencySymbol

    AppCard(
        onClick = onClickReceipt,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("sale_order_card_${order.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(order.customerName, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(order.customerName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("#${order.invoiceNumber} · ${Formatters.formatDateShort(order.timestamp)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatters.formatCurrency(order.totalAmount, currency), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                StatusBadge(paymentStatusLabel(order.paymentStatus), paymentStatusTone(order.paymentStatus))
            }
        }

        if (items.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = items.joinToString(" · ") { "${formatQty(it.quantity)}× ${it.productName}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(12.dp))
        HairlineDivider()
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    order.balanceDue > 0 -> Text("Balance ${Formatters.formatCurrency(order.balanceDue, currency)}",
                        style = MaterialTheme.typography.labelLarge, color = toneColors(Tone.DANGER).content)
                    else -> Text("Settled", style = MaterialTheme.typography.labelLarge, color = toneColors(Tone.SUCCESS).content)
                }
                if (order.discountAmount > 0) {
                    Text("Discount ${Formatters.formatCurrency(order.discountAmount, currency)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onEditOrder != null) {
                    CircleIconButton(Icons.Default.Edit, "Edit invoice", onEditOrder, size = 36.dp,
                        modifier = Modifier.testTag("edit_order_button_${order.id}"))
                }
                CircleIconButton(Icons.Default.Share, "Share on WhatsApp", onWhatsAppShare, tone = Tone.SUCCESS, size = 36.dp,
                    modifier = Modifier.testTag("whatsapp_order_button_${order.id}"))
                CircleIconButton(Icons.Default.Sms, "Send SMS", onSendSms, tone = Tone.PRIMARY, size = 36.dp,
                    modifier = Modifier.testTag("sms_order_button_${order.id}"))
                if (onVoidOrder != null) {
                    CircleIconButton(Icons.Default.DeleteOutline, "Void sale", onVoidOrder, tone = Tone.DANGER, size = 36.dp,
                        modifier = Modifier.testTag("void_order_button_${order.id}"))
                }
            }
        }
    }
}
