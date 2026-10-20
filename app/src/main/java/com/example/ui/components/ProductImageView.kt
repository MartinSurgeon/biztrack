package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import coil.compose.SubcomposeAsyncImage
import com.example.R
import com.example.data.model.ProductCategory
import java.io.File

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
    ProductCategory.GENERAL, ProductCategory.OTHER -> Icons.Default.Inventory2
    null -> Icons.Default.Sell // a category the business created itself
}

private val customTones = listOf(Tone.PRIMARY, Tone.INFO, Tone.SUCCESS, Tone.WARNING, Tone.DANGER)

fun categoryTone(category: String): Tone = when (ProductCategory.fromKey(category)) {
    ProductCategory.FOOD_DRINKS, ProductCategory.GROCERIES -> Tone.WARNING
    ProductCategory.FASHION, ProductCategory.HEALTH_BEAUTY -> Tone.DANGER
    ProductCategory.ELECTRONICS, ProductCategory.SERVICES -> Tone.INFO
    ProductCategory.AGRICULTURE -> Tone.SUCCESS
    null -> customTones[(category.lowercase().hashCode() and Int.MAX_VALUE) % customTones.size] // stable per name
    else -> Tone.PRIMARY
}

/** Coil model for a stored image reference: saved files load as File, links as-is. */
private fun imageModel(ref: String): Any = when {
    ref.startsWith("content://") || ref.startsWith("http://") || ref.startsWith("https://") -> ref
    else -> File(ref.removePrefix("file://"))
}

/**
 * Product image with a coloured ring. Shows the photo when there is one, the legacy farm
 * illustration for old products, and otherwise (or if the photo can't be loaded) the
 * category icon.
 */
@Composable
fun ProductThumbnail(
    imageUri: String,
    category: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    shapeRadius: Dp = 14.dp,
    /** Web copy of the photo (from cloud sync), used when the local file is missing. */
    fallbackUrl: String = ""
) {
    val tone = toneColors(categoryTone(category))
    val legacyRes = getLegacyProductDrawableRes(category)
    val ringWidth = if (size >= 56.dp) 2.dp else 1.5.dp
    val gap = if (size >= 56.dp) 3.dp else 2.dp
    val outer = RoundedCornerShape(shapeRadius)
    val inner = RoundedCornerShape((shapeRadius - ringWidth - gap).coerceAtLeast(4.dp))

    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(tone.container), contentAlignment = Alignment.Center) {
            if (legacyRes != null) {
                Image(
                    painter = painterResource(id = legacyRes),
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().padding(size * 0.08f),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(categoryIcon(category), contentDescription = name, tint = tone.content, modifier = Modifier.size(size * 0.42f))
            }
        }
    }

    // Ring = accent colour at partial opacity: clearly visible on white and on near-black.
    Box(
        modifier = modifier
            .size(size)
            .border(ringWidth, tone.content.copy(alpha = 0.45f), outer)
            .padding(ringWidth + gap)
            .clip(inner),
        contentAlignment = Alignment.Center
    ) {
        // Local photo first, then the cloud copy, then the category icon. Never a blank tile.
        val remote = fallbackUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val remoteOrIcon: @Composable () -> Unit = {
            if (remote == null) fallback() else SubcomposeAsyncImage(
                model = remote, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                loading = { fallback() }, error = { fallback() }
            )
        }
        if (imageUri.isBlank()) {
            remoteOrIcon()
        } else {
            SubcomposeAsyncImage(
                model = imageModel(imageUri),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { fallback() },
                error = { remoteOrIcon() }
            )
        }
    }
}
