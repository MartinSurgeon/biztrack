package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders receipts and customer statements as a PNG image or a PDF for sharing.
 *
 * Both formats use the same layout, drawn in "points" on a 360pt-wide receipt:
 * - PNG is rendered at 3x (1080px wide) so it stays sharp on phones.
 * - PDF uses a receipt-sized page whose height fits the content, instead of a
 *   mostly blank A4 sheet.
 * Every layout runs twice: once without a canvas to measure the height, then to draw.
 */
object DigitalReceiptGenerator {

    private const val TAG = "DigitalReceiptGenerator"
    private const val PAGE_WIDTH = 360f
    private const val PNG_SCALE = 3f

    // Palette matches the app theme (light). Text colours meet WCAG AA on their backgrounds.
    private object Ink {
        const val page = 0xFFEFF1F7.toInt()
        const val card = 0xFFFFFFFF.toInt()
        const val text = 0xFF09090B.toInt()
        const val muted = 0xFF52525B.toInt()
        const val subtle = 0xFF71717A.toInt()
        const val line = 0xFFE4E4E7.toInt()
        const val soft = 0xFFF4F4F5.toInt()
        const val primary = 0xFF4F46E5.toInt()
        const val primarySoft = 0xFFEEF2FF.toInt()
        const val onPrimarySoft = 0xFF312E81.toInt()
        const val success = 0xFF15803D.toInt()
        const val successSoft = 0xFFDCFCE7.toInt()
        const val onSuccessSoft = 0xFF14532D.toInt()
        const val warning = 0xFFB45309.toInt()
        const val warningSoft = 0xFFFEF3C7.toInt()
        const val onWarningSoft = 0xFF78350F.toInt()
        const val danger = 0xFFDC2626.toInt()
        const val dangerSoft = 0xFFFEE2E2.toInt()
        const val onDangerSoft = 0xFF7F1D1D.toInt()
        const val shadow = 0x26303656
    }

    private enum class Status(val label: String, val bg: Int, val fg: Int) {
        PAID("Paid", Ink.successSoft, Ink.onSuccessSoft),
        PARTIAL("Part paid", Ink.warningSoft, Ink.onWarningSoft),
        UNPAID("Unpaid", Ink.dangerSoft, Ink.onDangerSoft)
    }

    private fun statusOf(order: SaleOrderEntity) = when {
        order.balanceDue <= 0.009 -> Status.PAID
        order.amountPaid > 0.009 -> Status.PARTIAL
        else -> Status.UNPAID
    }

    private fun methodLabel(method: String) = when (method.uppercase()) {
        "CASH" -> "Cash"
        "MOMO" -> "Mobile money"
        "BANK_TRANSFER", "BANK" -> "Bank transfer"
        else -> method.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Cash" }
    }

    private fun qty(q: Double) = if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()

    private fun initials(name: String) = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "B" }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Receipt as a sharp PNG (1080px wide), sized to its content. */
    fun generateReceiptBitmap(context: Context, order: SaleOrderEntity, settings: AppSettingsEntity): File? = try {
        val bitmap = renderBitmap { pen -> drawReceipt(pen, order, settings) }
        saveBitmapToCache(context, bitmap, "receipt_${safeName(order.invoiceNumber)}.png")
    } catch (e: Exception) {
        Log.e(TAG, "Error generating receipt image", e)
        null
    }

    /** Receipt as a compact PDF: a 360pt-wide page exactly as tall as the receipt. */
    fun generateReceiptPdf(context: Context, order: SaleOrderEntity, settings: AppSettingsEntity): File? {
        val dir = File(context.cacheDir, "pdf_receipts").apply { mkdirs() }
        val file = File(dir, "receipt_${safeName(order.invoiceNumber)}.pdf")
        return renderPdf(file) { pen -> drawReceipt(pen, order, settings) }
    }

    /** Customer account statement as a PNG. */
    fun generateCustomerStatementBitmap(
        context: Context,
        customer: CustomerEntity,
        orders: List<SaleOrderEntity> = emptyList(),
        settings: AppSettingsEntity
    ): File? = try {
        val bitmap = renderBitmap { pen -> drawStatement(pen, customer, orders, settings) }
        saveBitmapToCache(context, bitmap, "statement_${safeName(customer.name)}.png")
    } catch (e: Exception) {
        Log.e(TAG, "Error generating statement image", e)
        null
    }

    // ------------------------------------------------------------------
    // Rendering targets
    // ------------------------------------------------------------------

    private fun renderBitmap(draw: (Pen) -> Float): Bitmap {
        val height = draw(Pen(null, forPdf = false))
        val bitmap = Bitmap.createBitmap((PAGE_WIDTH * PNG_SCALE).toInt(), (height * PNG_SCALE).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(PNG_SCALE, PNG_SCALE)
        draw(Pen(canvas, forPdf = false, pageHeight = height))
        return bitmap
    }

    private fun renderPdf(file: File, draw: (Pen) -> Float): File? {
        val height = draw(Pen(null, forPdf = true))
        val document = PdfDocument()
        return try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), height.toInt() + 1, 1).create())
            draw(Pen(page.canvas, forPdf = true, pageHeight = height))
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error generating PDF", e)
            null
        } finally {
            document.close()
        }
    }

    // ------------------------------------------------------------------
    // Layouts
    // ------------------------------------------------------------------

    private const val MARGIN = 16f     // page edge -> card
    private const val PAD = 22f        // card edge -> content

    /** Draws the receipt and returns its total height. */
    private fun drawReceipt(pen: Pen, order: SaleOrderEntity, settings: AppSettingsEntity): Float {
        val items = Formatters.parseCartItems(order.itemsJson)
        val cur = settings.currencySymbol
        val status = statusOf(order)
        val left = MARGIN + PAD
        val right = PAGE_WIDTH - MARGIN - PAD
        val width = right - left

        return pen.card { startY ->
            var y = startY + PAD
            y = drawBusinessHeader(pen, settings, left, right, y)

            y += 16f
            pen.perforation(y)
            y += 18f

            // Amount + status: the first thing a customer looks for.
            pen.text("Total", left, y + 11f, size = 11.5f, color = Ink.muted)
            pen.pill(status.label, right, y, status.bg, status.fg, alignRight = true)
            y += 18f
            y += pen.line(Formatters.formatCurrency(order.totalAmount, cur), left, y + 30f, size = 30f, weight = Weight.BOLD, color = Ink.text, maxWidth = width) + 4f
            if (order.balanceDue > 0.009) {
                pen.text("Balance due  ${Formatters.formatCurrency(order.balanceDue, cur)}", left, y + 14f, size = 13f, weight = Weight.SEMIBOLD, color = Ink.danger)
                y += 20f
            }

            y += 14f
            val colW = (width - 12f) / 2f
            y += metaPair(pen, "Receipt no.", order.invoiceNumber, left, y, colW, "Date", formatReceiptDate(order.timestamp), left + colW + 12f)
            y += 12f
            y += metaPair(pen, "Customer", order.customerName.ifBlank { "Walk-in customer" }, left, y, colW, "Payment", methodLabel(order.paymentMethod), left + colW + 12f)

            y += 16f
            pen.perforation(y)
            y += 18f

            pen.text("ITEMS", left, y + 10f, size = 10.5f, weight = Weight.SEMIBOLD, color = Ink.subtle, tracking = 0.08f)
            pen.text("${items.size} ${if (items.size == 1) "item" else "items"}", right, y + 10f, size = 10.5f, color = Ink.subtle, align = Paint.Align.RIGHT)
            y += 20f

            items.forEachIndexed { i, item ->
                if (i > 0) { pen.hairline(left, right, y); y += 10f }
                y += itemRow(pen, item, cur, left, right, y) + 10f
            }

            // Totals
            y += 4f
            val rows = buildList {
                if (order.discountAmount > 0.009) {
                    add(Triple("Subtotal", Formatters.formatCurrency(items.sumOf { it.lineTotal }, cur), Ink.text))
                    add(Triple("Discount", "− ${Formatters.formatCurrency(order.discountAmount, cur)}", Ink.warning))
                }
                add(Triple("Paid", Formatters.formatCurrency(order.amountPaid, cur), Ink.success))
            }
            val boxTop = y
            val boxH = 14f + rows.size * 22f + 30f + (if (order.balanceDue > 0.009) 26f else 0f) + 10f
            pen.roundRect(left - 8f, boxTop, right + 8f, boxTop + boxH, 16f, Ink.soft)
            y += 14f
            rows.forEach { (label, value, color) ->
                pen.text(label, left + 4f, y + 12f, size = 12.5f, color = Ink.muted)
                pen.text(value, right - 4f, y + 12f, size = 12.5f, weight = Weight.SEMIBOLD, color = color, align = Paint.Align.RIGHT)
                y += 22f
            }
            pen.hairline(left + 4f, right - 4f, y + 2f)
            y += 10f
            pen.text("Total", left + 4f, y + 15f, size = 14f, weight = Weight.BOLD, color = Ink.text)
            pen.text(Formatters.formatCurrency(order.totalAmount, cur), right - 4f, y + 15f, size = 15f, weight = Weight.BOLD, color = Ink.text, align = Paint.Align.RIGHT)
            y += 20f
            if (order.balanceDue > 0.009) {
                y += 6f
                pen.text("Balance due", left + 4f, y + 14f, size = 13f, weight = Weight.BOLD, color = Ink.danger)
                pen.text(Formatters.formatCurrency(order.balanceDue, cur), right - 4f, y + 14f, size = 14f, weight = Weight.BOLD, color = Ink.danger, align = Paint.Align.RIGHT)
                y += 20f
            }
            y = boxTop + boxH

            if (order.balanceDue > 0.009 && settings.momoPaymentDetails.isNotBlank()) {
                y += 12f
                y += howToPay(pen, settings.momoPaymentDetails, left, right, y)
            }
            if (order.notes.isNotBlank()) {
                y += 12f
                pen.text("Note", left, y + 11f, size = 10.5f, weight = Weight.SEMIBOLD, color = Ink.subtle)
                y += 16f
                y += pen.paragraph(order.notes, left, y, width, size = 12f, color = Ink.muted, maxLines = 4)
            }

            y += 20f
            y = drawFooter(pen, "Thank you for your business!", left, right, y)
            y + PAD
        }
    }

    /** Draws a customer statement and returns its total height. */
    private fun drawStatement(pen: Pen, customer: CustomerEntity, orders: List<SaleOrderEntity>, settings: AppSettingsEntity): Float {
        val cur = settings.currencySymbol
        val left = MARGIN + PAD
        val right = PAGE_WIDTH - MARGIN - PAD
        val width = right - left
        val recent = orders.sortedByDescending { it.timestamp }.take(10)
        val owes = customer.currentBalance > 0.009

        return pen.card { startY ->
            var y = startY + PAD
            y = drawBusinessHeader(pen, settings, left, right, y)

            y += 16f
            pen.perforation(y)
            y += 18f

            pen.text("ACCOUNT STATEMENT", left, y + 10f, size = 10.5f, weight = Weight.SEMIBOLD, color = Ink.subtle, tracking = 0.08f)
            y += 18f
            y += pen.line(customer.name, left, y + 20f, size = 20f, weight = Weight.BOLD, color = Ink.text, maxWidth = width) + 2f
            val sub = listOf(customer.phone, "As of ${formatReceiptDate(System.currentTimeMillis())}").filter { it.isNotBlank() }.joinToString("  ·  ")
            pen.text(sub, left, y + 13f, size = 11.5f, color = Ink.muted, maxWidth = width)
            y += 26f

            // Balance hero
            val heroH = 70f
            pen.roundRect(left - 8f, y, right + 8f, y + heroH, 18f, if (owes) Ink.dangerSoft else Ink.successSoft)
            pen.text(if (owes) "Balance owed" else "All settled", left + 6f, y + 24f, size = 12f, weight = Weight.SEMIBOLD, color = if (owes) Ink.onDangerSoft else Ink.onSuccessSoft)
            pen.text(Formatters.formatCurrency(customer.currentBalance, cur), left + 6f, y + 54f, size = 24f, weight = Weight.BOLD, color = if (owes) Ink.onDangerSoft else Ink.onSuccessSoft, maxWidth = width - 12f)
            y += heroH + 12f

            // Bought / Paid tiles
            val tileW = (width + 16f - 10f) / 2f
            statTile(pen, "Total bought", Formatters.formatCurrency(customer.totalPurchases, cur), left - 8f, y, tileW)
            statTile(pen, "Total paid", Formatters.formatCurrency(customer.totalPaid, cur), left - 8f + tileW + 10f, y, tileW)
            y += 58f + 18f

            pen.text("RECENT INVOICES", left, y + 10f, size = 10.5f, weight = Weight.SEMIBOLD, color = Ink.subtle, tracking = 0.08f)
            y += 20f
            if (recent.isEmpty()) {
                pen.text("No invoices yet.", left, y + 13f, size = 12.5f, color = Ink.muted)
                y += 22f
            }
            recent.forEachIndexed { i, o ->
                if (i > 0) { pen.hairline(left, right, y); y += 10f }
                val st = statusOf(o)
                pen.text("#${o.invoiceNumber}", left, y + 13f, size = 13f, weight = Weight.SEMIBOLD, color = Ink.text, maxWidth = width * 0.55f)
                pen.text(formatReceiptDate(o.timestamp), left, y + 30f, size = 11f, color = Ink.muted)
                pen.text(Formatters.formatCurrency(o.totalAmount, cur), right, y + 13f, size = 13f, weight = Weight.SEMIBOLD, color = Ink.text, align = Paint.Align.RIGHT)
                val pillLabel = if (st == Status.PAID) st.label else "Owes ${Formatters.formatCurrency(o.balanceDue, cur)}"
                pen.pill(pillLabel, right, y + 20f, st.bg, st.fg, alignRight = true, small = true)
                y += 40f
            }
            if (orders.size > recent.size) {
                pen.text("+ ${orders.size - recent.size} older invoices", left, y + 12f, size = 11f, color = Ink.subtle)
                y += 18f
            }

            if (owes && settings.momoPaymentDetails.isNotBlank()) {
                y += 12f
                y += howToPay(pen, settings.momoPaymentDetails, left, right, y)
            }

            y += 20f
            y = drawFooter(pen, "Thank you for your business!", left, right, y)
            y + PAD
        }
    }

    // ------------------------------------------------------------------
    // Shared blocks
    // ------------------------------------------------------------------

    private fun drawBusinessHeader(pen: Pen, settings: AppSettingsEntity, left: Float, right: Float, top: Float): Float {
        val avatar = 46f
        pen.circle(left + avatar / 2f, top + avatar / 2f, avatar / 2f, Ink.primarySoft)
        pen.text(initials(settings.businessName), left + avatar / 2f, top + avatar / 2f + 6f, size = 16f, weight = Weight.BOLD, color = Ink.onPrimarySoft, align = Paint.Align.CENTER)

        val tx = left + avatar + 12f
        val tw = right - tx
        var y = top + 2f
        y += pen.paragraph(settings.businessName.ifBlank { "My Business" }, tx, y, tw, size = 16f, weight = Weight.BOLD, color = Ink.text, maxLines = 2)
        if (settings.businessTagline.isNotBlank()) {
            y += 1f
            y += pen.paragraph(settings.businessTagline, tx, y, tw, size = 11.5f, color = Ink.muted, maxLines = 2)
        }
        val contact = listOf(settings.businessPhone, settings.businessLocation).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (contact.isNotBlank()) {
            y += 2f
            y += pen.paragraph(contact, tx, y, tw, size = 11f, color = Ink.subtle, maxLines = 2)
        }
        return maxOf(y, top + avatar)
    }

    private fun metaPair(pen: Pen, l1: String, v1: String, x1: Float, y: Float, w: Float, l2: String, v2: String, x2: Float): Float {
        pen.text(l1, x1, y + 10f, size = 10.5f, color = Ink.subtle)
        pen.text(v1, x1, y + 27f, size = 13f, weight = Weight.SEMIBOLD, color = Ink.text, maxWidth = w)
        pen.text(l2, x2, y + 10f, size = 10.5f, color = Ink.subtle)
        pen.text(v2, x2, y + 27f, size = 13f, weight = Weight.SEMIBOLD, color = Ink.text, maxWidth = w)
        return 32f
    }

    private fun itemRow(pen: Pen, item: CartItem, cur: String, left: Float, right: Float, top: Float): Float {
        val amount = Formatters.formatCurrency(item.lineTotal, cur)
        val amountW = pen.measure(amount, 13.5f, Weight.SEMIBOLD)
        val nameW = right - left - amountW - 14f
        pen.text(amount, right, top + 14f, size = 13.5f, weight = Weight.SEMIBOLD, color = Ink.text, align = Paint.Align.RIGHT)
        var h = pen.paragraph(item.productName, left, top, nameW, size = 13.5f, weight = Weight.SEMIBOLD, color = Ink.text, maxLines = 2)
        val unit = if (item.unit.isNotBlank()) " / ${item.unit}" else ""
        h += 2f
        h += pen.paragraph("${qty(item.quantity)} × ${Formatters.formatCurrency(item.unitPrice, cur)}$unit", left, top + h, nameW, size = 11.5f, color = Ink.muted, maxLines = 1)
        return maxOf(h, 18f)
    }

    private fun howToPay(pen: Pen, details: String, left: Float, right: Float, top: Float): Float {
        val inner = right - left - 8f
        val textH = pen.paragraph(details, 0f, 0f, inner, size = 12.5f, weight = Weight.SEMIBOLD, color = Ink.onPrimarySoft, maxLines = 4, measureOnly = true)
        val h = 14f + 14f + 6f + textH + 14f
        pen.roundRect(left - 8f, top, right + 8f, top + h, 16f, Ink.primarySoft)
        pen.text("How to pay", left + 4f, top + 14f + 11f, size = 11f, weight = Weight.SEMIBOLD, color = Ink.primary)
        pen.paragraph(details, left + 4f, top + 14f + 14f + 6f, inner, size = 12.5f, weight = Weight.SEMIBOLD, color = Ink.onPrimarySoft, maxLines = 4)
        return h
    }

    private fun statTile(pen: Pen, label: String, value: String, x: Float, y: Float, w: Float) {
        pen.roundRect(x, y, x + w, y + 58f, 16f, Ink.soft)
        pen.text(label, x + 14f, y + 22f, size = 11f, color = Ink.muted)
        pen.text(value, x + 14f, y + 44f, size = 15f, weight = Weight.BOLD, color = Ink.text, maxWidth = w - 28f)
    }

    private fun drawFooter(pen: Pen, message: String, left: Float, right: Float, top: Float): Float {
        val cx = (left + right) / 2f
        pen.text(message, cx, top + 13f, size = 13f, weight = Weight.SEMIBOLD, color = Ink.text, align = Paint.Align.CENTER)
        pen.text("Generated with BizTrack", cx, top + 31f, size = 10f, color = Ink.subtle, align = Paint.Align.CENTER)
        return top + 36f
    }

    private fun formatReceiptDate(ts: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ts))

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)

    // ------------------------------------------------------------------
    // Drawing primitives. With a null canvas every call only measures.
    // ------------------------------------------------------------------

    private enum class Weight { REGULAR, SEMIBOLD, BOLD }

    /**
     * @param canvas null for the measuring pass.
     * @param pageHeight total height from the measuring pass, so backgrounds can be
     *   drawn before the content that sits on top of them.
     */
    private class Pen(val canvas: Canvas?, val forPdf: Boolean, val pageHeight: Float = 0f) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val typefaces = mapOf(
            Weight.REGULAR to Typeface.create("sans-serif", Typeface.NORMAL),
            Weight.SEMIBOLD to Typeface.create("sans-serif-medium", Typeface.NORMAL),
            Weight.BOLD to Typeface.create("sans-serif", Typeface.BOLD)
        )

        private fun textPaint(size: Float, weight: Weight, color: Int, tracking: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            typeface = typefaces[weight]
            this.color = color
            letterSpacing = tracking
        }

        /**
         * Draws the page and the white card, then the [content]. [content] receives the
         * card's top y and returns the card's bottom y. Returns the full page height.
         */
        fun card(content: (Float) -> Float): Float {
            drawCardBackground(MARGIN, pageHeight - MARGIN)
            return content(MARGIN) + MARGIN
        }

        private fun drawCardBackground(top: Float, bottom: Float) {
            val c = canvas ?: return
            c.drawColor(if (forPdf) Ink.card else Ink.page)
            val rect = RectF(MARGIN, top, PAGE_WIDTH - MARGIN, bottom)
            fill.style = Paint.Style.FILL
            fill.color = Ink.card
            if (!forPdf) fill.setShadowLayer(18f, 0f, 6f, Ink.shadow)
            c.drawRoundRect(rect, 24f, 24f, fill)
            fill.clearShadowLayer()
            if (forPdf) {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 0.75f
                fill.color = Ink.line
                c.drawRoundRect(rect, 24f, 24f, fill)
                fill.style = Paint.Style.FILL
            }
        }

        fun text(
            s: String, x: Float, baseline: Float, size: Float, color: Int,
            weight: Weight = Weight.REGULAR, align: Paint.Align = Paint.Align.LEFT,
            maxWidth: Float = 0f, tracking: Float = 0f
        ) {
            val c = canvas ?: return
            val p = textPaint(size, weight, color, tracking).apply { textAlign = align }
            val str = if (maxWidth > 0f) TextUtils.ellipsize(s, p, maxWidth, TextUtils.TruncateAt.END).toString() else s
            c.drawText(str, x, baseline, p)
        }

        /** Single line; returns its height. */
        fun line(s: String, x: Float, baseline: Float, size: Float, color: Int, weight: Weight = Weight.REGULAR, maxWidth: Float = 0f): Float {
            text(s, x, baseline, size, color, weight, maxWidth = maxWidth)
            return size * 1.15f
        }

        /** Wrapped text block starting at [top]; returns its height. */
        fun paragraph(
            s: String, x: Float, top: Float, width: Float, size: Float, color: Int,
            weight: Weight = Weight.REGULAR, maxLines: Int = Int.MAX_VALUE, measureOnly: Boolean = false
        ): Float {
            if (s.isBlank() || width <= 0f) return 0f
            val p = textPaint(size, weight, color)
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            if (!measureOnly) canvas?.let { c ->
                c.save(); c.translate(x, top); layout.draw(c); c.restore()
            }
            return layout.height.toFloat()
        }

        fun measure(s: String, size: Float, weight: Weight) = textPaint(size, weight, 0).measureText(s)

        fun pill(label: String, x: Float, top: Float, bg: Int, fg: Int, alignRight: Boolean, small: Boolean = false) {
            val c = canvas ?: return
            val size = if (small) 10.5f else 11.5f
            val h = if (small) 18f else 22f
            val w = measure(label, size, Weight.SEMIBOLD) + (if (small) 16f else 20f)
            val l = if (alignRight) x - w else x
            fill.style = Paint.Style.FILL
            fill.color = bg
            c.drawRoundRect(RectF(l, top, l + w, top + h), h / 2f, h / 2f, fill)
            text(label, l + w / 2f, top + h / 2f + size * 0.36f, size, fg, Weight.SEMIBOLD, Paint.Align.CENTER)
        }

        fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
            val c = canvas ?: return
            fill.style = Paint.Style.FILL
            fill.color = color
            c.drawRoundRect(RectF(l, t, r, b), radius, radius, fill)
        }

        fun circle(cx: Float, cy: Float, r: Float, color: Int) {
            val c = canvas ?: return
            fill.style = Paint.Style.FILL
            fill.color = color
            c.drawCircle(cx, cy, r, fill)
        }

        fun hairline(l: Float, r: Float, y: Float) {
            val c = canvas ?: return
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 0.75f
            fill.color = Ink.line
            c.drawLine(l, y, r, y, fill)
            fill.style = Paint.Style.FILL
        }

        /** Dashed tear line with notches cut into the card edges, like a paper receipt. */
        fun perforation(y: Float) {
            val c = canvas ?: return
            val bg = if (forPdf) Ink.card else Ink.page
            fill.style = Paint.Style.FILL
            fill.color = bg
            c.drawCircle(MARGIN, y, 8f, fill)
            c.drawCircle(PAGE_WIDTH - MARGIN, y, 8f, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 1f
            fill.color = Ink.line
            fill.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
            c.drawLine(MARGIN + 14f, y, PAGE_WIDTH - MARGIN - 14f, y, fill)
            fill.pathEffect = null
            fill.style = Paint.Style.FILL
        }
    }

    // ------------------------------------------------------------------
    // Files & sharing
    // ------------------------------------------------------------------

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
        } finally {
            bitmap.recycle()
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
                // Open the customer's chat directly when we know their number; WhatsApp
                // ignores the extra (and shows its contact picker) if it can't match it.
                val waNumber = SmsService.formatGhanaPhoneNumber(recipientPhone)
                if (waNumber.length >= 9) shareIntent.putExtra("jid", "$waNumber@s.whatsapp.net")
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

            val chooser = Intent.createChooser(shareIntent, "Share receipt via…").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share digital receipt file", e)
            Toast.makeText(context, "Could not open share dialog: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
