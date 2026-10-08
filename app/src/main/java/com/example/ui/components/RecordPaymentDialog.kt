package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppSettingsEntity
import com.example.data.model.CustomerEntity
import com.example.data.model.PaymentMethod
import com.example.data.model.SaleOrderEntity
import com.example.ui.theme.BrandGreenPrimary
import com.example.ui.theme.DebtRed
import com.example.ui.theme.DebtRedContainer
import com.example.ui.theme.FarmGreen
import com.example.ui.theme.FarmGreenContainer
import com.example.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordPaymentDialog(
    customer: CustomerEntity,
    settings: AppSettingsEntity,
    order: SaleOrderEntity? = null,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, method: PaymentMethod, notes: String, sendSms: Boolean) -> Unit
) {
    val targetDebt = if (order != null) order.balanceDue else customer.currentBalance
    var amountText by remember(targetDebt) {
        mutableStateOf(if (targetDebt > 0) (if (targetDebt % 1.0 == 0.0) targetDebt.toInt().toString() else targetDebt.toString()) else "")
    }
    var selectedMethod by remember { mutableStateOf(PaymentMethod.MOMO) }
    var notes by remember(order) {
        mutableStateOf(if (order != null) "Payment for Invoice #${order.invoiceNumber}" else "")
    }
    var sendSmsAck by remember(settings.smsAutoSendOnPayment) { mutableStateOf(settings.smsAutoSendOnPayment) }

    val amountDouble = amountText.toDoubleOrNull() ?: 0.0
    val actualPayment = if (targetDebt > 0) minOf(amountDouble, targetDebt) else amountDouble
    val remainingAfterPayment = (targetDebt - amountDouble).coerceAtLeast(0.0)
    val isOverpayment = amountDouble > targetDebt && targetDebt > 0
    val isFullyPaid = remainingAfterPayment <= 0.0 && amountDouble > 0

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
                    .testTag("record_payment_dialog_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = FarmGreen.copy(alpha = 0.12f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Payment,
                                        contentDescription = "Payment Icon",
                                        tint = FarmGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (order != null) "Pay Invoice #${order.invoiceNumber}" else "Record Debt Payment",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = customer.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_payment_dialog_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Dialog",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Current Balance Card
                    Surface(
                        color = DebtRedContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (order != null) "Invoice Balance Due" else "Current Debt",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DebtRed,
                                    fontWeight = FontWeight.Bold
                                )
                                if (order != null && customer.currentBalance != order.balanceDue) {
                                    Text(
                                        text = "Total debt: ${Formatters.formatCurrency(customer.currentBalance, settings.currencySymbol)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                } else if (customer.phone.isNotBlank()) {
                                    Text(
                                        text = customer.phone,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = Formatters.formatCurrency(targetDebt, settings.currencySymbol),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = DebtRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Amount Input
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Payment Amount (${settings.currencySymbol})") },
                        placeholder = { Text("0.00") },
                        leadingIcon = {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = FarmGreen)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("payment_amount_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Fill Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Full balance
                        FilterChip(
                            selected = amountDouble == customer.currentBalance && customer.currentBalance > 0,
                            onClick = {
                                val bal = customer.currentBalance
                                amountText = if (bal % 1.0 == 0.0) bal.toInt().toString() else String.format("%.2f", bal)
                            },
                            label = { Text("Full Debt", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FarmGreen, selectedLabelColor = Color.White)
                        )

                        // 50% Half
                        if (customer.currentBalance > 1) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val half = customer.currentBalance / 2.0
                                    amountText = if (half % 1.0 == 0.0) half.toInt().toString() else String.format("%.2f", half)
                                },
                                label = { Text("50% Half", style = MaterialTheme.typography.labelSmall) }
                            )
                        }

                        // GH₵100 Preset
                        if (customer.currentBalance >= 100) {
                            FilterChip(
                                selected = amountText == "100",
                                onClick = { amountText = "100" },
                                label = { Text("100", style = MaterialTheme.typography.labelSmall) }
                            )
                        }

                        // GH₵50 Preset
                        if (customer.currentBalance >= 50 && customer.currentBalance != 100.0) {
                            FilterChip(
                                selected = amountText == "50",
                                onClick = { amountText = "50" },
                                label = { Text("50", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Live Balance Preview Card
                    Surface(
                        color = if (isFullyPaid) FarmGreenContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isFullyPaid) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (isFullyPaid) FarmGreen else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isFullyPaid) "Debt Cleared" else "New Debt Balance",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = if (amountDouble <= 0) {
                                    Formatters.formatCurrency(customer.currentBalance, settings.currencySymbol)
                                } else {
                                    Formatters.formatCurrency(remainingAfterPayment, settings.currencySymbol)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isFullyPaid) FarmGreen else if (remainingAfterPayment > 0) DebtRed else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Payment Method Selection
                    Text(
                        text = "Payment Method",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PaymentMethod.values().forEach { method ->
                            FilterChip(
                                selected = selectedMethod == method,
                                onClick = { selectedMethod = method },
                                label = { Text(method.displayName.substringBefore(" ("), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BrandGreenPrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Notes / Reference Field
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Reference / Note (Optional)") },
                        placeholder = { Text("e.g. MoMo Ref ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // SMS Toggle Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = sendSmsAck,
                            onCheckedChange = { sendSmsAck = it },
                            modifier = Modifier.testTag("send_sms_ack_checkbox")
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Send SMS Payment Receipt to customer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                if (actualPayment > 0) {
                                    onConfirm(actualPayment, selectedMethod, notes, sendSmsAck)
                                }
                            },
                            enabled = actualPayment > 0,
                            modifier = Modifier
                                .weight(1.6f)
                                .height(50.dp)
                                .testTag("confirm_payment_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FarmGreen)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (actualPayment > 0) "Save Payment" else "Enter Amount",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

