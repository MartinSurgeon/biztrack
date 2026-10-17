package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AppSettingsEntity
import com.example.data.model.CommonUnits
import com.example.data.model.ProductCategory
import com.example.data.model.ProductEntity
import com.example.ui.components.*
import com.example.util.Formatters

private enum class StockFilter(val label: String) { ALL("All"), LOW("Low stock"), OUT("Out of stock") }

@Composable
fun ProductsCatalogScreen(
    products: List<ProductEntity>,
    settings: AppSettingsEntity,
    onSaveProduct: (ProductEntity) -> Unit,
    onDeleteProduct: (ProductEntity) -> Unit,
    onAdjustStock: ((productId: Long, quantityChange: Double, logType: String, notes: String) -> Unit)? = null
) {
    var productToEdit by remember { mutableStateOf<ProductEntity?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var productForStockAdjust by remember { mutableStateOf<ProductEntity?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var stockFilter by remember { mutableStateOf(StockFilter.ALL) }
    var query by remember { mutableStateOf("") }

    val categories = remember(products) { products.map { it.category }.distinct().sortedBy { ProductCategory.labelFor(it) } }
    val inventoryValue = remember(products) { products.sumOf { it.stockQuantity.coerceAtLeast(0.0) * it.unitPrice } }
    val lowCount = products.count { it.stockQuantity > 0 && it.stockQuantity <= it.minStockThreshold }
    val outCount = products.count { it.stockQuantity <= 0 || !it.inStock }

    val filteredProducts = remember(products, selectedCategoryFilter, stockFilter, query) {
        val q = query.trim().lowercase()
        products.filter { p ->
            (selectedCategoryFilter == "ALL" || p.category == selectedCategoryFilter) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.description.lowercase().contains(q)) &&
                when (stockFilter) {
                    StockFilter.ALL -> true
                    StockFilter.LOW -> p.stockQuantity > 0 && p.stockQuantity <= p.minStockThreshold
                    StockFilter.OUT -> p.stockQuantity <= 0 || !p.inStock
                }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenHeader(title = "Products", subtitle = "Catalog, prices and stock")
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricMiniCard("Items", products.size.toString(), Icons.Default.Inventory2, Tone.PRIMARY, Modifier.weight(1f),
                        caption = "${categories.size} ${if (categories.size == 1) "category" else "categories"}")
                    MetricMiniCard("Stock value", Formatters.formatCurrency(inventoryValue, settings.currencySymbol), Icons.Default.Sell, Tone.SUCCESS, Modifier.weight(1f),
                        caption = if (lowCount + outCount > 0) "${lowCount + outCount} need attention" else "all stocked")
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchField(query, { query = it }, "Search products")
                    ChipRow {
                        StockFilter.entries.forEach { f ->
                            PillChip(
                                f.label, stockFilter == f, { stockFilter = f },
                                count = when (f) { StockFilter.ALL -> null; StockFilter.LOW -> lowCount; StockFilter.OUT -> outCount }
                            )
                        }
                    }
                    if (categories.size > 1) {
                        ChipRow {
                            PillChip("All categories", selectedCategoryFilter == "ALL", { selectedCategoryFilter = "ALL" })
                            categories.forEach { key ->
                                PillChip(ProductCategory.labelFor(key), selectedCategoryFilter == key, { selectedCategoryFilter = key }, icon = categoryIcon(key))
                            }
                        }
                    }
                }
            }

            if (filteredProducts.isEmpty()) {
                item {
                    AppCard {
                        if (products.isEmpty()) {
                            EmptyState(
                                icon = Icons.Default.Inventory2,
                                title = "Add your first product",
                                message = "Goods or services — set a price, unit and opening stock.",
                                tone = Tone.PRIMARY,
                                actionText = "Add product",
                                onAction = { isAddingNew = true }
                            )
                        } else {
                            EmptyState(Icons.Default.FilterAltOff, "Nothing here", "No products match these filters.")
                        }
                    }
                }
            } else {
                items(filteredProducts, key = { it.id }) { product ->
                    ProductCatalogGridCard(
                        product = product,
                        currencySymbol = settings.currencySymbol,
                        onAdjustStock = if (onAdjustStock != null) { { productForStockAdjust = product } } else null,
                        onEdit = { productToEdit = product }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { isAddingNew = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add product", style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("add_product_fab")
        )
    }

    if (productToEdit != null || isAddingNew) {
        val target = productToEdit ?: ProductEntity(
            name = "",
            category = ProductCategory.GENERAL.name,
            unit = "Piece",
            unitPrice = 0.0,
            inStock = true,
            stockQuantity = 0.0
        )
        ProductFormDialog(
            initialProduct = target,
            isNew = isAddingNew,
            currencySymbol = settings.currencySymbol,
            onDismiss = { productToEdit = null; isAddingNew = false },
            onSave = { updated ->
                onSaveProduct(updated)
                productToEdit = null
                isAddingNew = false
            },
            onDelete = {
                productToEdit?.let { onDeleteProduct(it) }
                productToEdit = null
                isAddingNew = false
            }
        )
    }

    val adjustTarget = productForStockAdjust
    if (adjustTarget != null && onAdjustStock != null) {
        StockAdjustmentDialog(
            product = adjustTarget,
            onDismiss = { productForStockAdjust = null },
            onConfirmAdjust = { change, notes ->
                onAdjustStock(adjustTarget.id, change, if (change >= 0) "RESTOCK" else "CORRECTION", notes)
                productForStockAdjust = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFormDialog(
    initialProduct: ProductEntity,
    isNew: Boolean,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onSave: (ProductEntity) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initialProduct.name) }
    var category by remember { mutableStateOf(initialProduct.category) }
    var unit by remember { mutableStateOf(initialProduct.unit) }
    var priceText by remember { mutableStateOf(if (isNew) "" else initialProduct.unitPrice.toString()) }
    var costText by remember { mutableStateOf(if (initialProduct.costPrice > 0) initialProduct.costPrice.toString() else "") }
    var stockText by remember { mutableStateOf(if (isNew) "" else formatQty(initialProduct.stockQuantity)) }
    var thresholdText by remember { mutableStateOf(formatQty(initialProduct.minStockThreshold)) }
    var description by remember { mutableStateOf(initialProduct.description) }
    var inStock by remember { mutableStateOf(initialProduct.inStock) }
    var imageUri by remember { mutableStateOf(initialProduct.imageUri) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) imageUri = uri.toString()
    }

    // Offer the modern categories, plus the product's current one if it is a legacy key.
    val categoryOptions = remember(initialProduct.category) {
        val base = ProductCategory.selectable.map { it.name }
        if (initialProduct.category in base) base else listOf(initialProduct.category) + base
    }
    val canSave = name.isNotBlank() && priceText.toDoubleOrNull() != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(if (isNew) "New product" else "Edit product", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)

            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductThumbnail(imageUri, category, name.ifBlank { "Product" }, size = 64.dp, shapeRadius = 18.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Photo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Optional — shown on the sale screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppButton(if (imageUri.isBlank()) "Choose" else "Change", { photoPickerLauncher.launch("image/*") },
                    variant = ButtonVariant.SECONDARY, icon = Icons.Default.PhotoCamera, height = 40.dp)
                if (imageUri.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    CircleIconButton(Icons.Default.Close, "Remove photo", { imageUri = "" }, tone = Tone.DANGER, size = 36.dp)
                }
            }

            AppTextField(name, { name = it }, label = "Name", placeholder = "e.g. Bottled water 500ml, Haircut")

            Column {
                Text("Category", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                ChipRow {
                    categoryOptions.forEach { key ->
                        PillChip(ProductCategory.labelFor(key), category == key, { category = key }, icon = categoryIcon(key))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(priceText, { priceText = it }, label = "Selling price ($currencySymbol)", placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                AppTextField(costText, { costText = it }, label = "Cost price", placeholder = "Optional",
                    keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            }

            Column {
                AppTextField(unit, { unit = it }, label = "Unit", placeholder = "Piece, Kg, Box, Hour…")
                Spacer(Modifier.height(8.dp))
                ChipRow {
                    CommonUnits.forEach { u -> PillChip(u, unit.equals(u, true), { unit = u }) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(stockText, { stockText = it }, label = "Stock on hand", placeholder = "0",
                    keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                AppTextField(thresholdText, { thresholdText = it }, label = "Low-stock alert at", placeholder = "5",
                    keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            }

            AppTextField(description, { description = it }, label = "Description", placeholder = "Optional", singleLine = false, minLines = 2)

            AppCard(color = MaterialTheme.colorScheme.surfaceVariant, borderColor = androidx.compose.ui.graphics.Color.Transparent, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ListRow(
                    title = "Available for sale",
                    subtitle = "Turn off to hide temporarily",
                    trailing = { Switch(checked = inStock, onCheckedChange = { inStock = it }) }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isNew) {
                    AppButton("Delete", { showDeleteConfirm = true }, variant = ButtonVariant.SECONDARY, icon = Icons.Default.DeleteOutline, modifier = Modifier.weight(1f))
                }
                AppButton(
                    text = if (isNew) "Add product" else "Save changes",
                    onClick = {
                        onSave(
                            initialProduct.copy(
                                name = name.trim(),
                                category = category,
                                unit = unit.trim().ifBlank { "Piece" },
                                unitPrice = priceText.toDoubleOrNull() ?: initialProduct.unitPrice,
                                costPrice = costText.toDoubleOrNull() ?: 0.0,
                                stockQuantity = stockText.toDoubleOrNull() ?: initialProduct.stockQuantity,
                                minStockThreshold = thresholdText.toDoubleOrNull() ?: initialProduct.minStockThreshold,
                                description = description.trim(),
                                inStock = inStock,
                                imageUri = imageUri.trim()
                            )
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(if (isNew) 1f else 1.6f)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Delete product?",
            message = "'${initialProduct.name}' will be permanently removed from your catalog. Past sales are not affected.",
            confirmButtonText = "Delete",
            cancelButtonText = "Keep",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockAdjustmentDialog(
    product: ProductEntity,
    onDismiss: () -> Unit,
    onConfirmAdjust: (Double, String) -> Unit
) {
    var quantityText by remember { mutableStateOf("") }
    var isAddition by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }

    val qty = quantityText.toDoubleOrNull() ?: 0.0
    val calculatedChange = if (isAddition) qty else -qty
    val resultingStock = (product.stockQuantity + calculatedChange).coerceAtLeast(0.0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductThumbnail(product.imageUri, product.category, product.name, size = 48.dp, shapeRadius = 14.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Adjust stock", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("${product.name} · ${formatQty(product.stockQuantity)} ${product.unit} on hand",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            SegmentedControl(
                options = listOf(true, false),
                selected = isAddition,
                onSelect = { isAddition = it },
                label = { if (it) "Add stock" else "Remove stock" }
            )

            AppTextField(quantityText, { quantityText = it }, label = "Quantity (${product.unit})", placeholder = "0", keyboardType = KeyboardType.Decimal)
            AppTextField(notes, { notes = it }, label = "Reason", placeholder = if (isAddition) "New delivery, returned item…" else "Damaged, expired, count correction…")

            if (qty > 0) {
                Banner(
                    text = "New stock: ${formatQty(resultingStock)} ${product.unit}",
                    tone = if (isAddition) Tone.SUCCESS else Tone.WARNING,
                    icon = if (isAddition) Icons.Default.TrendingUp else Icons.Default.TrendingDown
                )
            }

            AppButton(
                text = if (isAddition) "Add ${formatQty(qty)}" else "Remove ${formatQty(qty)}",
                onClick = { onConfirmAdjust(calculatedChange, notes) },
                enabled = qty > 0,
                variant = if (isAddition) ButtonVariant.PRIMARY else ButtonVariant.DANGER,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ProductCatalogGridCard(
    product: ProductEntity,
    currencySymbol: String,
    onAdjustStock: (() -> Unit)?,
    onEdit: () -> Unit
) {
    val isOutOfStock = product.stockQuantity <= 0 || !product.inStock
    val isLow = !isOutOfStock && product.stockQuantity <= product.minStockThreshold
    val c = MaterialTheme.colorScheme

    AppCard(onClick = onEdit, contentPadding = PaddingValues(14.dp), modifier = Modifier.testTag("product_catalog_card_${product.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductThumbnail(product.imageUri, product.category, product.name, size = 56.dp, shapeRadius = 16.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ProductCategory.labelFor(product.category), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                StatusBadge(
                    text = when {
                        !product.inStock -> "Unavailable"
                        isOutOfStock -> "Out of stock"
                        else -> "${formatQty(product.stockQuantity)} ${product.unit}"
                    },
                    tone = when {
                        isOutOfStock -> Tone.DANGER
                        isLow -> Tone.WARNING
                        else -> Tone.SUCCESS
                    }
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatters.formatCurrency(product.unitPrice, currencySymbol), style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("per ${product.unit}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAdjustStock != null) {
                        CircleIconButton(Icons.Default.Inventory, "Adjust stock", onAdjustStock, tone = Tone.PRIMARY, size = 36.dp,
                            modifier = Modifier.testTag("adjust_stock_button_${product.id}"))
                    }
                    CircleIconButton(Icons.Default.Edit, "Edit product", onEdit, size = 36.dp,
                        modifier = Modifier.testTag("edit_product_button_${product.id}"))
                }
            }
        }
    }
}

