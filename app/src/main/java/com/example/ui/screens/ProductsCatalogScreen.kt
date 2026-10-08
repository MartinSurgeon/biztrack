package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppSettingsEntity
import com.example.data.model.ProductCategory
import com.example.data.model.ProductEntity
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.ProductThumbnail
import com.example.ui.theme.*
import com.example.util.Formatters

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

    val categories = listOf(
        "ALL" to "All (${products.size})",
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isAddingNew = true },
                containerColor = BrandGreenPrimary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 60.dp)
                    .testTag("add_product_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Product")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            item {
                Column {
                    Text(
                        text = "Poultry Products & Pricing",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Eggs, Live & Dressed Broilers and Sasso Chicken catalog & stock inventory",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Category Filter Chips
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

            // Products Grid in 2 Columns
            items(filteredProducts.chunked(2)) { rowProducts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowProducts.forEach { product ->
                        Box(modifier = Modifier.weight(1f)) {
                            ProductCatalogGridCard(
                                product = product,
                                currencySymbol = settings.currencySymbol,
                                onAdjustStock = if (onAdjustStock != null) { { productForStockAdjust = product } } else null,
                                onEdit = { productToEdit = product }
                            )
                        }
                    }
                    if (rowProducts.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // Edit / Add Product Dialog
    if (productToEdit != null || isAddingNew) {
        val target = productToEdit ?: ProductEntity(
            name = "",
            category = ProductCategory.EGGS.name,
            unit = "Crate",
            unitPrice = 50.0,
            inStock = true,
            stockQuantity = 50.0
        )

        ProductFormDialog(
            initialProduct = target,
            isNew = isAddingNew,
            currencySymbol = settings.currencySymbol,
            onDismiss = {
                productToEdit = null
                isAddingNew = false
            },
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

    // Quick Stock Adjustment Dialog with Confirmation
    if (productForStockAdjust != null && onAdjustStock != null) {
        StockAdjustmentDialog(
            product = productForStockAdjust!!,
            onDismiss = { productForStockAdjust = null },
            onConfirmAdjust = { change, notes ->
                onAdjustStock(productForStockAdjust!!.id, change, if (change >= 0) "RESTOCK" else "CORRECTION", notes)
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
    var priceText by remember { mutableStateOf(initialProduct.unitPrice.toString()) }
    var stockText by remember { mutableStateOf(if (initialProduct.stockQuantity % 1.0 == 0.0) initialProduct.stockQuantity.toInt().toString() else initialProduct.stockQuantity.toString()) }
    var description by remember { mutableStateOf(initialProduct.description) }
    var inStock by remember { mutableStateOf(initialProduct.inStock) }
    var imageUri by remember { mutableStateOf(initialProduct.imageUri) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            imageUri = uri.toString()
        }
    }

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
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isNew) "Add Farm Product" else "Edit Product & Price",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close Dialog")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Product Photo Selector
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    ProductThumbnail(
                                        imageUri = imageUri,
                                        category = category,
                                        name = name.ifBlank { "Product" },
                                        size = 56.dp,
                                        shapeRadius = 12.dp
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Product Photo",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = { photoPickerLauncher.launch("image/*") },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(36.dp)
                                            ) {
                                                Icon(Icons.Default.PhotoCamera, contentDescription = "Choose Photo", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Select Photo", style = MaterialTheme.typography.labelSmall)
                                            }

                                            if (imageUri.isNotBlank()) {
                                                TextButton(
                                                    onClick = { imageUri = "" },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(36.dp)
                                                ) {
                                                    Text("Reset", style = MaterialTheme.typography.labelSmall, color = DebtRed)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Product Name *") },
                                placeholder = { Text("e.g. Crate of Eggs / Live Sasso") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        item {
                            Text(
                                text = "Category",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(ProductCategory.values()) { cat ->
                                    FilterChip(
                                        selected = category == cat.name,
                                        onClick = { category = cat.name },
                                        label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BrandGreenPrimary,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = { priceText = it },
                                    label = { Text("Price ($currencySymbol) *") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                OutlinedTextField(
                                    value = unit,
                                    onValueChange = { unit = it },
                                    label = { Text("Unit") },
                                    placeholder = { Text("Crate / Bird / Kg") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = stockText,
                                onValueChange = { stockText = it },
                                label = { Text("Initial / Current Stock Quantity") },
                                placeholder = { Text("e.g. 100") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Description (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Currently In Stock", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Switch(checked = inStock, onCheckedChange = { inStock = it })
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!isNew) {
                                    OutlinedButton(
                                        onClick = { showDeleteConfirm = true },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DebtRed),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Delete", fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                Button(
                                    onClick = {
                                        val price = priceText.toDoubleOrNull() ?: initialProduct.unitPrice
                                        val stock = stockText.toDoubleOrNull() ?: initialProduct.stockQuantity
                                        onSave(
                                            initialProduct.copy(
                                                name = name.trim(),
                                                category = category,
                                                unit = unit.trim(),
                                                unitPrice = price,
                                                stockQuantity = stock,
                                                description = description.trim(),
                                                inStock = inStock,
                                                imageUri = imageUri.trim()
                                            )
                                        )
                                    },
                                    enabled = name.isNotBlank() && priceText.toDoubleOrNull() != null,
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                                ) {
                                    Text("Save Product", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Delete Product?",
            message = "Are you sure you want to permanently delete '${initialProduct.name}' from your farm catalog?",
            confirmButtonText = "Delete Product",
            cancelButtonText = "Keep Product",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
fun StockAdjustmentDialog(
    product: ProductEntity,
    onDismiss: () -> Unit,
    onConfirmAdjust: (Double, String) -> Unit
) {
    var quantityText by remember { mutableStateOf("") }
    var isAddition by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    val qty = quantityText.toDoubleOrNull() ?: 0.0
    val calculatedChange = if (isAddition) qty else -qty
    val resultingStock = (product.stockQuantity + calculatedChange).coerceAtLeast(0.0)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Adjust Stock Inventory",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${product.name} (Current: ${product.stockQuantity.toInt()} ${product.unit}s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    // Operation Toggle (Add Stock vs Deduct Stock)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { isAddition = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = if (isAddition) {
                                ButtonDefaults.filledTonalButtonColors(containerColor = BrandGreenPrimary, contentColor = Color.White)
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Restock (+)")
                        }

                        FilledTonalButton(
                            onClick = { isAddition = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = if (!isAddition) {
                                ButtonDefaults.filledTonalButtonColors(containerColor = DebtRed, contentColor = Color.White)
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Deduct (-)")
                        }
                    }

                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (${product.unit}s)") },
                        placeholder = { Text("e.g. 20") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Reason / Notes (Optional)") },
                        placeholder = { Text("e.g. Morning collection from Pen B") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Result Preview
                    if (qty > 0) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "New Stock will become: ${resultingStock.toInt()} ${product.unit}s",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isAddition) FarmGreen else DebtRed,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            onClick = { showConfirm = true },
                            enabled = qty > 0,
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAddition) BrandGreenPrimary else DebtRed
                            )
                        ) {
                            Text("Update Stock", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showConfirm) {
        val actionVerb = if (isAddition) "Add" else "Deduct"
        ConfirmationDialog(
            title = "Confirm Stock Adjustment",
            message = "Are you sure you want to $actionVerb ${qty.toInt()} ${product.unit}(s) for '${product.name}'?\n\nNew stock will be ${resultingStock.toInt()} ${product.unit}(s).",
            confirmButtonText = "Confirm Update",
            cancelButtonText = "Cancel",
            isDestructive = !isAddition,
            onConfirm = {
                showConfirm = false
                onConfirmAdjust(calculatedChange, notes)
            },
            onDismiss = { showConfirm = false }
        )
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product_catalog_card_${product.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (isOutOfStock) DebtRed.copy(alpha = 0.35f) else StripeCardBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Row: Stock Badge & Category Tag
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
                        else -> FarmGreenContainer
                    },
                    border = BorderStroke(
                        0.5.dp,
                        when {
                            isOutOfStock -> DebtRed.copy(alpha = 0.3f)
                            product.stockQuantity <= product.minStockThreshold -> BrandAmberGold.copy(alpha = 0.3f)
                            else -> FarmGreen.copy(alpha = 0.3f)
                        }
                    )
                ) {
                    Text(
                        text = when {
                            isOutOfStock -> "Out of Stock"
                            product.stockQuantity <= product.minStockThreshold -> "Low: ${product.stockQuantity.toInt()}"
                            else -> "${product.stockQuantity.toInt()} ${product.unit}s"
                        },
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isOutOfStock -> DebtRed
                            product.stockQuantity <= product.minStockThreshold -> BrandAmberGold
                            else -> FarmGreen
                        }
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = product.category.substringBefore("_"),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            // Action Buttons: Adjust Stock + Edit Product
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onAdjustStock != null) {
                    OutlinedButton(
                        onClick = onAdjustStock,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag("adjust_stock_button_${product.id}"),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Stock", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Button(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .testTag("edit_product_button_${product.id}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Edit", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
