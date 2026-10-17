package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.model.*
import com.example.ui.screens.CustomersDebtScreen
import com.example.ui.screens.ProductsCatalogScreen
import com.example.ui.screens.SalesDashboardScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.Formatters
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens in light and dark theme to check layout and contrast. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class ScreensScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val settings = AppSettingsEntity(businessName = "Ama's Corner Shop", currencySymbol = "GH₵")

    private val products = listOf(
        ProductEntity(id = 1, name = "Bottled Water 500ml", category = ProductCategory.FOOD_DRINKS.name, unit = "Bottle", unitPrice = 3.5, stockQuantity = 120.0),
        ProductEntity(id = 2, name = "Rice 5kg Bag", category = ProductCategory.GROCERIES.name, unit = "Bag", unitPrice = 95.0, stockQuantity = 4.0),
        ProductEntity(id = 3, name = "Phone Charger", category = ProductCategory.ELECTRONICS.name, unit = "Piece", unitPrice = 45.0, stockQuantity = 0.0),
        ProductEntity(id = 4, name = "Haircut", category = ProductCategory.SERVICES.name, unit = "Service", unitPrice = 30.0, stockQuantity = 999.0),
        ProductEntity(id = 5, name = "Fresh Eggs (Crate)", category = ProductCategory.EGGS.name, unit = "Crate", unitPrice = 55.0, stockQuantity = 30.0)
    )

    private val customers = listOf(
        CustomerEntity(id = 1, name = "Kwame Mensah", phone = "0244123456", totalPurchases = 950.0, totalPaid = 550.0, currentBalance = 400.0),
        CustomerEntity(id = 2, name = "Efua Boateng", phone = "0201234567", totalPurchases = 300.0, totalPaid = 300.0, currentBalance = 0.0)
    )

    private val orders = listOf(
        order(1, "Kwame Mensah", 1, total = 550.0, paid = 150.0, status = "PARTIAL"),
        order(2, "Efua Boateng", 2, total = 120.0, paid = 120.0, status = "PAID"),
        order(3, "Walk-in", 0, total = 45.0, paid = 0.0, status = "UNPAID")
    )

    private fun order(id: Long, name: String, customerId: Long, total: Double, paid: Double, status: String) = SaleOrderEntity(
        id = id,
        invoiceNumber = "INV-1000$id",
        customerId = customerId,
        customerName = name,
        customerPhone = "0244000000",
        customerWhatsapp = "",
        itemsJson = Formatters.serializeCartItems(
            listOf(CartItem(1, "Bottled Water 500ml", ProductCategory.FOOD_DRINKS.name, "Bottle", 3.5, 10.0, 35.0))
        ),
        totalAmount = total,
        amountPaid = paid,
        balanceDue = total - paid,
        paymentStatus = status,
        paymentMethod = PaymentMethod.CASH.name
    )

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
    }

    @Composable
    private fun Dashboard() = SalesDashboardScreen(
        orders = orders, debtors = customers.take(1), totalDebt = 400.0, totalRevenue = 715.0, totalCollected = 270.0,
        settings = settings, onNavigateToNewSale = {}, onNavigateToDebtors = {}, onNavigateToProducts = {},
        onSelectOrderReceipt = {}, onSendSms = {}, onEditOrder = {}, onVoidOrder = {}, products = products
    )

    @Composable
    private fun Products() = ProductsCatalogScreen(products = products, settings = settings, onSaveProduct = {}, onDeleteProduct = {}, onAdjustStock = { _, _, _, _ -> })

    @Composable
    private fun Customers() = CustomersDebtScreen(
        customers = customers, debtors = customers.take(1), totalDebt = 400.0, settings = settings, orders = orders,
        onRecordPayment = {}, onSendReminderSms = {}, onSaveCustomer = {}, onDeleteCustomer = {}, onAddCustomer = {}
    )

    @Test fun dashboard_light() = capture("dashboard", false) { Dashboard() }
    @Test fun dashboard_dark() = capture("dashboard", true) { Dashboard() }
    @Test fun products_light() = capture("products", false) { Products() }
    @Test fun products_dark() = capture("products", true) { Products() }
    @Test fun customers_light() = capture("customers", false) { Customers() }
    @Test fun customers_dark() = capture("customers", true) { Customers() }
}
