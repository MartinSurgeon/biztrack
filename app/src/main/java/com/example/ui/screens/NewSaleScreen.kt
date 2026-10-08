package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.ProductThumbnail
import com.example.ui.theme.*
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
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var showCustomerPicker by remember { mutableStateOf(false) }
    var showClearCartConfirm by remember { mutableStateOf(false) }
    var showCompleteSaleConfirm by remember { mutableStateOf(false) }
    var isCustomerFormExpanded by remember { mutableStateOf(false) }

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
    val totalItemsInCart = cart.values.sumOf { it.quantity }.toInt()
    val isReadyToCheckout = cart.isNotEmpty() && customerName.isNotBlank()

    val categories = listOf(
        "ALL" to "All Products",
        ProductCategory.EGGS.name to "Eggs 🥚",
        ProductCategory.BROILER_LIVE.name to "Live Broiler 🐔",
        ProductCategory.BROILER_DRESSED.name to "Dressed Broiler 🍗",
        ProductCategory.SASSO_LIVE.name to "Live Sasso 🐓",
        ProductCategory.SASSO_DRESSED.name to "Dressed Sasso 🍗"
    )

    val filteredProducts = if (selectedCategoryFilter == "ALL") {
        products
    } else {
        products.filter { it.category == selectedCategoryFilter }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = if (cart.isNotEmpty()) 120.dp else 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Screen Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "New Sale / Order",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Select eggs or chicken & record customer details",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (cart.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearCartConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = DebtRed)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }
            }

            // 2. High-Priority Top Customer Quick-Card (Zero Scroll Needed)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (customerName.isNotBlank()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (customerName.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        // Section Top Bar: Title + Status + Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (customerName.isNotBlank()) FarmGreen else MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (customerName.isNotBlank()) Icons.Default.Check else Icons.Default.Person,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (customerName.isNotBlank()) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = "CUSTOMER",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (customerName.isNotBlank()) {
                                        Text(
                                            text = customerName + if (customerPhone.isNotBlank()) " • $customerPhone" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text(
                                            text = "Walk-in, regular, or new customer",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (customers.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = {
                                            showCustomerPicker = !showCustomerPicker
                                            if (showCustomerPicker) isCustomerFormExpanded = true
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = PastelMauve,
                                            contentColor = Color(0xFF2A153A)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("select_existing_customer_button")
                                    ) {
                                        Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Select Saved", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                                IconButton(
                                    onClick = { isCustomerFormExpanded = !isCustomerFormExpanded },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCustomerFormExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (isCustomerFormExpanded) "Collapse details" else "Expand details",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Quick Select chips for saved customers or Walk-in Presets
                        if (!isCustomerFormExpanded && customers.isNotEmpty() && customerName.isBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(customers.take(5)) { cust ->
                                    FilterChip(
                                        selected = selectedCustomer?.id == cust.id,
                                        onClick = {
                                            viewModel.selectCustomer(cust)
                                        },
                                        label = {
                                            Text(
                                                text = cust.name,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    )
                                }
                            }
                        }

                        // Existing Customer Picker Dropdown/List
                        if (showCustomerPicker && customers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Choose from registered customers:",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        IconButton(
                                            onClick = { showCustomerPicker = false },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Close picker", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    customers.forEach { cust ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.selectCustomer(cust)
                                                    showCustomerPicker = false
                                                }
                                                .padding(vertical = 8.dp, horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(text = cust.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                                Text(text = cust.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (cust.currentBalance > 0) {
                                                Text(
                                                    text = "Owes ${Formatters.formatCurrency(cust.currentBalance, settings.currencySymbol)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = DebtRed,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }

                        // Quick Direct Inputs with clean, theme-harmonious borders
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customerName,
                                onValueChange = {
                                    viewModel.customCustomerName.value = it
                                    if (selectedCustomer != null && selectedCustomer!!.name != it) {
                                        viewModel.selectedCustomer.value = null
                                    }
                                },
                                label = { Text("Name *") },
                                placeholder = { Text("e.g. Kwame / Walk-in") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .testTag("customer_name_input"),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )

                            OutlinedTextField(
                                value = customerPhone,
                                onValueChange = { viewModel.customCustomerPhone.value = it },
                                label = { Text("Phone (SMS) *") },
                                placeholder = { Text("0244123456") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier
                                    .weight(0.9f)
                                    .testTag("customer_phone_input"),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }

                        // Expanded Optional Fields (WhatsApp & Balance Preview)
                        if (isCustomerFormExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customerWhatsapp,
                                onValueChange = { viewModel.customCustomerWhatsapp.value = it },
                                label = { Text("WhatsApp Number (for Receipt)") },
                                placeholder = { Text("Leave empty to use phone number") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = WhatsAppGreen, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )

                            if (selectedCustomer != null && selectedCustomer!!.currentBalance > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = DebtRedContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = DebtRed, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Existing debt: ${Formatters.formatCurrency(selectedCustomer!!.currentBalance, settings.currencySymbol)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = DebtRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Category Filter Chips
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categories) { (key, label) ->
                        FilterChip(
                            selected = selectedCategoryFilter == key,
                            onClick = { selectedCategoryFilter = key },
                            label = { Text(label, fontWeight = if (selectedCategoryFilter == key) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandGreenPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            // 4. Product Cards Grid Header & Counter
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SELECT PRODUCTS (${filteredProducts.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (cart.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = BrandGreenPrimary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "$totalItemsInCart item(s) • ${Formatters.formatCurrency(cartTotal, settings.currencySymbol)}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = BrandGreenPrimary
                            )
                        }
                    }
                }
            }

            // 5. Product Cards Grid
            items(filteredProducts.chunked(2)) { rowProducts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowProducts.forEach { product ->
                        val cartItem = cart[product.id]
                        val currentQty = cartItem?.quantity ?: 0.0

                        Box(modifier = Modifier.weight(1f)) {
                            ProductGridCard(
                                product = product,
                                currencySymbol = settings.currencySymbol,
                                currentQty = currentQty,
                                onAdd = { viewModel.updateCartItemQuantity(product, 1.0) },
                                onDecrease = { viewModel.updateCartItemQuantity(product, -1.0) }
                            )
                        }
                    }
                    if (rowProducts.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 6. Payment & Notes Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PAYMENT & BALANCE DUE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Bill Subtotal & Net Total
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (discountDouble > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "Gross Subtotal:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = Formatters.formatCurrency(subtotal, settings.currencySymbol),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Sell, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = "Discount Granted:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = WarningAmber)
                                    }
                                    Text(
                                        text = "-${Formatters.formatCurrency(discountDouble, settings.currencySymbol)}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = WarningAmber
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (discountDouble > 0) "Net Payable Total:" else "Total Bill Amount:",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = Formatters.formatCurrency(discountedTotal, settings.currencySymbol),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BrandGreenPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Discount Input Field with Quick Clear / Helper
                        OutlinedTextField(
                            value = discountInput,
                            onValueChange = { viewModel.discountInput.value = it },
                            label = { Text("Discount Amount (${settings.currencySymbol})") },
                            placeholder = { Text("e.g. 10 or 25.00") },
                            leadingIcon = { Icon(Icons.Default.LocalOffer, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (discountInput.isNotBlank()) {
                                    IconButton(onClick = { viewModel.discountInput.value = "" }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear discount", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("discount_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Quick Payment Presets (Full / Half / Credit)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = amountPaidInput == discountedTotal.toString() || (amountPaidInput.isBlank() && discountedTotal > 0),
                                onClick = { viewModel.amountPaidInput.value = discountedTotal.toString() },
                                label = { Text("Paid Full", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = amountPaidInput == (discountedTotal / 2.0).toString(),
                                onClick = { viewModel.amountPaidInput.value = (discountedTotal / 2.0).toString() },
                                label = { Text("50% Deposit", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = amountPaidInput == "0",
                                onClick = { viewModel.amountPaidInput.value = "0" },
                                label = { Text("Credit (Owing)", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Custom Amount Paid input
                        OutlinedTextField(
                            value = amountPaidInput,
                            onValueChange = { viewModel.amountPaidInput.value = it },
                            label = { Text("Amount Paid Upfront / Tendered (${settings.currencySymbol})") },
                            placeholder = { Text(discountedTotal.toString()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("amount_paid_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Overpayment / Cash Change Calculator Banner
                        if (isOverpaid) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BrandAmberGoldContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.AttachMoney,
                                                contentDescription = null,
                                                tint = BrandAmberGold,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "CHANGE DUE TO CUSTOMER:",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = BrandAmberGold
                                            )
                                        }
                                        Text(
                                            text = Formatters.formatCurrency(changeDue, settings.currencySymbol),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BrandAmberGold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Tendered: ${Formatters.formatCurrency(amountPaidDouble, settings.currencySymbol)} (Bill: ${Formatters.formatCurrency(cartTotal, settings.currencySymbol)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(
                                            onClick = { viewModel.amountPaidInput.value = cartTotal.toString() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Set Exact (${Formatters.formatCurrency(cartTotal, settings.currencySymbol)})", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Balance Owed Alert / Indicator
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                calculatedBalanceDue > 0 -> DebtRedContainer
                                isOverpaid -> BrandAmberGoldContainer
                                else -> FarmGreenContainer
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when {
                                            calculatedBalanceDue > 0 -> "Today's Unpaid Amount (Debt):"
                                            isOverpaid -> "Payment Status (Overpayment):"
                                            else -> "Payment Status:"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            calculatedBalanceDue > 0 -> DebtRed
                                            isOverpaid -> BrandAmberGold
                                            else -> FarmGreen
                                        }
                                    )
                                    if (calculatedBalanceDue > 0) {
                                        val totalOwedOverall = (selectedCustomer?.currentBalance ?: 0.0) + calculatedBalanceDue
                                        Text(
                                            text = if (selectedCustomer != null && selectedCustomer!!.currentBalance > 0) {
                                                "Total debt will become ${Formatters.formatCurrency(totalOwedOverall, settings.currencySymbol)}"
                                            } else {
                                                "Will be tracked in Debtors list for SMS reminders"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else if (isOverpaid) {
                                        Text(
                                            text = "Return ${Formatters.formatCurrency(changeDue, settings.currencySymbol)} cash change to customer",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Text(
                                    text = when {
                                        calculatedBalanceDue > 0 -> Formatters.formatCurrency(calculatedBalanceDue, settings.currencySymbol)
                                        isOverpaid -> "PAID (CHANGE ${Formatters.formatCurrency(changeDue, settings.currencySymbol)})"
                                        else -> "PAID IN FULL"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = when {
                                        calculatedBalanceDue > 0 -> DebtRed
                                        isOverpaid -> BrandAmberGold
                                        else -> FarmGreen
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Payment Method chips
                        Text(text = "Payment Method:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PaymentMethod.values().forEach { method ->
                                FilterChip(
                                    selected = paymentMethod == method,
                                    onClick = { viewModel.paymentMethod.value = method },
                                    label = { Text(method.displayName.substringBefore(" ("), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = notes,
                            onValueChange = { viewModel.saleNotes.value = it },
                            label = { Text("Order Notes (Optional)") },
                            placeholder = { Text("e.g. Broiler cut into 8 pieces, Delivery note") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }
        }

        // 7. Persistent Bottom Sticky Action Bar (HCI / Fitts's Law: Instant Access Anywhere)
        if (cart.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (customerName.isNotBlank()) "Customer: $customerName" else "⚠️ Customer details required",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (customerName.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else DebtRed
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = Formatters.formatCurrency(discountedTotal, settings.currencySymbol),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BrandGreenPrimary
                                )
                                if (discountDouble > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "(-${Formatters.formatCurrency(discountDouble, settings.currencySymbol)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = WarningAmber
                                    )
                                }
                                Text(
                                    text = " • $totalItemsInCart items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = { showCompleteSaleConfirm = true },
                            enabled = isReadyToCheckout,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("complete_sale_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Complete Sale",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialogs
    // 1. Clear Cart Confirmation
    if (showClearCartConfirm) {
        ConfirmationDialog(
            title = "Clear Current Cart?",
            message = "Are you sure you want to discard all ${cart.values.sumOf { it.quantity }.toInt()} item(s) from this order?",
            confirmButtonText = "Clear Cart",
            cancelButtonText = "Keep Items",
            isDestructive = true,
            onConfirm = {
                viewModel.clearCart()
                showClearCartConfirm = false
            },
            onDismiss = { showClearCartConfirm = false }
        )
    }

    // 2. Complete Sale Confirmation
    if (showCompleteSaleConfirm) {
        val totalOwedOverall = (selectedCustomer?.currentBalance ?: 0.0) + calculatedBalanceDue
        val debtDetails = if (calculatedBalanceDue > 0) {
            "\n\n⚠️ Balance Due (Credit): ${Formatters.formatCurrency(calculatedBalanceDue, settings.currencySymbol)}\nCustomer's total outstanding balance will be: ${Formatters.formatCurrency(totalOwedOverall, settings.currencySymbol)}"
        } else if (isOverpaid) {
            "\n\n✓ Cash Tendered: ${Formatters.formatCurrency(amountPaidDouble, settings.currencySymbol)}\n• Cash Change Due: ${Formatters.formatCurrency(changeDue, settings.currencySymbol)}"
        } else {
            "\n\n✓ Payment: Paid in Full (${Formatters.formatCurrency(discountedTotal, settings.currencySymbol)})"
        }

        val discountMsg = if (discountDouble > 0) " (Subtotal: ${Formatters.formatCurrency(subtotal, settings.currencySymbol)}, Discount: -${Formatters.formatCurrency(discountDouble, settings.currencySymbol)})" else ""

        ConfirmationDialog(
            title = if (calculatedBalanceDue > 0) "Confirm Credit / Part Sale?" else "Confirm Sale & Invoice?",
            message = "Record sale of ${cart.values.sumOf { it.quantity }.toInt()} item(s) totaling ${Formatters.formatCurrency(discountedTotal, settings.currencySymbol)}$discountMsg for '$customerName'?$debtDetails",
            confirmButtonText = "Record Sale",
            cancelButtonText = "Review Order",
            isDestructive = false,
            icon = if (calculatedBalanceDue > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
            onConfirm = {
                showCompleteSaleConfirm = false
                viewModel.completeSale { savedOrder ->
                    onSaleCompleted(savedOrder)
                }
            },
            onDismiss = { showCompleteSaleConfirm = false }
        )
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product_item_${product.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else if (isOutOfStock) DebtRed.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Row: Stock Badge & In-Cart Counter Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when {
                        isOutOfStock -> DebtRedContainer
                        product.stockQuantity <= product.minStockThreshold -> BrandAmberGoldContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = BorderStroke(
                        0.5.dp,
                        when {
                            isOutOfStock -> DebtRed.copy(alpha = 0.3f)
                            product.stockQuantity <= product.minStockThreshold -> BrandAmberGold.copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
                ) {
                    Text(
                        text = when {
                            isOutOfStock -> "Out"
                            else -> "${product.stockQuantity.toInt()} left"
                        },
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isOutOfStock -> DebtRed
                            product.stockQuantity <= product.minStockThreshold -> BrandAmberGold
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (isSelected) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "✓ ${if (currentQty % 1.0 == 0.0) currentQty.toInt() else currentQty}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Center Product Image Thumbnail (72dp)
            ProductThumbnail(
                imageUri = product.imageUri,
                category = product.category,
                name = product.name,
                size = 72.dp,
                shapeRadius = 10.dp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Product Name (2 lines fixed height for clean grid alignment)
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                minLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Price & Unit
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = Formatters.formatCurrency(product.unitPrice, currencySymbol),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "/ ${product.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Ergonomic Stepper or Add Button
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDecrease,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "Decrease quantity",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        val qtyDisplay = if (currentQty % 1.0 == 0.0) currentQty.toInt().toString() else currentQty.toString()
                        Text(
                            text = qtyDisplay,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        IconButton(
                            onClick = onAdd,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Increase quantity",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

