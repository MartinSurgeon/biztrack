package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.*
import com.example.ui.theme.BrandGreenPrimary
import com.example.ui.theme.DebtRed
import com.example.ui.theme.DebtRedContainer
import com.example.ui.theme.FarmGreen
import com.example.ui.theme.FarmGreenContainer
import com.example.ui.theme.BrandAmberGold
import com.example.ui.theme.BrandAmberGoldContainer
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberContainer
import com.example.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditInvoiceDialog(
    order: SaleOrderEntity,
    products: List<ProductEntity>,
    customers: List<CustomerEntity>,
    settings: AppSettingsEntity,
    onDismiss: () -> Unit,
    onSaveOrder: (updatedOrder: SaleOrderEntity, originalOrder: SaleOrderEntity) -> Unit
) {
    // 1. Items State
    val initialItems = remember(order) { Formatters.parseCartItems(order.itemsJson) }
    var itemsList by remember { mutableStateOf<List<CartItem>>(initialItems) }

    // 2. Customer State
    var customerName by remember { mutableStateOf(order.customerName) }
    var customerPhone by remember { mutableStateOf(order.customerPhone) }
    var customerWhatsapp by remember { mutableStateOf(order.customerWhatsapp) }
    var customerId by remember { mutableStateOf(order.customerId) }

    // 3. Payment State
    var discountText by remember {
        mutableStateOf(
            if (order.discountAmount > 0) {
                if (order.discountAmount % 1.0 == 0.0) order.discountAmount.toInt().toString() else order.discountAmount.toString()
            } else ""
        )
    }
    var amountPaidText by remember {
        mutableStateOf(
            if (order.amountPaid % 1.0 == 0.0) order.amountPaid.toInt().toString() else order.amountPaid.toString()
        )
    }
    var paymentMethod by remember {
        mutableStateOf(
            try {
                PaymentMethod.valueOf(order.paymentMethod)
            } catch (e: Exception) {
                PaymentMethod.CASH
            }
        )
    }
    var notes by remember { mutableStateOf(order.notes) }
    var showAddProductSheet by remember { mutableStateOf(false) }

    // Calculations
    val subtotal = remember(itemsList) { itemsList.sumOf { it.lineTotal } }
    val discountDouble = (discountText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
    val totalAmount = (subtotal - discountDouble).coerceAtLeast(0.0)
    val amountPaidDouble = amountPaidText.toDoubleOrNull() ?: 0.0
    val balanceDue = (totalAmount - amountPaidDouble).coerceAtLeast(0.0)
    val isPaidInFull = balanceDue <= 0.0 && totalAmount > 0.0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .widthIn(max = 560.dp)
                    .testTag("edit_invoice_dialog_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
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
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = BrandGreenPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Edit Invoice",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "#${order.invoiceNumber} • ${Formatters.formatDate(order.timestamp)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("edit_invoice_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // Scrollable Form Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // SECTION 1: Customer Details
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Customer Information",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedTextField(
                                    value = customerName,
                                    onValueChange = { name ->
                                        customerName = name
                                        val matched = customers.find { it.name.equals(name.trim(), ignoreCase = true) }
                                        if (matched != null) {
                                            customerId = matched.id
                                            if (customerPhone.isBlank()) customerPhone = matched.phone
                                            if (customerWhatsapp.isBlank()) customerWhatsapp = matched.whatsapp
                                        }
                                    },
                                    label = { Text("Customer Name *") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("edit_customer_name_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customerPhone,
                                        onValueChange = { customerPhone = it },
                                        label = { Text("Phone Number") },
                                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_customer_phone_input"),
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    OutlinedTextField(
                                        value = customerWhatsapp,
                                        onValueChange = { customerWhatsapp = it },
                                        label = { Text("WhatsApp (Optional)") },
                                        leadingIcon = { Icon(Icons.Default.Chat, contentDescription = null) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_customer_whatsapp_input"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }

                        // SECTION 2: Items Purchased List & Modification
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Invoice Items (${itemsList.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    FilledTonalButton(
                                        onClick = { showAddProductSheet = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .testTag("edit_invoice_add_item_button"),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add Item", fontSize = 12.sp)
                                    }
                                }

                                if (itemsList.isEmpty()) {
                                    Text(
                                        text = "No items in this invoice. Tap '+ Add Item' to add products.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DebtRed,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                } else {
                                    itemsList.forEachIndexed { index, item ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = CardDefaults.outlinedCardBorder()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.productName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${Formatters.formatCurrency(item.unitPrice, settings.currencySymbol)} / ${item.unit}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                // Quantity Increment/Decrement Controls
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            val newQty = item.quantity - 1.0
                                                            if (newQty <= 0.0) {
                                                                itemsList = itemsList.filterIndexed { i, _ -> i != index }
                                                            } else {
                                                                itemsList = itemsList.mapIndexed { i, old ->
                                                                    if (i == index) old.copy(quantity = newQty, lineTotal = newQty * old.unitPrice) else old
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                                    ) {
                                                        Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                                                    }

                                                    val qtyFormatted = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString()
                                                    Text(
                                                        text = qtyFormatted,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.widthIn(min = 28.dp),
                                                        textAlign = TextAlign.Center
                                                    )

                                                    IconButton(
                                                        onClick = {
                                                            val newQty = item.quantity + 1.0
                                                            itemsList = itemsList.mapIndexed { i, old ->
                                                                if (i == index) old.copy(quantity = newQty, lineTotal = newQty * old.unitPrice) else old
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .background(BrandGreenPrimary.copy(alpha = 0.15f), CircleShape)
                                                    ) {
                                                        Icon(Icons.Default.Add, contentDescription = "Increase", tint = BrandGreenPrimary, modifier = Modifier.size(16.dp))
                                                    }

                                                    Spacer(modifier = Modifier.width(4.dp))

                                                    Text(
                                                        text = Formatters.formatCurrency(item.lineTotal, settings.currencySymbol),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = FarmGreen,
                                                        modifier = Modifier.widthIn(min = 60.dp),
                                                        textAlign = TextAlign.End
                                                    )

                                                    IconButton(
                                                        onClick = {
                                                            itemsList = itemsList.filterIndexed { i, _ -> i != index }
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = DebtRed, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // SECTION 3: Payment & Balance
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Payment & Balances",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                // Live Totals Card
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (discountDouble > 0) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Gross Subtotal:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    Formatters.formatCurrency(subtotal, settings.currencySymbol),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Sell, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Discount Granted:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = WarningAmber)
                                                }
                                                Text(
                                                    "-${Formatters.formatCurrency(discountDouble, settings.currencySymbol)}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = WarningAmber
                                                )
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (discountDouble > 0) "Net Total Bill:" else "Total Bill:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            Text(
                                                Formatters.formatCurrency(totalAmount, settings.currencySymbol),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Amount Paid:", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                Formatters.formatCurrency(amountPaidDouble, settings.currencySymbol),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = FarmGreen
                                            )
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Balance Due:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            Text(
                                                Formatters.formatCurrency(balanceDue, settings.currencySymbol),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isPaidInFull) FarmGreen else DebtRed
                                            )
                                        }
                                    }
                                }

                                // Discount Input Field
                                OutlinedTextField(
                                    value = discountText,
                                    onValueChange = { discountText = it },
                                    label = { Text("Discount Amount (${settings.currencySymbol})") },
                                    leadingIcon = { Icon(Icons.Default.LocalOffer, contentDescription = null, tint = WarningAmber) },
                                    trailingIcon = {
                                        if (discountText.isNotBlank()) {
                                            IconButton(onClick = { discountText = "" }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear discount", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("edit_discount_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                // Amount Paid Input
                                OutlinedTextField(
                                    value = amountPaidText,
                                    onValueChange = { amountPaidText = it },
                                    label = { Text("Amount Paid (${settings.currencySymbol})") },
                                    leadingIcon = { Icon(Icons.Default.Payments, contentDescription = null) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("edit_amount_paid_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                // Quick Amount Presets
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            amountPaidText = if (totalAmount % 1.0 == 0.0) totalAmount.toInt().toString() else totalAmount.toString()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("Full Payment", fontSize = 11.sp, maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val half = totalAmount / 2.0
                                            amountPaidText = if (half % 1.0 == 0.0) half.toInt().toString() else String.format("%.2f", half)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("50% Deposit", fontSize = 11.sp, maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { amountPaidText = "0" },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("Credit / 0 Paid", fontSize = 11.sp, maxLines = 1)
                                    }
                                }

                                // Payment Method Selection
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Payment Method",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PaymentMethod.values().forEach { method ->
                                            val isSelected = paymentMethod == method
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { paymentMethod = method },
                                                label = { Text(method.displayName, fontSize = 11.sp) },
                                                modifier = Modifier.weight(1f),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = BrandGreenPrimary.copy(alpha = 0.2f),
                                                    selectedLabelColor = BrandGreenPrimary
                                                )
                                            )
                                        }
                                    }
                                }

                                // Notes Field
                                OutlinedTextField(
                                    value = notes,
                                    onValueChange = { notes = it },
                                    label = { Text("Invoice Notes (Optional)") },
                                    leadingIcon = { Icon(Icons.Default.Note, contentDescription = null) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                if (itemsList.isEmpty()) {
                                    return@Button
                                }
                                val updatedJson = Formatters.serializeCartItems(itemsList)
                                val updated = order.copy(
                                    customerId = customerId,
                                    customerName = customerName.ifBlank { "Walk-in Customer" },
                                    customerPhone = customerPhone,
                                    customerWhatsapp = customerWhatsapp,
                                    itemsJson = updatedJson,
                                    totalAmount = totalAmount,
                                    discountAmount = discountDouble,
                                    amountPaid = amountPaidDouble,
                                    balanceDue = balanceDue,
                                    paymentStatus = if (balanceDue <= 0.0) PaymentStatus.PAID.name else if (amountPaidDouble > 0.0) PaymentStatus.PARTIAL.name else PaymentStatus.UNPAID.name,
                                    paymentMethod = paymentMethod.name,
                                    notes = notes
                                )
                                onSaveOrder(updated, order)
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(48.dp)
                                .testTag("save_invoice_changes_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary),
                            shape = RoundedCornerShape(12.dp),
                            enabled = itemsList.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Changes", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet to Add Products from catalog into the invoice
    if (showAddProductSheet) {
        Dialog(
            onDismissRequest = { showAddProductSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.75f)
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Add Product to Invoice",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showAddProductSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(products) { product ->
                            val alreadyInCart = itemsList.any { it.productId == product.id }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!alreadyInCart) {
                                            val newItem = CartItem(
                                                productId = product.id,
                                                productName = product.name,
                                                category = product.category,
                                                unit = product.unit,
                                                unitPrice = product.unitPrice,
                                                quantity = 1.0,
                                                lineTotal = product.unitPrice,
                                                imageUri = product.imageUri
                                            )
                                            itemsList = itemsList + newItem
                                        }
                                        showAddProductSheet = false
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = CardDefaults.outlinedCardBorder()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = product.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${product.category} • ${Formatters.formatCurrency(product.unitPrice, settings.currencySymbol)} / ${product.unit}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (alreadyInCart) {
                                        Text(
                                            text = "Added",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = FarmGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        FilledTonalButton(
                                            onClick = {
                                                val newItem = CartItem(
                                                    productId = product.id,
                                                    productName = product.name,
                                                    category = product.category,
                                                    unit = product.unit,
                                                    unitPrice = product.unitPrice,
                                                    quantity = 1.0,
                                                    lineTotal = product.unitPrice,
                                                    imageUri = product.imageUri
                                                )
                                                itemsList = itemsList + newItem
                                                showAddProductSheet = false
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
