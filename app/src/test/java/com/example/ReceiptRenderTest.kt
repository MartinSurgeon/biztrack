package com.example

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.*
import com.example.ui.components.ReceiptDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.util.DigitalReceiptGenerator
import com.example.util.Formatters
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the shareable receipt/statement exactly as customers receive them, plus the
 * in-app receipt dialog, into src/test/screenshots for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class ReceiptRenderTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val settings = AppSettingsEntity(
        businessName = "Ama's Corner Shop",
        businessTagline = "Everyday essentials, fair prices",
        businessPhone = "024 555 0101",
        businessLocation = "Osu, Accra",
        momoPaymentDetails = "MTN MoMo 024 555 0101 (Ama Mensah)",
        currencySymbol = "GH₵"
    )

    private val items = listOf(
        CartItem(1, "Bottled Water 500ml", ProductCategory.FOOD_DRINKS.name, "Bottle", 3.5, 12.0, 42.0),
        CartItem(2, "Rice 5kg Bag (Premium long grain jasmine, imported)", ProductCategory.GROCERIES.name, "Bag", 95.0, 2.0, 190.0),
        CartItem(3, "Haircut", ProductCategory.SERVICES.name, "Service", 30.0, 1.0, 30.0)
    )

    private fun order(paid: Double, discount: Double = 10.0): SaleOrderEntity {
        val total = items.sumOf { it.lineTotal } - discount
        return SaleOrderEntity(
            id = 7, invoiceNumber = "INV-261019-4821", customerId = 1, customerName = "Kwame Mensah",
            customerPhone = "0244123456", customerWhatsapp = "", itemsJson = Formatters.serializeCartItems(items),
            totalAmount = total, discountAmount = discount, amountPaid = paid, balanceDue = (total - paid).coerceAtLeast(0.0),
            paymentStatus = if (paid >= total) "PAID" else if (paid > 0) "PARTIAL" else "UNPAID",
            paymentMethod = PaymentMethod.MOMO.name, timestamp = 1_792_000_000_000L
        )
    }

    private fun keep(file: File?, name: String): File {
        assertNotNull("generator returned null", file)
        val out = File("src/test/screenshots/$name")
        file!!.copyTo(out, overwrite = true)
        return out
    }

    @Test fun receipt_image_paid() {
        val out = keep(DigitalReceiptGenerator.generateReceiptBitmap(context, order(paid = 252.0), settings), "receipt_paid.png")
        val bmp = BitmapFactory.decodeFile(out.path)
        assertEquals(1080, bmp.width) // 360pt at 3x
    }

    @Test fun receipt_image_part_paid() {
        keep(DigitalReceiptGenerator.generateReceiptBitmap(context, order(paid = 100.0), settings), "receipt_part_paid.png")
    }

    @Test fun statement_image() {
        val customer = CustomerEntity(id = 1, name = "Kwame Mensah", phone = "0244123456", totalPurchases = 1450.0, totalPaid = 1050.0, currentBalance = 400.0)
        val orders = listOf(order(100.0), order(252.0).copy(id = 8, invoiceNumber = "INV-261012-1007"), order(0.0).copy(id = 9, invoiceNumber = "INV-261003-0042"))
        keep(DigitalReceiptGenerator.generateCustomerStatementBitmap(context, customer, orders, settings), "statement.png")
    }

    // The PDF shares this exact layout (1x, no drop shadow), but Robolectric has no native
    // PdfDocument backend, so it can only be checked on a device.

    @OptIn(ExperimentalRoborazziApi::class)
    private fun dialog(name: String, dark: Boolean) {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = dark) {
                ReceiptDialog(order = order(paid = 100.0), settings = settings, onDismiss = {}, onSendSms = {}, onEditOrder = {}, onRecordPayment = {})
            }
        }
        captureScreenRoboImage("src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
    }

    @Test fun receipt_dialog_light() = dialog("receipt_dialog", false)
    @Test fun receipt_dialog_dark() = dialog("receipt_dialog", true)
}
