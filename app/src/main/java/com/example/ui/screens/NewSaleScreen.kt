package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.FarmViewModel
import com.example.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSaleScreen(
    viewModel: FarmViewModel,
    products: List<ProductEntity>,
    customers: List<CustomerEntity>,
    settings: AppSettingsEntity,
    cart: Map<Long, CartItem>,
    cartTotal: Double,
    onSaleCompleted: (SaleOrderEntity) -> Unit
) {
    val currency = settings.currencySymbol
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var productQuery by remember { mutableStateOf("") }
    var showCustomerPicker by remember { mutableStateOf(false) }
    var showClearCartConfirm by remember { mutableStateOf(false) }
    var showCompleteSaleConfirm by remember { mutableStateOf(false) }
    var showWhatsappField by remember { mutableStateOf(false) }

    val customerName by viewModel.customCustomerName.collectAsState()
    val customerPhone by viewModel.customCustomerPhone.collectAsState()
    val customerWhatsapp by viewModel.customCustomerWhatsapp.collectAsState()
    val discountInput by viewModel.discountInput.collectAsState()
    val amountPaidInput by viewModel.amountPaidInput.collectAsState()
    val paymentMethod by viewModel.paymentMethod.collectAsState()
    val notes by viewModel.saleNotes.collectAsState()
    val selectedCustomer by viewModel.selectedCustomer.collectAsState()

    val subtotal = cartTotal
    val discountDouble = (discountInput.toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
    val discountedTotal = (subtotal - discountDouble).coerceAtLeast(0.0)
    val amountPaidDouble = amountPaidInput.toDoubleOrNull() ?: discountedTotal
    val calculatedBalanceDue = (discountedTotal - amountPaidDouble).coerceAtLeast(0.0)
    val changeDue = if (amountPaidInput.isNotBlank() && (amountPaidInput.toDoubleOrNull() ?: 0.0) > discountedTotal) {
        ((amountPaidInput.toDoubleOrNull() ?: 0.0) - discountedTotal).coerceAtLeast(0.0)
    } else 0.0
    val isOverpaid = changeDue > 0.0
    val totalItemsInCart = cart.values.sumOf { it.quantity }
    val isReadyToCheckout = cart.isNotEmpty() && customerName.isNotBlank()

    // Categories come from the catalog itself, so any kind of business gets relevant filters.
    val categories = remember(products) { products.map { it.category }.distinct().sortedBy { ProductCategory.labelFor(it) } }
    val filteredProducts = remember(products, selectedCategoryFilter, productQuery) {
        val q = productQuery.trim().lowercase()
        products.filter { p ->
            (selectedCategoryFilter == "ALL" || p.category == selectedCategoryFilter) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.description.lowercase().contains(q))
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (cart.isNotEmpty()) 120.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ScreenHeader(title = "New sale", subtitle = "Pick items, add the customer, take payment") {
                    if (cart.isNotEmpty()) {
                        CircleIconButton(Icons.Default.DeleteSweep, "Clear cart", { showClearCartConfirm = true }, tone = Tone.DANGER)
                    }
                }
            }

            // Customer
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (customerName.isNotBlank()) Avatar(customerName, size = 40.dp)
                        else IconBadge(Icons.Default.Person, tone = Tone.NEUTRAL, shape = CircleShape)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Customer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = if (customerName.isNotBlank()) listOf(customerName, customerPhone).filter { it.isNotBlank() }.joinToString(" · ")
                                else "Walk-in, regular or new customer",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (customers.isNotEmpty()) {
                            AppButton(
                                text = "Saved",
                                icon = Icons.Default.PersonSearch,
                                onClick = { showCustomerPicker = true },
                                variant = ButtonVariant.SECONDARY,
                                height = 38.dp,
                                modifier = Modifier.testTag("select_existing_customer_button")
                            )
                        }
                    }

                    if (customers.isNotEmpty() && customerName.isBlank()) {
                        Spacer(Modifier.height(12.dp))
                        ChipRow {
                            customers.take(6).forEach { cust ->
                                PillChip(cust.name, selected = false, onClick = { viewModel.selectCustomer(cust) }, icon = Icons.Default.Person)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppTextField(
                            value = customerName,
                            onValueChange = {
                                viewModel.customCustomerName.value = it
                                if (selectedCustomer != null && selectedCustomer!!.name != it) viewModel.selectedCustomer.value = null
                            },
                            label = "Name",
                            placeholder = "e.g. Ama / Walk-in",
                            modifier = Modifier.weight(1f),
                            fieldModifier = Modifier.testTag("customer_name_input")
                        )
                        AppTextField(
                            value = customerPhone,
                            onValueChange = { viewModel.customCustomerPhone.value = it },
                            label = "Phone",
                            placeholder = "0244 123 456",
                            keyboardType = KeyboardType.Phone,
                            modifier = Modifier.weight(1f),
                            fieldModifier = Modifier.testTag("customer_phone_input")
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (showWhatsappField) "Hide WhatsApp number" else "+ Different WhatsApp number",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showWhatsappField = !showWhatsappField }.padding(4.dp)
                    )
                    AnimatedVisibility(showWhatsappField) {
                        AppTextField(
                            value = customerWhatsapp,
                            onValueChange = { viewModel.customCustomerWhatsapp.value = it },
                            placeholder = "Leave empty to use the phone number",
                            leadingIcon = Icons.Default.Chat,
                            keyboardType = KeyboardType.Phone,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    val existingDebt = selectedCustomer?.currentBalance ?: 0.0
                    if (existingDebt > 0) {
                        Spacer(Modifier.height(10.dp))
                        Banner(
                            text = "Already owes ${Formatters.formatCurrency(existingDebt, currency)}",
                            tone = Tone.DANGER,
                            icon = Icons.Default.Warning
                        )
                    }
                }
            }

            // Products
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = "Products",
                        trailing = if (cart.isNotEmpty()) "${formatQty(totalItemsInCart)} in cart" else "${filteredProducts.size} items"
                    )
                    if (products.size > 6) {
                        SearchField(productQuery, { productQuery = it }, "Search products")
                    }
                    if (categories.size > 1) {
                        ChipRow {
                            PillChip("All", selectedCategoryFilter == "ALL", { selectedCategoryFilter = "ALL" }, count = products.size)
                            categories.forEach { key ->
                                PillChip(
                                    ProductCategory.labelFor(key),
                                    selectedCategoryFilter == key,
                                    { selectedCategoryFilter = key },
                                    icon = categoryIcon(key)
                                )
                            }
                        }
                    }
                }
            }

            if (products.isEmpty()) {
                item {
                    AppCard {
                        EmptyState(
                            icon = Icons.Default.Inventory2,
                            title = "Your catalog is empty",
                            message = "Add products or services in the Products tab, then come back to sell them.",
                            tone = Tone.PRIMARY
                        )
                    }
                }
            } else if (filteredProducts.isEmpty()) {
                item {
                    Text("No products match your search.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = TextAlign.Center)
                }
            }

            items(filteredProducts.chunked(2)) { rowProducts ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowProducts.forEach { product ->
                        Box(Modifier.weight(1f)) {
                            ProductGridCard(
                                product = product,
                                currencySymbol = currency,
                                currentQty = cart[product.id]?.quantity ?: 0.0,
                                onAdd = { viewModel.updateCartItemQuantity(product, 1.0) },
                                onDecrease = { viewModel.updateCartItemQuantity(product, -1.0) }
                            )
                        }
                    }
                    if (rowProducts.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // Payment
            if (cart.isNotEmpty()) {
                item {
                    AppCard {
                        Text("Payment", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))

                        if (discountDouble > 0) {
                            SummaryLine("Subtotal", Formatters.formatCurrency(subtotal, currency))
                            Spacer(Modifier.height(6.dp))
                            SummaryLine("Discount", "−${Formatters.formatCurrency(discountDouble, currency)}", valueColor = AppTheme.status.warning)
                            Spacer(Modifier.height(8.dp))
                        }
                        SummaryLine("Total", Formatters.formatCurrency(discountedTotal, currency), emphasize = true)

                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppTextField(
                                value = discountInput,
                                onValueChange = { viewModel.discountInput.value = it },
                                label = "Discount ($currency)",
                                placeholder = "0.00",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f),
                                fieldModifier = Modifier.testTag("discount_input")
                            )
                            AppTextField(
                                value = amountPaidInput,
                                onValueChange = { viewModel.amountPaidInput.value = it },
                                label = "Amount paid ($currency)",
                                placeholder = Formatters.formatCurrency(discountedTotal, ""),
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f),
                                fieldModifier = Modifier.testTag("amount_paid_input")
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        ChipRow {
                            PillChip("Paid in full", amountPaidInput == discountedTotal.toString() || (amountPaidInput.isBlank() && discountedTotal > 0),
                                { viewModel.amountPaidInput.value = discountedTotal.toString() })
                            PillChip("50% deposit", amountPaidInput == (discountedTotal / 2.0).toString(),
                                { viewModel.amountPaidInput.value = (discountedTotal / 2.0).toString() })
                            PillChip("On credit", amountPaidInput == "0", { viewModel.amountPaidInput.value = "0" })
                        }

                        Spacer(Modifier.height(12.dp))
                        when {
                            calculatedBalanceDue > 0 -> {
                                val totalOwed = (selectedCustomer?.currentBalance ?: 0.0) + calculatedBalanceDue
                                Banner(
                                    text = "Balance due ${Formatters.formatCurrency(calculatedBalanceDue, currency)}" +
                                        if ((selectedCustomer?.currentBalance ?: 0.0) > 0) " · total owed becomes ${Formatters.formatCurrency(totalOwed, currency)}"
                                        else " · tracked under Customers",
                                    tone = Tone.DANGER,
                                    icon = Icons.Default.Schedule
                                )
                            }
                            isOverpaid -> Banner(
                                text = "Give change ${Formatters.formatCurrency(changeDue, currency)}",
                                tone = Tone.WARNING,
                                icon = Icons.Default.Payments,
                                trailing = {
                                    Text("Set exact", style = MaterialTheme.typography.labelLarge, color = toneColors(Tone.WARNING).onContainer,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.amountPaidInput.value = discountedTotal.toString() }.padding(6.dp))
                                }
                            )
                            else -> Banner("Paid in full", tone = Tone.SUCCESS, icon = Icons.Default.CheckCircle)
                        }

                        Spacer(Modifier.height(16.dp))
                        Caption("Payment method")
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(
                            options = PaymentMethod.entries,
                            selected = paymentMethod,
                            onSelect = { viewModel.paymentMethod.value = it },
                            label = { paymentMethodShortLabel(it) }
                        )

                        Spacer(Modifier.height(14.dp))
                        AppTextField(
                            value = notes,
                            onValueChange = { viewModel.saleNotes.value = it },
                            label = "Notes",
                            placeholder = "Delivery details, special instructions…",
                            leadingIcon = Icons.Default.Notes
                        )
                    }
                }
            }
        }

        // Sticky checkout bar
        if (cart.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (customerName.isNotBlank()) "${formatQty(totalItemsInCart)} items · $customerName" else "Add a customer name to continue",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customerName.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.status.danger,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(Formatters.formatCurrency(discountedTotal, currency), style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                    Spacer(Modifier.width(12.dp))
                    AppButton(
                        text = "Charge",
                        icon = Icons.Default.CheckCircle,
                        onClick = { showCompleteSaleConfirm = true },
                        enabled = isReadyToCheckout,
                        modifier = Modifier.testTag("complete_sale_button")
                    )
                }
            }
        }
    }

    if (showCustomerPicker) {
        CustomerPickerSheet(
            customers = customers,
            currency = currency,
            onDismiss = { showCustomerPicker = false },
            onPick = {
                viewModel.selectCustomer(it)
                showCustomerPicker = false
            }
        )
    }

    if (showClearCartConfirm) {
        ConfirmationDialog(
            title = "Clear cart?",
            message = "Remove all ${formatQty(totalItemsInCart)} item(s) from this sale?",
            confirmButtonText = "Clear",
            cancelButtonText = "Keep",
            isDestructive = true,
            onConfirm = {
                viewModel.clearCart()
                showClearCartConfirm = false
            },
            onDismiss = { showClearCartConfirm = false }
        )
    }

    if (showCompleteSaleConfirm) {
        val totalOwedOverall = (selectedCustomer?.currentBalance ?: 0.0) + calculatedBalanceDue
        val paymentLine = when {
            calculatedBalanceDue > 0 -> "Balance due: ${Formatters.formatCurrency(calculatedBalanceDue, currency)}\nTotal owed after sale: ${Formatters.formatCurrency(totalOwedOverall, currency)}"
            isOverpaid -> "Tendered: ${Formatters.formatCurrency(amountPaidDouble, currency)}\nChange due: ${Formatters.formatCurrency(changeDue, currency)}"
            else -> "Paid in full"
        }
        val discountLine = if (discountDouble > 0) "\nDiscount: ${Formatters.formatCurrency(discountDouble, currency)}" else ""

        ConfirmationDialog(
            title = if (calculatedBalanceDue > 0) "Record credit sale?" else "Record sale?",
            message = "$customerName · ${formatQty(totalItemsInCart)} item(s)\nTotal: ${Formatters.formatCurrency(discountedTotal, currency)}$discountLine\n\n$paymentLine",
            confirmButtonText = "Record sale",
            cancelButtonText = "Review",
            isDestructive = false,
            icon = if (calculatedBalanceDue > 0) Icons.Default.Schedule else Icons.Default.CheckCircle,
            onConfirm = {
                showCompleteSaleConfirm = false
                viewModel.completeSale { savedOrder -> onSaleCompleted(savedOrder) }
            },
            onDismiss = { showCompleteSaleConfirm = false }
        )
    }
}

fun paymentMethodShortLabel(method: PaymentMethod): String = when (method) {
    PaymentMethod.CASH -> "Cash"
    PaymentMethod.MOMO -> "Mobile money"
    PaymentMethod.BANK_TRANSFER -> "Bank"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerPickerSheet(
    customers: List<CustomerEntity>,
    currency: String,
    onDismiss: () -> Unit,
    onPick: (CustomerEntity) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(customers, query) {
        customers.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Choose customer", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            SearchField(query, { query = it }, "Search name or phone")
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            items(filtered, key = { it.id }) { cust ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(cust) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(cust.name, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cust.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(cust.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (cust.currentBalance > 0) {
                        StatusBadge("Owes ${Formatters.formatCurrency(cust.currentBalance, currency)}", Tone.DANGER)
                    }
                }
                HairlineDivider()
            }
        }
    }
}

@Composable
fun ProductGridCard(
    product: ProductEntity,
    currencySymbol: String,
    currentQty: Double,
    onAdd: () -> Unit,
    onDecrease: () -> Unit
) {
    val isSelected = currentQty > 0
    val isOutOfStock = product.stockQuantity <= 0
    val isLow = !isOutOfStock && product.stockQuantity <= product.minStockThreshold
    val c = MaterialTheme.colorScheme

    AppCard(
        onClick = if (isSelected) null else onAdd,
        borderColor = if (isSelected) c.primary else null,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("product_item_${product.id}")
    ) {
        Box(Modifier.fillMaxWidth()) {
            ProductThumbnail(
                imageUri = product.imageUri,
                category = product.category,
                name = product.name,
                size = 64.dp,
                shapeRadius = 16.dp,
                fallbackUrl = product.remoteImageOrBlank
            )
            StatusBadge(
                text = when {
                    isOutOfStock -> "Out"
                    else -> "${formatQty(product.stockQuantity)} left"
                },
                tone = when {
                    isOutOfStock -> Tone.DANGER
                    isLow -> Tone.WARNING
                    else -> Tone.NEUTRAL
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(product.name, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Formatters.formatCurrency(product.unitPrice, currencySymbol), style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text(" / ${product.unit}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        if (isSelected) {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp)).background(c.primaryContainer),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StepperButton(Icons.Default.Remove, "Decrease quantity", onDecrease, solid = false)
                Text(formatQty(currentQty), style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer)
                StepperButton(Icons.Default.Add, "Increase quantity", onAdd, solid = true)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = c.onSurface, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add", style = MaterialTheme.typography.labelLarge, color = c.onSurface)
            }
        }
    }
}

@Composable
private fun StepperButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, solid: Boolean) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (solid) c.primary else c.surface)
            .then(if (!solid) Modifier.border(1.dp, c.outlineVariant, RoundedCornerShape(10.dp)) else Modifier)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (solid) c.onPrimary else c.onSurface, modifier = Modifier.size(16.dp))
    }
}
