package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppSettingsEntity
import com.example.data.model.SaleOrderEntity
import com.example.data.remote.SmsService
import com.example.ui.theme.BrandGreenPrimary
import com.example.ui.theme.DebtRed
import com.example.ui.theme.DebtRedContainer
import com.example.ui.theme.FarmGreen
import com.example.ui.theme.FarmGreenContainer
import com.example.ui.theme.BrandAmberGold
import com.example.ui.theme.BrandAmberGoldContainer
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberContainer
import com.example.ui.theme.WhatsAppGreen
import com.example.util.DigitalReceiptGenerator
import com.example.util.Formatters

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
    var selectedShareFormat by remember { mutableStateOf(0) } // 0 = Digital Image, 1 = PDF Invoice, 2 = WhatsApp Text
    val targetPhone = if (order.customerWhatsapp.isNotBlank()) order.customerWhatsapp else order.customerPhone

    val isPaidInFull = order.balanceDue <= 0
    val isPartiallyPaid = order.balanceDue > 0 && order.amountPaid > 0

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
                    .widthIn(max = 480.dp)
                    .testTag("receipt_dialog_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BrandGreenPrimary.copy(alpha = 0.12f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.ReceiptLong,
                                        contentDescription = "Receipt Icon",
                                        tint = BrandGreenPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Receipt #${order.invoiceNumber}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = Formatters.formatDateShort(order.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (onEditOrder != null) {
                                IconButton(
                                    onClick = {
                                        onDismiss()
                                        onEditOrder(order)
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("receipt_edit_invoice_button")
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit Invoice",
                                        tint = BrandGreenPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("close_receipt_button")
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close Receipt",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Streamlined Receipt Ticket Container
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            // Business & Customer Info
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = settings.businessName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (settings.businessPhone.isNotBlank()) {
                                        Text(
                                            text = "Tel: ${settings.businessPhone}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = order.customerName,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )

                            // Item Rows
                            items.forEach { item ->
                                val qtyFormatted = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.productName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "$qtyFormatted ${item.unit} @ ${Formatters.formatCurrency(item.unitPrice, settings.currencySymbol)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = Formatters.formatCurrency(item.lineTotal, settings.currencySymbol),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )

                            // Financial Summary
                            val subtotal = items.sumOf { it.lineTotal }
                            if (order.discountAmount > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Subtotal:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = Formatters.formatCurrency(subtotal, settings.currencySymbol),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Sell,
                                            contentDescription = null,
                                            tint = WarningAmber,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Discount Granted:",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = WarningAmber
                                        )
                                    }
                                    Text(
                                        text = "-${Formatters.formatCurrency(order.discountAmount, settings.currencySymbol)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = WarningAmber
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (order.discountAmount > 0) "Net Total Bill:" else "Total Bill:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = Formatters.formatCurrency(order.totalAmount, settings.currencySymbol),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Amount Paid:", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = Formatters.formatCurrency(order.amountPaid, settings.currencySymbol),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FarmGreen
                                )
                            }

                            if (order.balanceDue > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Balance Due:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = DebtRed)
                                    Text(
                                        text = Formatters.formatCurrency(order.balanceDue, settings.currencySymbol),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = DebtRed
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Payment Status Banner
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isPaidInFull) FarmGreenContainer else DebtRedContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isPaidInFull) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isPaidInFull) FarmGreen else DebtRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isPaidInFull) "PAID IN FULL" else "CREDIT SALE • BALANCE DUE: ${Formatters.formatCurrency(order.balanceDue, settings.currencySymbol)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPaidInFull) FarmGreen else DebtRed
                                    )
                                }
                            }

                            // Quick Installment Action if owing
                            if (order.balanceDue > 0 && onRecordPayment != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { onRecordPayment(order) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .testTag("receipt_record_installment_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DebtRed),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Record Installment Payment", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Thank you for choosing our farm Products, we look forward to serving you again!",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Share Format Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedShareFormat == 0,
                            onClick = { selectedShareFormat = 0 },
                            label = { Text("🖼 Image", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedShareFormat == 1,
                            onClick = { selectedShareFormat = 1 },
                            label = { Text("📄 PDF", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedShareFormat == 2,
                            onClick = { selectedShareFormat = 2 },
                            label = { Text("💬 Text", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Primary WhatsApp Share Button
                    Button(
                        onClick = {
                            when (selectedShareFormat) {
                                0 -> {
                                    val imageFile = DigitalReceiptGenerator.generateReceiptBitmap(context, order, settings)
                                    if (imageFile != null) {
                                        val caption = "🧾 Receipt #${order.invoiceNumber} from ${settings.businessName}"
                                        DigitalReceiptGenerator.shareDigitalFile(
                                            context = context,
                                            file = imageFile,
                                            mimeType = "image/png",
                                            captionText = caption,
                                            recipientPhone = targetPhone,
                                            preferWhatsApp = true
                                        )
                                    } else {
                                        Toast.makeText(context, "Could not create receipt image", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                1 -> {
                                    val pdfFile = DigitalReceiptGenerator.generateReceiptPdf(context, order, settings)
                                    if (pdfFile != null) {
                                        val caption = "📄 PDF Receipt #${order.invoiceNumber} - ${settings.businessName}"
                                        DigitalReceiptGenerator.shareDigitalFile(
                                            context = context,
                                            file = pdfFile,
                                            mimeType = "application/pdf",
                                            captionText = caption,
                                            recipientPhone = targetPhone,
                                            preferWhatsApp = true
                                        )
                                    } else {
                                        Toast.makeText(context, "Could not create PDF", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                2 -> {
                                    val whatsappText = Formatters.buildWhatsAppReceipt(order, settings)
                                    SmsService.shareViaWhatsApp(context, targetPhone, whatsappText)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag(
                                when (selectedShareFormat) {
                                    0 -> "share_digital_image_whatsapp_button"
                                    1 -> "share_pdf_invoice_button"
                                    else -> "share_whatsapp_receipt_button"
                                }
                            ),
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = when (selectedShareFormat) {
                                0 -> Icons.Default.Image
                                1 -> Icons.Default.PictureAsPdf
                                else -> Icons.Default.Send
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (selectedShareFormat) {
                                0 -> "Share Image on WhatsApp"
                                1 -> "Share PDF on WhatsApp"
                                else -> "Send Text on WhatsApp"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary Action Row (SMS + Generic Share)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSendSms(order) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("send_sms_receipt_button"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Sms, contentDescription = null, tint = BrandGreenPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SMS Receipt", color = BrandGreenPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }

                        OutlinedButton(
                            onClick = {
                                if (selectedShareFormat == 1) {
                                    val pdfFile = DigitalReceiptGenerator.generateReceiptPdf(context, order, settings)
                                    if (pdfFile != null) {
                                        DigitalReceiptGenerator.shareDigitalFile(
                                            context = context,
                                            file = pdfFile,
                                            mimeType = "application/pdf",
                                            captionText = "Invoice #${order.invoiceNumber}",
                                            recipientPhone = targetPhone,
                                            preferWhatsApp = false
                                        )
                                    }
                                } else {
                                    val imageFile = DigitalReceiptGenerator.generateReceiptBitmap(context, order, settings)
                                    if (imageFile != null) {
                                        DigitalReceiptGenerator.shareDigitalFile(
                                            context = context,
                                            file = imageFile,
                                            mimeType = "image/png",
                                            captionText = "Receipt #${order.invoiceNumber}",
                                            recipientPhone = targetPhone,
                                            preferWhatsApp = false
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share via...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

