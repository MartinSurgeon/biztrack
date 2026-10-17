package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ProductCategory

/** Illustrations kept for products created by the original poultry version. */
fun getLegacyProductDrawableRes(category: String): Int? = when (ProductCategory.fromKey(category)) {
    ProductCategory.BROILER_DRESSED, ProductCategory.SASSO_DRESSED -> R.drawable.ic_dressed_chicken
    ProductCategory.BROILER_LIVE -> R.drawable.ic_live_broiler
    ProductCategory.SASSO_LIVE -> R.drawable.ic_sasso_chicken
    ProductCategory.EGGS -> R.drawable.ic_fresh_eggs
    else -> null
}

fun categoryIcon(category: String): ImageVector = when (ProductCategory.fromKey(category)) {
    ProductCategory.FOOD_DRINKS -> Icons.Default.Restaurant
    ProductCategory.GROCERIES -> Icons.Default.LocalGroceryStore
    ProductCategory.FASHION -> Icons.Default.Checkroom
    ProductCategory.ELECTRONICS -> Icons.Default.Devices
    ProductCategory.HEALTH_BEAUTY -> Icons.Default.Spa
    ProductCategory.HOME_HARDWARE -> Icons.Default.Handyman
    ProductCategory.AGRICULTURE, ProductCategory.EGGS, ProductCategory.BROILER_LIVE,
    ProductCategory.BROILER_DRESSED, ProductCategory.SASSO_LIVE, ProductCategory.SASSO_DRESSED -> Icons.Default.Agriculture
    ProductCategory.SERVICES -> Icons.Default.HomeRepairService
    else -> Icons.Default.Inventory2
}

fun categoryTone(category: String): Tone = when (ProductCategory.fromKey(category)) {
    ProductCategory.FOOD_DRINKS, ProductCategory.GROCERIES -> Tone.WARNING
    ProductCategory.FASHION, ProductCategory.HEALTH_BEAUTY -> Tone.DANGER
    ProductCategory.ELECTRONICS, ProductCategory.SERVICES -> Tone.INFO
    ProductCategory.AGRICULTURE -> Tone.SUCCESS
    else -> Tone.PRIMARY
}

@Composable
fun ProductThumbnail(
    imageUri: String,
    category: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    shapeRadius: Dp = 14.dp
) {
    val shape = RoundedCornerShape(shapeRadius)
    val legacyRes = getLegacyProductDrawableRes(category)
    val tone = toneColors(categoryTone(category))

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tone.container),
        contentAlignment = Alignment.Center
    ) {
        when {
            imageUri.isNotBlank() -> AsyncImage(
                model = imageUri,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            legacyRes != null -> Image(
                painter = painterResource(id = legacyRes),
                contentDescription = name,
                modifier = Modifier.fillMaxSize().padding(size * 0.08f),
                contentScale = ContentScale.Fit
            )
            else -> Icon(
                imageVector = categoryIcon(category),
                contentDescription = name,
                tint = tone.content,
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}
