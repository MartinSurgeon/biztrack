package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ProductCategory
import com.example.data.model.ProductEntity

fun getProductDefaultDrawableRes(category: String, name: String): Int {
    val lowerName = name.lowercase()
    val upperCat = category.uppercase()

    return when {
        upperCat == ProductCategory.BROILER_DRESSED.name || lowerName.contains("dressed") -> {
            R.drawable.ic_dressed_chicken
        }
        upperCat == ProductCategory.BROILER_LIVE.name || (lowerName.contains("broiler") && lowerName.contains("live")) -> {
            R.drawable.ic_live_broiler
        }
        upperCat.contains("SASSO") || lowerName.contains("sasso") -> {
            R.drawable.ic_sasso_chicken
        }
        upperCat == ProductCategory.EGGS.name || lowerName.contains("egg") -> {
            R.drawable.ic_fresh_eggs
        }
        else -> {
            R.drawable.ic_farm_product
        }
    }
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
    val fallbackRes = getProductDefaultDrawableRes(category, name)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color(0xFFF4F7F2))
            .border(1.dp, Color(0xFFE5ECE2), shape),
        contentAlignment = Alignment.Center
    ) {
        if (imageUri.isNotBlank()) {
            AsyncImage(
                model = imageUri,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(id = fallbackRes),
                placeholder = painterResource(id = fallbackRes)
            )
        } else {
            Image(
                painter = painterResource(id = fallbackRes),
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Fit
            )
        }
    }
}
