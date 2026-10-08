package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.AppSettingsEntity
import com.example.data.model.CartItem
import com.example.data.model.CustomerEntity
import com.example.data.model.SaleOrderEntity
import com.example.data.remote.SmsService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DigitalReceiptGenerator {

    private const val TAG = "DigitalReceiptGenerator"

    /**
     * Generates a high-resolution, branded receipt image (PNG).
     */
    fun generateReceiptBitmap(
        context: Context,
        order: SaleOrderEntity,
        settings: AppSettingsEntity
    ): File? {
        val items = Formatters.parseCartItems(order.itemsJson)
        val width = 720
        // Calculate dynamic height tightly
        val baseHeight = 580
        val itemHeight = 44
        val totalHeight = baseHeight + (items.size * itemHeight)

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Colors
        val colorBackground = 0xFFFFFFFF.toInt()
        val colorPrimary = 0xFF1B4332.toInt() // Forest Green
        val colorDark = 0xFF1E293B.toInt()
        val colorGray = 0xFF64748B.toInt()
        val colorDebtRed = 0xFFDC2626.toInt()
        val colorPaidGreen = 0xFF15803D.toInt()

        // Background
        canvas.drawColor(colorBackground)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw Outer Border
        paint.color = 0xFFE2E8F0.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        val borderRect = RectF(10f, 10f, width - 10f, totalHeight - 10f)
        canvas.drawRoundRect(borderRect, 16f, 16f, paint)

        // Header Background
        paint.style = Paint.Style.FILL
        paint.color = colorPrimary
        val headerRect = RectF(10f, 10f, width - 10f, 140f)
        canvas.drawRoundRect(headerRect, 16f, 16f, paint)
        canvas.drawRect(RectF(10f, 100f, width - 10f, 140f), paint)

        // Header Content
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 28f
        canvas.drawText(settings.businessName.uppercase(), width / 2f, 52f, paint)

        paint.color = 0xFFD1E7DD.toInt()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        paint.textSize = 17f
        if (settings.businessTagline.isNotBlank()) {
            canvas.drawText("“${settings.businessTagline}”", width / 2f, 82f, paint)
        }

        if (settings.businessPhone.isNotBlank()) {
            paint.color = 0xFFFCD34D.toInt()
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 16f
            canvas.drawText("Tel / WhatsApp: ${settings.businessPhone}", width / 2f, 114f, paint)
        }

        // Subheader Details Bar (Invoice # & Customer)
        var currentY = 175f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Receipt #${order.invoiceNumber}", 30f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = colorGray
        paint.textSize = 17f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(Formatters.formatDate(order.timestamp), width - 30f, currentY, paint)

        currentY += 32f
        // Customer row
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF8FAFC.toInt()
        val custRect = RectF(26f, currentY - 8f, width - 26f, currentY + 38f)
        canvas.drawRoundRect(custRect, 8f, 8f, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.color = colorPrimary
        paint.textSize = 18f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Customer: ${order.customerName}", 40f, currentY + 22f, paint)

        if (order.customerPhone.isNotBlank()) {
            paint.textAlign = Paint.Align.RIGHT
            paint.color = colorGray
            paint.textSize = 16f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Tel: ${order.customerPhone}", width - 40f, currentY + 22f, paint)
        }

        currentY += 60f

        // Table Header
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE2E8F0.toInt()
        val thRect = RectF(26f, currentY - 20f, width - 26f, currentY + 12f)
        canvas.drawRoundRect(thRect, 6f, 6f, paint)

        paint.color = colorDark
        paint.textSize = 16f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("ITEM & QUANTITY", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("AMOUNT (${settings.currencySymbol})", width - 40f, currentY, paint)

        currentY += 28f

        // Items Loop
        items.forEachIndexed { index, item ->
            val qtyStr = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString()

            paint.style = Paint.Style.FILL
            paint.color = colorDark
            paint.textSize = 18f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.productName, 40f, currentY, paint)

            paint.color = colorGray
            paint.textSize = 14f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("$qtyStr ${item.unit} @ ${Formatters.formatCurrency(item.unitPrice, settings.currencySymbol)}", 40f, currentY + 18f, paint)

            paint.color = colorDark
            paint.textSize = 18f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(Formatters.formatCurrency(item.lineTotal, settings.currencySymbol), width - 40f, currentY + 10f, paint)

            currentY += itemHeight.toFloat()
        }

        // Divider
        paint.style = Paint.Style.STROKE
        paint.color = 0xFFCBD5E1.toInt()
        paint.strokeWidth = 1.5f
        canvas.drawLine(26f, currentY, width - 26f, currentY, paint)

        currentY += 26f

        // Financial Summary
        paint.style = Paint.Style.FILL
        val subtotal = items.sumOf { it.lineTotal }
        if (order.discountAmount > 0) {
            paint.textAlign = Paint.Align.LEFT
            paint.color = colorGray
            paint.textSize = 16f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Subtotal:", 40f, currentY, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.color = colorDark
            paint.textSize = 18f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText(Formatters.formatCurrency(subtotal, settings.currencySymbol), width - 40f, currentY, paint)

            currentY += 24f
            paint.textAlign = Paint.Align.LEFT
            paint.color = 0xFFB45309.toInt()
            paint.textSize = 16f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("🏷️ Discount Granted:", 40f, currentY, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.color = 0xFFB45309.toInt()
            paint.textSize = 18f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("-${Formatters.formatCurrency(order.discountAmount, settings.currencySymbol)}", width - 40f, currentY, paint)

            currentY += 26f
        }

        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 18f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(if (order.discountAmount > 0) "Net Total Bill:" else "Total Bill:", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = colorPrimary
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(Formatters.formatCurrency(order.totalAmount, settings.currencySymbol), width - 40f, currentY, paint)

        currentY += 26f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorGray
        paint.textSize = 16f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Amount Paid:", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = colorPaidGreen
        paint.textSize = 18f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(Formatters.formatCurrency(order.amountPaid, settings.currencySymbol), width - 40f, currentY, paint)

        currentY += 26f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 18f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Balance Due:", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = if (order.balanceDue > 0) colorDebtRed else colorPaidGreen
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(Formatters.formatCurrency(order.balanceDue, settings.currencySymbol), width - 40f, currentY, paint)

        currentY += 36f

        // Payment Status Badge
        val statusRect = RectF(40f, currentY, width - 40f, currentY + 42f)
        paint.style = Paint.Style.FILL
        if (order.balanceDue <= 0) {
            paint.color = 0xFFDCFCE7.toInt()
            canvas.drawRoundRect(statusRect, 8f, 8f, paint)
            paint.color = colorPaidGreen
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 17f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("✓ PAID IN FULL — THANK YOU", width / 2f, currentY + 27f, paint)
        } else if (order.amountPaid > 0) {
            paint.color = 0xFFFEF3C7.toInt()
            canvas.drawRoundRect(statusRect, 8f, 8f, paint)
            paint.color = 0xFFB45309.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 16f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("⚠ PARTIALLY PAID • BALANCE DUE: ${Formatters.formatCurrency(order.balanceDue, settings.currencySymbol)}", width / 2f, currentY + 27f, paint)
        } else {
            paint.color = 0xFFFEE2E2.toInt()
            canvas.drawRoundRect(statusRect, 8f, 8f, paint)
            paint.color = colorDebtRed
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 16f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("⚠ CREDIT SALE • OUTSTANDING BILL: ${Formatters.formatCurrency(order.balanceDue, settings.currencySymbol)}", width / 2f, currentY + 27f, paint)
        }

        currentY += 60f

        // Footer
        if (settings.momoPaymentDetails.isNotBlank()) {
            paint.color = 0xFF92400E.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 15f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("MoMo Payment: ${settings.momoPaymentDetails}", width / 2f, currentY, paint)
            currentY += 22f
        }

        paint.color = colorGray
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Thank you for choosing our farm Products, we look forward to serving you again!", width / 2f, currentY, paint)

        // Save Bitmap to Cache Directory
        return saveBitmapToCache(context, bitmap, "receipt_${order.invoiceNumber}.png")
    }

    /**
     * Generates an official PDF invoice receipt document.
     */
    fun generateReceiptPdf(
        context: Context,
        order: SaleOrderEntity,
        settings: AppSettingsEntity
    ): File? {
        val items = Formatters.parseCartItems(order.itemsJson)
        val pageWidth = 595 // A4 standard width in points
        val pageHeight = 842 // A4 standard height in points

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val colorPrimary = 0xFF1B4332.toInt()
        val colorDark = 0xFF1A1A1A.toInt()
        val colorGray = 0xFF555555.toInt()
        val colorDebtRed = 0xFFDC2626.toInt()
        val colorPaidGreen = 0xFF15803D.toInt()

        // Background
        canvas.drawColor(0xFFFFFFFF.toInt())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Header Background
        paint.style = Paint.Style.FILL
        paint.color = colorPrimary
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 120f, paint)

        // Header Text
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 22f
        canvas.drawText(settings.businessName.uppercase(), pageWidth / 2f, 45f, paint)

        paint.color = 0xFFD1E7DD.toInt()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        paint.textSize = 13f
        canvas.drawText("“${settings.businessTagline}”", pageWidth / 2f, 68f, paint)

        if (settings.businessPhone.isNotBlank()) {
            paint.color = 0xFFFCD34D.toInt()
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 12f
            canvas.drawText("Tel / WhatsApp: ${settings.businessPhone}", pageWidth / 2f, 90f, paint)
        }

        // Subheader
        var currentY = 155f
        paint.color = colorDark
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 16f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("INVOICE / RECEIPT: #${order.invoiceNumber}", 30f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = colorGray
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Date: ${Formatters.formatDate(order.timestamp)}", pageWidth - 30f, currentY, paint)

        currentY += 28f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Customer: ${order.customerName}", 30f, currentY, paint)

        if (order.customerPhone.isNotBlank()) {
            paint.textAlign = Paint.Align.RIGHT
            paint.color = colorGray
            paint.textSize = 12f
            canvas.drawText("Phone: ${order.customerPhone}", pageWidth - 30f, currentY, paint)
        }

        currentY += 25f

        // Table Header
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE2E8F0.toInt()
        canvas.drawRect(30f, currentY - 14f, pageWidth - 30f, currentY + 12f, paint)

        paint.color = colorDark
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("ITEM DESCRIPTION", 40f, currentY, paint)

        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("QUANTITY & RATE", pageWidth - 160f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("TOTAL (${settings.currencySymbol})", pageWidth - 40f, currentY, paint)

        currentY += 24f

        items.forEachIndexed { index, item ->
            val qtyStr = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString()

            paint.color = colorDark
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.productName, 40f, currentY, paint)

            paint.color = colorGray
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$qtyStr ${item.unit} @ ${item.unitPrice}", pageWidth - 160f, currentY, paint)

            paint.color = colorDark
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(Formatters.formatCurrency(item.lineTotal, settings.currencySymbol), pageWidth - 40f, currentY, paint)

            currentY += 22f
        }

        currentY += 10f
        paint.style = Paint.Style.STROKE
        paint.color = colorPrimary
        paint.strokeWidth = 1.5f
        canvas.drawLine(30f, currentY, pageWidth - 30f, currentY, paint)

        currentY += 26f
        paint.style = Paint.Style.FILL
        val subtotal = items.sumOf { it.lineTotal }
        if (order.discountAmount > 0) {
            paint.textAlign = Paint.Align.LEFT
            paint.color = colorGray
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Subtotal:", 40f, currentY, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.color = colorDark
            paint.textSize = 13f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText(Formatters.formatCurrency(subtotal, settings.currencySymbol), pageWidth - 40f, currentY, paint)

            currentY += 18f
            paint.textAlign = Paint.Align.LEFT
            paint.color = 0xFFB45309.toInt()
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("🏷️ Discount Granted:", 40f, currentY, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.color = 0xFFB45309.toInt()
            paint.textSize = 13f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("-${Formatters.formatCurrency(order.discountAmount, settings.currencySymbol)}", pageWidth - 40f, currentY, paint)

            currentY += 20f
        }

        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(if (order.discountAmount > 0) "Net Total Bill:" else "Total Amount:", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = colorPrimary
        paint.textSize = 16f
        canvas.drawText(Formatters.formatCurrency(order.totalAmount, settings.currencySymbol), pageWidth - 40f, currentY, paint)

        currentY += 22f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorGray
        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Amount Paid:", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = colorPaidGreen
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(Formatters.formatCurrency(order.amountPaid, settings.currencySymbol), pageWidth - 40f, currentY, paint)

        currentY += 22f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Balance Due:", 40f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = if (order.balanceDue > 0) colorDebtRed else colorPaidGreen
        paint.textSize = 16f
        canvas.drawText(Formatters.formatCurrency(order.balanceDue, settings.currencySymbol), pageWidth - 40f, currentY, paint)

        currentY += 30f
        // Status box
        val statusRect = RectF(40f, currentY, pageWidth - 40f, currentY + 32f)
        paint.style = Paint.Style.FILL
        if (order.balanceDue <= 0) {
            paint.color = 0xFFDCFCE7.toInt()
            canvas.drawRoundRect(statusRect, 6f, 6f, paint)
            paint.color = colorPaidGreen
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 13f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("✅ YOU PAID IN FULL — THANK YOU", pageWidth / 2f, currentY + 20f, paint)
        } else if (order.amountPaid > 0) {
            paint.color = 0xFFFEF3C7.toInt()
            canvas.drawRoundRect(statusRect, 6f, 6f, paint)
            paint.color = 0xFFB45309.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("PARTIALLY PAID • BALANCE DUE: ${Formatters.formatCurrency(order.balanceDue, settings.currencySymbol)}", pageWidth / 2f, currentY + 20f, paint)
        } else {
            paint.color = 0xFFFEE2E2.toInt()
            canvas.drawRoundRect(statusRect, 6f, 6f, paint)
            paint.color = colorDebtRed
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("CREDIT SALE/OUTSTANDING BILL: ${Formatters.formatCurrency(order.balanceDue, settings.currencySymbol)}", pageWidth / 2f, currentY + 20f, paint)
        }

        currentY += 50f
        if (settings.momoPaymentDetails.isNotBlank()) {
            paint.color = 0xFF92400E.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 11f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("Payment Details: ${settings.momoPaymentDetails}", pageWidth / 2f, currentY, paint)
            currentY += 20f
        }

        paint.color = colorGray
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Thank you for choosing our farm Products, we look forward to serving you again!", pageWidth / 2f, currentY + 15f, paint)

        document.finishPage(page)

        // Save PDF to cache
        val pdfDir = File(context.cacheDir, "pdf_receipts")
        if (!pdfDir.exists()) pdfDir.mkdirs()
        val file = File(pdfDir, "invoice_${order.invoiceNumber}.pdf")

        try {
            FileOutputStream(file).use { out ->
                document.writeTo(out)
            }
            document.close()
            return file
        } catch (e: IOException) {
            Log.e(TAG, "Error generating PDF", e)
            document.close()
            return null
        }
    }

    /**
     * Generates a digital customer statement image.
     */
    fun generateCustomerStatementBitmap(
        context: Context,
        customer: CustomerEntity,
        orders: List<SaleOrderEntity> = emptyList(),
        settings: AppSettingsEntity
    ): File? {
        val width = 900
        val baseHeight = 900
        val rowHeight = 45
        val totalHeight = baseHeight + (orders.take(8).size * rowHeight)

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colorBackground = 0xFFFCFCF9.toInt()
        val colorPrimary = 0xFF1B4332.toInt()
        val colorDark = 0xFF1A1A1A.toInt()
        val colorGray = 0xFF555555.toInt()
        val colorDebtRed = 0xFFDC2626.toInt()
        val colorPaidGreen = 0xFF15803D.toInt()

        canvas.drawColor(colorBackground)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Border
        paint.color = colorPrimary
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawRoundRect(RectF(12f, 12f, width - 12f, totalHeight - 12f), 24f, 24f, paint)

        // Header
        paint.style = Paint.Style.FILL
        paint.color = colorPrimary
        canvas.drawRect(RectF(12f, 12f, width - 12f, 200f), paint)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 34f
        canvas.drawText(settings.businessName.uppercase(), width / 2f, 70f, paint)

        paint.color = 0xFFD1E7DD.toInt()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        paint.textSize = 22f
        canvas.drawText("“${settings.businessTagline}”", width / 2f, 110f, paint)

        paint.color = Color.WHITE
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("📊 OFFICIAL CUSTOMER ACCOUNT STATEMENT", width / 2f, 160f, paint)

        var currentY = 250f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorDark
        paint.textSize = 26f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Customer: ${customer.name}", 40f, currentY, paint)

        if (customer.phone.isNotBlank()) {
            paint.textAlign = Paint.Align.RIGHT
            paint.color = colorGray
            paint.textSize = 22f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Phone: ${customer.phone}", width - 40f, currentY, paint)
        }

        currentY += 40f
        paint.textAlign = Paint.Align.LEFT
        paint.color = colorGray
        paint.textSize = 18f
        canvas.drawText("Statement Date: ${Formatters.formatDate(System.currentTimeMillis())}", 40f, currentY, paint)

        currentY += 40f

        // Financial Metrics Cards
        val cardWidth = (width - 120f) / 3f
        val cardHeight = 110f

        // Card 1: Total Purchases
        drawMetricCard(canvas, paint, 40f, currentY, cardWidth, cardHeight, "Total Purchases", Formatters.formatCurrency(customer.totalPurchases, settings.currencySymbol), colorPrimary)
        // Card 2: Total Paid
        drawMetricCard(canvas, paint, 50f + cardWidth, currentY, cardWidth, cardHeight, "Total Paid", Formatters.formatCurrency(customer.totalPaid, settings.currencySymbol), colorPaidGreen)
        // Card 3: Outstanding Debt
        drawMetricCard(canvas, paint, 60f + (cardWidth * 2), currentY, cardWidth, cardHeight, "Balance Due", Formatters.formatCurrency(customer.currentBalance, settings.currencySymbol), if (customer.currentBalance > 0) colorDebtRed else colorPaidGreen)

        currentY += 150f

        // Transactions Table Header
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE2E8F0.toInt()
        canvas.drawRoundRect(RectF(40f, currentY - 25f, width - 40f, currentY + 15f), 8f, 8f, paint)

        paint.color = colorDark
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("RECENT INVOICES", 56f, currentY, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("STATUS & TOTAL", width - 56f, currentY, paint)

        currentY += 35f

        orders.take(8).forEachIndexed { index, ord ->
            val isEven = index % 2 == 0
            if (isEven) {
                paint.style = Paint.Style.FILL
                paint.color = 0xFFF8FAFC.toInt()
                canvas.drawRect(RectF(40f, currentY - 20f, width - 40f, currentY + 22f), paint)
            }

            paint.color = colorDark
            paint.textSize = 20f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("#${ord.invoiceNumber}  •  ${Formatters.formatDateShort(ord.timestamp)}", 56f, currentY, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.color = if (ord.balanceDue > 0) colorDebtRed else colorPaidGreen
            val status = if (ord.balanceDue > 0) "Owing ${Formatters.formatCurrency(ord.balanceDue, settings.currencySymbol)}" else "Paid"
            canvas.drawText("${Formatters.formatCurrency(ord.totalAmount, settings.currencySymbol)}  ($status)", width - 56f, currentY, paint)

            currentY += 42f
        }

        currentY += 30f
        // Payment Info
        if (settings.momoPaymentDetails.isNotBlank()) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFEF3C7.toInt()
            val momoRect = RectF(40f, currentY, width - 40f, currentY + 50f)
            canvas.drawRoundRect(momoRect, 8f, 8f, paint)

            paint.color = 0xFF92400E.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 20f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("💳 Payment Info: ${settings.momoPaymentDetails}", width / 2f, currentY + 32f, paint)
            currentY += 70f
        }

        paint.color = colorGray
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Thank you for choosing our farm Products, we look forward to serving you again!", width / 2f, currentY + 15f, paint)

        return saveBitmapToCache(context, bitmap, "statement_${customer.name.replace(" ", "_")}.png")
    }

    private fun drawMetricCard(
        canvas: Canvas,
        paint: Paint,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        title: String,
        value: String,
        accentColor: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF1F5F9.toInt()
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, 12f, 12f, paint)

        paint.style = Paint.Style.STROKE
        paint.color = 0xFFCBD5E1.toInt()
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(rect, 12f, 12f, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xFF64748B.toInt()
        paint.textSize = 17f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(title, x + (w / 2f), y + 34f, paint)

        paint.color = accentColor
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(value, x + (w / 2f), y + 74f, paint)
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap, fileName: String): File? {
        val receiptsDir = File(context.cacheDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()
        val file = File(receiptsDir, fileName)

        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "Error saving bitmap to cache", e)
            null
        }
    }

    /**
     * Shares a generated digital file (Image or PDF) directly to WhatsApp or Android share sheet.
     */
    fun shareDigitalFile(
        context: Context,
        file: File,
        mimeType: String,
        captionText: String = "",
        recipientPhone: String = "",
        preferWhatsApp: Boolean = true
    ) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                if (captionText.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, captionText)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (preferWhatsApp) {
                // Try WhatsApp specific intent
                val cleanPhone = SmsService.formatGhanaPhoneNumber(recipientPhone)
                shareIntent.setPackage("com.whatsapp")

                try {
                    context.startActivity(shareIntent)
                    return
                } catch (e: Exception) {
                    // Try WhatsApp Business package
                    try {
                        val waBizIntent = Intent(shareIntent).apply { setPackage("com.whatsapp.w4b") }
                        context.startActivity(waBizIntent)
                        return
                    } catch (e2: Exception) {
                        // Fallback to system chooser
                        shareIntent.setPackage(null)
                    }
                }
            }

            val chooser = Intent.createChooser(shareIntent, "Share Digital Receipt via...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share digital receipt file", e)
            Toast.makeText(context, "Could not open share dialog: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
