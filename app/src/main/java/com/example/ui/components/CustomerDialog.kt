package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.CustomerEntity
import com.example.ui.theme.BrandGreenPrimary
import com.example.ui.theme.DebtRed
import com.example.ui.theme.DebtRedContainer
import com.example.util.Formatters

@Composable
fun AddCustomerDialog(
    onDismiss: () -> Unit,
    onSaveCustomer: (CustomerEntity) -> Unit
) {
    CustomerFormDialog(
        customer = null,
        currencySymbol = "GH₵",
        onDismiss = onDismiss,
        onSaveCustomer = onSaveCustomer,
        onDeleteCustomer = {}
    )
}

@Composable
fun CustomerFormDialog(
    customer: CustomerEntity? = null,
    currencySymbol: String = "GH₵",
    onDismiss: () -> Unit,
    onSaveCustomer: (CustomerEntity) -> Unit,
    onDeleteCustomer: ((CustomerEntity) -> Unit)? = null
) {
    val isEdit = customer != null && customer.id > 0
    var name by remember(customer) { mutableStateOf(customer?.name ?: "") }
    var phone by remember(customer) { mutableStateOf(customer?.phone ?: "") }
    var whatsapp by remember(customer) { mutableStateOf(customer?.whatsapp ?: "") }
    var notes by remember(customer) { mutableStateOf(customer?.notes ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                    .testTag("customer_form_dialog_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
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
                                color = BrandGreenPrimary.copy(alpha = 0.12f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = "Customer Icon",
                                        tint = BrandGreenPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isEdit) "Edit Customer Profile" else "Add New Customer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isEdit) "Update contact & notes" else "Store phone number for receipts & SMS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_customer_dialog_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Dialog",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // If existing debtor, show current balance banner
                    if (isEdit && customer != null && customer.currentBalance > 0) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DebtRedContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = DebtRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Current Outstanding Debt: ${Formatters.formatCurrency(customer.currentBalance, currencySymbol)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = DebtRed
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Customer Name Field
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Customer Name *") },
                        placeholder = { Text("e.g. Kwame Mensah") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = BrandGreenPrimary)
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            keyboardType = KeyboardType.Text
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_customer_name_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Primary Phone Field
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number * (for SMS)") },
                        placeholder = { Text("e.g. 0244123456") },
                        leadingIcon = {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = BrandGreenPrimary)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_customer_phone_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // WhatsApp Number Field
                    OutlinedTextField(
                        value = whatsapp,
                        onValueChange = { whatsapp = it },
                        label = { Text("WhatsApp Number (Optional)") },
                        placeholder = { Text("Leave blank if same as phone") },
                        leadingIcon = {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Notes Field
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes / Location (Optional)") },
                        placeholder = { Text("e.g. Buys 5 crates weekly / Madina Market") },
                        leadingIcon = {
                            Icon(Icons.Default.Notes, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isEdit && onDeleteCustomer != null) {
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("dialog_delete_customer_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DebtRed)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Button(
                            onClick = {
                                val target = customer ?: CustomerEntity(
                                    name = name.trim(),
                                    phone = phone.trim(),
                                    whatsapp = if (whatsapp.isNotBlank()) whatsapp.trim() else phone.trim(),
                                    notes = notes.trim()
                                )
                                onSaveCustomer(
                                    target.copy(
                                        name = name.trim(),
                                        phone = phone.trim(),
                                        whatsapp = if (whatsapp.isNotBlank()) whatsapp.trim() else phone.trim(),
                                        notes = notes.trim()
                                    )
                                )
                            },
                            enabled = name.isNotBlank() && phone.isNotBlank(),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(50.dp)
                                .testTag("dialog_save_customer_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isEdit) "Save Changes" else "Save Customer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Deletion Confirmation Dialog
    if (showDeleteConfirm && customer != null && onDeleteCustomer != null) {
        val warningDebt = if (customer.currentBalance > 0) {
            "\n\n⚠️ WARNING: This customer has an active unpaid debt of ${Formatters.formatCurrency(customer.currentBalance, currencySymbol)}."
        } else ""

        ConfirmationDialog(
            title = "Delete Customer?",
            message = "Are you sure you want to delete '${customer.name}' from your customer records?$warningDebt",
            confirmButtonText = "Delete Customer",
            cancelButtonText = "Keep Customer",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                onDeleteCustomer(customer)
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}
