package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.ProductCategory
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders clay cards and product thumbnails through Android's HardwareRenderer (like a real
 * phone), since shadow layers behave differently there than in software rendering.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class ClayHardwareRenderTest {

    companion object {
        @JvmStatic @BeforeClass fun hardware() {
            System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        }
    }

    @get:Rule val composeTestRule = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun render(name: String, dark: Boolean) {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = dark) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    AppCard(color = MaterialTheme.colorScheme.primary) {
                        Text("Total sales", color = MaterialTheme.colorScheme.onPrimary)
                        Text("GH₵ 715.00", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppCard(Modifier.weight(1f), onClick = {}) {
                            IconBadge(Icons.Default.Inventory2)
                            Spacer(Modifier.height(8.dp)); Text("Clickable card")
                        }
                        AppCard(Modifier.weight(1f)) {
                            IconBadge(Icons.Default.Inventory2, tone = Tone.SUCCESS)
                            Spacer(Modifier.height(8.dp)); Text("Plain card")
                        }
                    }
                    AppCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ProductThumbnail("", ProductCategory.FOOD_DRINKS.name, "Water", size = 64.dp, shapeRadius = 18.dp)
                            ProductThumbnail("", "Pastries", "Croissant", size = 64.dp, shapeRadius = 18.dp)
                            ProductThumbnail("/missing/photo.jpg", ProductCategory.ELECTRONICS.name, "Broken photo", size = 64.dp, shapeRadius = 18.dp)
                            ProductThumbnail("", ProductCategory.EGGS.name, "Eggs", size = 64.dp, shapeRadius = 18.dp)
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/clay_hw_${name}.png")
    }

    @Test fun clay_light() = render("light", false)
    @Test fun clay_dark() = render("dark", true)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun productForm(name: String, dark: Boolean) {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = dark) {
                com.example.ui.screens.ProductFormDialog(
                    initialProduct = com.example.data.model.ProductEntity(
                        id = 3, name = "Butter croissant", category = "Pastries", unit = "Piece", unitPrice = 12.0, stockQuantity = 24.0
                    ),
                    isNew = false,
                    currencySymbol = "GH₵",
                    onDismiss = {}, onSave = {}, onDelete = {},
                    categoryUsage = mapOf("Pastries" to 6, "Cakes" to 3, ProductCategory.FOOD_DRINKS.name to 2)
                )
            }
        }
        composeTestRule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/product_form_${name}.png")
    }

    @Test fun product_form_light() = productForm("light", false)
    @Test fun product_form_dark() = productForm("dark", true)
}
