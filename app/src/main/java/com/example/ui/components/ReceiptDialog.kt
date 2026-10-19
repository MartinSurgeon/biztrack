package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppSettingsEntity
import com.example.data.model.SaleOrderEntity
import com.example.data.remote.SmsService
import com.example.ui.theme.AppTheme
import com.example.ui.theme.WhatsAppSolid
import com.example.util.DigitalReceiptGenerator
import com.example.util.Formatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ShareFormat(val label: String) { IMAGE("Image"), PDF("PDF"), TEXT("Text") }

@Composable
fun ReceiptDialog(
    order: SaleOrderEntity,
    settings: AppSettingsEntity,
    onDismiss: () -> Unit,
    onSendSms: (SaleOrderEntity) -> Unit,
    onEditOrder: ((SaleOrderEntity) -> Unit)? = null,
    onRecordPayment: ((SaleOrderEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val items = remember(order.itemsJson) { Formatters.parseCartItems(order.itemsJson) }
    var format by remember { mutableStateOf(ShareFormat.IMAGE) }
    val targetPhone = order.customerWhatsapp.ifBlank { order.customerPhone }
    val cur = settings.currencySymbol
    val c = MaterialTheme.colorScheme

    val (statusLabel, statusTone) = when {
        order.balanceDue <= 0.009 -> "Paid" to Tone.SUCCESS
        order.amountPaid > 0.009 -> "Part paid" to Tone.WARNING
        else -> "Unpaid" to Tone.DANGER
    }

    fun share(viaWhatsApp: Boolean) {
        when (format) {
            ShareFormat.IMAGE -> {
                val file = DigitalReceiptGenerator.generateReceiptBitmap(context, order, settings)
                if (file == null) Toast.makeText(context, "Could not create the receipt image", Toast.LENGTH_SHORT).show()
                else DigitalReceiptGenerator.shareDigitalFile(context, file, "image/png",
                    "🧾 Receipt #${order.invoiceNumber} from ${settings.businessName}", targetPhone, viaWhatsApp)
            }
            ShareFormat.PDF -> {
                val file = DigitalReceiptGenerator.generateReceiptPdf(context, order, settings)
                if (file == null) Toast.makeText(context, "Could not create the PDF", Toast.LENGTH_SHORT).show()
                else DigitalReceiptGenerator.shareDigitalFile(context, file, "application/pdf",
                    "📄 Receipt #${order.invoiceNumber} from ${settings.businessName}", targetPhone, viaWhatsApp)
            }
            ShareFormat.TEXT -> SmsService.shareViaWhatsApp(context, targetPhone, Formatters.buildWhatsAppReceipt(order, settings))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .clay(RoundedCornerShape(28.dp), c.surface)
                    .clip(RoundedCornerShape(28.dp))
                    .testTag("receipt_dialog_card")
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Title bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Receipt", style = MaterialTheme.typography.headlineSmall, color = c.onSurface)
                        Text("#${order.invoiceNumber}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onEditOrder != null) {
                        CircleIconButton(Icons.Default.Edit, "Edit invoice", { onDismiss(); onEditOrder(order) },
                            modifier = Modifier.testTag("receipt_edit_invoice_button"))
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleIconButton(Icons.Default.Close, "Close receipt", onDismiss, modifier = Modifier.testTag("close_receipt_button"))
                }

                Spacer(Modifier.height(16.dp))

                // Ticket: same structure as the shared image/PDF.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(c.surfaceVariant.copy(alpha = if (AppTheme.status.isDark) 0.6f else 0.7f))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(settings.businessName.ifBlank { "B" }, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(settings.businessName, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val contact = listOf(settings.businessPhone, settings.businessLocation).filter { it.isNotBlank() }.joinToString(" · ")
                            if (contact.isNotBlank()) Text(contact, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    DashedDivider()

                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("Total", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                            Text(Formatters.formatCurrency(order.totalAmount, cur), style = MaterialTheme.typography.headlineMedium, color = c.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (order.balanceDue > 0.009) {
                                Text("Balance due ${Formatters.formatCurrency(order.balanceDue, cur)}", style = MaterialTheme.typography.labelLarge,
                                    color = AppTheme.status.danger)
                            }
                        }
                        StatusBadge(statusLabel, statusTone)
                    }

                    Spacer(Modifier.height(14.dp))
                    Row {
                        MetaCell("Customer", order.customerName.ifBlank { "Walk-in customer" }, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        MetaCell("Date", SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(order.timestamp)), Modifier.weight(1f))
                    }

                    DashedDivider()

                    items.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 1.dp, color = c.outlineVariant)
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(item.productName, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatItemQty(item.quantity)} × ${Formatters.formatCurrency(item.unitPrice, cur)}" + if (item.unit.isNotBlank()) " / ${item.unit}" else "",
                                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(Formatters.formatCurrency(item.lineTotal, cur), style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (order.discountAmount > 0.009) {
                            SummaryLine("Subtotal", Formatters.formatCurrency(items.sumOf { it.lineTotal }, cur))
                            SummaryLine("Discount", "− ${Formatters.formatCurrency(order.discountAmount, cur)}", valueColor = AppTheme.status.warning)
                        }
                        SummaryLine("Paid · ${methodLabel(order.paymentMethod)}", Formatters.formatCurrency(order.amountPaid, cur), valueColor = AppTheme.status.success)
                        HairlineDivider()
                        SummaryLine("Total", Formatters.formatCurrency(order.totalAmount, cur), emphasize = true)
                        if (order.balanceDue > 0.009) {
                            SummaryLine("Balance due", Formatters.formatCurrency(order.balanceDue, cur), valueColor = AppTheme.status.danger)
                        }
                    }

                    if (order.balanceDue > 0.009 && settings.momoPaymentDetails.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Banner("How to pay: ${settings.momoPaymentDetails}", Tone.PRIMARY, icon = Icons.Default.Payments)
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Thank you for your business!", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                if (order.balanceDue > 0.009 && onRecordPayment != null) {
                    Spacer(Modifier.height(12.dp))
                    AppButton(
                        "Record a payment", { onRecordPayment(order) }, variant = ButtonVariant.SUCCESS, icon = Icons.Default.Payments,
                        modifier = Modifier.fillMaxWidth().testTag("receipt_record_installment_button")
                    )
                }

                Spacer(Modifier.height(16.dp))
                Caption("Send as")
                Spacer(Modifier.height(8.dp))
                SegmentedControl(ShareFormat.entries, format, { format = it }, label = { it.label })

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { share(viaWhatsApp = true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(
                            when (format) {
                                ShareFormat.IMAGE -> "share_digital_image_whatsapp_button"
                                ShareFormat.PDF -> "share_pdf_invoice_button"
                                ShareFormat.TEXT -> "share_whatsapp_receipt_button"
                            }
                        ),
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppSolid, contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send on WhatsApp", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("SMS", { onSendSms(order) }, variant = ButtonVariant.SECONDARY, icon = Icons.Default.Sms, height = 46.dp,
                        modifier = Modifier.weight(1f).testTag("send_sms_receipt_button"))
                    AppButton("Other apps", { if (format == ShareFormat.TEXT) format = ShareFormat.IMAGE; share(viaWhatsApp = false) },
                        variant = ButtonVariant.SECONDARY, icon = Icons.Default.Share, height = 46.dp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Dashed tear line, echoing the perforation on the shared receipt. */
@Composable
private fun DashedDivider() {
    val color = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp)) {
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    }
}

private fun formatItemQty(q: Double) = if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()

private fun methodLabel(method: String) = when (method.uppercase()) {
    "CASH" -> "Cash"
    "MOMO" -> "Mobile money"
    "BANK_TRANSFER", "BANK" -> "Bank"
    else -> method.lowercase().replaceFirstChar { it.uppercase() }
}

