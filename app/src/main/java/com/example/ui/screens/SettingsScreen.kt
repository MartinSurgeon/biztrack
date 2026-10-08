package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettingsEntity
import com.example.data.model.BackupData
import com.example.data.model.BackupFileInfo
import com.example.data.model.RestoreResult
import com.example.data.remote.CloudSyncResult
import com.example.data.remote.SmsBalanceResult
import com.example.data.remote.SmsSendResult
import com.example.ui.theme.BrandGreenPrimary
import com.example.ui.theme.DebtRed
import com.example.ui.theme.FarmGreen
import com.example.util.DatabaseBackupHelper
import com.example.util.Formatters
import java.io.File

enum class SettingsTab(val title: String, val icon: ImageVector, val subtitle: String) {
    PROFILE("Profile", Icons.Default.Storefront, "Business details & MoMo"),
    BACKUP("Backup & Sync", Icons.Default.CloudSync, "Cloud sync & Phone backups"),
    SMS("SMS Gateway", Icons.Default.Sms, "SMSOnlineGH & Auto-alerts")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettingsEntity,
    onSaveSettings: (AppSettingsEntity) -> Unit,
    onTestSms: ((testPhone: String, (SmsSendResult) -> Unit) -> Unit)? = null,
    onCheckBalance: ((apiKey: String, (SmsBalanceResult) -> Unit) -> Unit)? = null,
    isTestingSms: Boolean = false,
    lastBackupTime: Long = 0L,
    lastBackupStatus: String = "Auto-backup scheduled",
    lastBackupCount: Int = 0,
    savedBackupFiles: List<BackupFileInfo> = emptyList(),
    isBackingUp: Boolean = false,
    isRestoring: Boolean = false,
    onManualBackup: ((onSuccess: (File, BackupData) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    onRestoreUri: ((Uri, onResult: (RestoreResult) -> Unit) -> Unit)? = null,
    onRestoreFile: ((File, onResult: (RestoreResult) -> Unit) -> Unit)? = null,
    onSaveBackupToUri: ((Uri, BackupData, onResult: (Boolean) -> Unit) -> Unit)? = null,
    onRefreshBackups: (() -> Unit)? = null,
    // Cloud Sync parameters
    isCloudSyncing: Boolean = false,
    cloudSyncStatusMsg: String? = null,
    onSyncToCloud: ((url: String, key: String, (CloudSyncResult) -> Unit) -> Unit)? = null,
    onRestoreFromCloud: ((url: String, key: String, (RestoreResult) -> Unit) -> Unit)? = null,
    onTestCloudConnection: ((url: String, key: String, (CloudSyncResult) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(SettingsTab.PROFILE) }

    // Form states
    var businessName by remember(settings) { mutableStateOf(settings.businessName) }
    var businessTagline by remember(settings) { mutableStateOf(settings.businessTagline) }
    var businessPhone by remember(settings) { mutableStateOf(settings.businessPhone) }
    var momoPaymentDetails by remember(settings) { mutableStateOf(settings.momoPaymentDetails) }
    var currencySymbol by remember(settings) { mutableStateOf(settings.currencySymbol) }

    // Cloud Sync fields
    var cloudSyncUrl by remember(settings) { mutableStateOf(settings.cloudSyncUrl) }
    var cloudSyncSecretKey by remember(settings) { mutableStateOf(settings.cloudSyncSecretKey) }
    var cloudAutoSyncOnSale by remember(settings) { mutableStateOf(settings.cloudAutoSyncOnSale) }
    var showSecretKey by remember { mutableStateOf(false) }
    var testCloudResultStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTestingCloudConn by remember { mutableStateOf(false) }
    var showSetupGuideDialog by remember { mutableStateOf(false) }
    var showConfirmCloudRestoreDialog by remember { mutableStateOf(false) }

    // SMS fields
    var smsApiKey by remember(settings) { mutableStateOf(settings.smsApiKey) }
    var smsSenderId by remember(settings) { mutableStateOf(settings.smsSenderId) }
    var smsAutoSendOnSale by remember(settings) { mutableStateOf(settings.smsAutoSendOnSale) }
    var smsAutoSendOnPayment by remember(settings) { mutableStateOf(settings.smsAutoSendOnPayment) }
    var showApiKey by remember { mutableStateOf(false) }
    var testPhoneInput by remember { mutableStateOf("") }
    var testResultStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isCheckingBalance by remember { mutableStateOf(false) }
    var balanceResultText by remember { mutableStateOf<String?>(null) }

    // Backup dialog states
    var pendingBackupDataForSave by remember { mutableStateOf<BackupData?>(null) }
    var recentBackupSuccessData by remember { mutableStateOf<Pair<File, BackupData>?>(null) }
    var fileToConfirmRestore by remember { mutableStateOf<File?>(null) }
    var uriToConfirmRestore by remember { mutableStateOf<Uri?>(null) }

    // Track unsaved changes for prominent feedback
    val hasUnsavedChanges = remember(
        businessName, businessTagline, businessPhone, momoPaymentDetails, currencySymbol,
        cloudSyncUrl, cloudSyncSecretKey, cloudAutoSyncOnSale,
        smsApiKey, smsSenderId, smsAutoSendOnSale, smsAutoSendOnPayment,
        settings
    ) {
        businessName.trim() != settings.businessName ||
        businessTagline.trim() != settings.businessTagline ||
        businessPhone.trim() != settings.businessPhone ||
        momoPaymentDetails.trim() != settings.momoPaymentDetails ||
        currencySymbol.trim() != settings.currencySymbol ||
        cloudSyncUrl.trim() != settings.cloudSyncUrl ||
        cloudSyncSecretKey.trim() != settings.cloudSyncSecretKey ||
        cloudAutoSyncOnSale != settings.cloudAutoSyncOnSale ||
        smsApiKey.trim() != settings.smsApiKey ||
        smsSenderId.trim() != settings.smsSenderId ||
        smsAutoSendOnSale != settings.smsAutoSendOnSale ||
        smsAutoSendOnPayment != settings.smsAutoSendOnPayment
    }

    val currentSettingsSnapshot = remember(
        businessName, businessTagline, businessPhone, momoPaymentDetails, currencySymbol,
        cloudSyncUrl, cloudSyncSecretKey, cloudAutoSyncOnSale,
        smsApiKey, smsSenderId, smsAutoSendOnSale, smsAutoSendOnPayment,
        settings
    ) {
        settings.copy(
            businessName = businessName.trim(),
            businessTagline = businessTagline.trim(),
            businessPhone = businessPhone.trim(),
            momoPaymentDetails = momoPaymentDetails.trim(),
            smsApiKey = smsApiKey.trim(),
            smsSenderId = smsSenderId.trim(),
            smsAutoSendOnSale = smsAutoSendOnSale,
            smsAutoSendOnPayment = smsAutoSendOnPayment,
            currencySymbol = currencySymbol.trim(),
            cloudSyncUrl = cloudSyncUrl.trim(),
            cloudSyncSecretKey = cloudSyncSecretKey.trim(),
            cloudAutoSyncOnSale = cloudAutoSyncOnSale
        )
    }

    // Document Launchers
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && pendingBackupDataForSave != null) {
            onSaveBackupToUri?.invoke(uri, pendingBackupDataForSave!!) {
                pendingBackupDataForSave = null
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            uriToConfirmRestore = uri
        }
    }

    Scaffold(
        bottomBar = {
            // High-visibility persistent Save Bar adhering to Fitts's Law & Peak-End Rule
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasUnsavedChanges) "Unsaved changes" else "All settings synced",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (hasUnsavedChanges) Color(0xFFD97706) else FarmGreen
                        )
                        Text(
                            text = if (hasUnsavedChanges) "Tap save to apply your updates" else "Last saved to device storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            onSaveSettings(currentSettingsSnapshot)
                            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("save_settings_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasUnsavedChanges) BrandGreenPrimary else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (hasUnsavedChanges) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save Settings",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Settings & Integrations",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = selectedTab.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3 Clean Navigation Tabs (Hick's & Miller's Law: 3 distinct options)
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = BrandGreenPrimary,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                                color = BrandGreenPrimary,
                                height = 3.dp
                            )
                        },
                        divider = {}
                    ) {
                        SettingsTab.values().forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (selectedTab == tab) BrandGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tab.title,
                                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                },
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                }
            }

            // Tab Content with Smooth Scrolling
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (selectedTab) {
                        SettingsTab.PROFILE -> {
                            ProfileTabContent(
                                businessName = businessName,
                                onBusinessNameChange = { businessName = it },
                                businessTagline = businessTagline,
                                onBusinessTaglineChange = { businessTagline = it },
                                businessPhone = businessPhone,
                                onBusinessPhoneChange = { businessPhone = it },
                                momoPaymentDetails = momoPaymentDetails,
                                onMomoPaymentDetailsChange = { momoPaymentDetails = it },
                                currencySymbol = currencySymbol,
                                onCurrencySymbolChange = { currencySymbol = it }
                            )
                        }
                        SettingsTab.BACKUP -> {
                            BackupTabContent(
                                context = context,
                                settings = settings,
                                cloudSyncUrl = cloudSyncUrl,
                                onCloudSyncUrlChange = {
                                    cloudSyncUrl = it
                                    testCloudResultStatus = null
                                },
                                cloudSyncSecretKey = cloudSyncSecretKey,
                                onCloudSyncSecretKeyChange = {
                                    cloudSyncSecretKey = it
                                    testCloudResultStatus = null
                                },
                                showSecretKey = showSecretKey,
                                onToggleShowSecretKey = { showSecretKey = !showSecretKey },
                                isTestingCloudConn = isTestingCloudConn,
                                testCloudResultStatus = testCloudResultStatus,
                                isCloudSyncing = isCloudSyncing,
                                onTestCloudConnection = {
                                    if (cloudSyncUrl.isBlank()) {
                                        testCloudResultStatus = Pair(false, "Please enter your Sync URL first")
                                        return@BackupTabContent
                                    }
                                    isTestingCloudConn = true
                                    testCloudResultStatus = null
                                    onTestCloudConnection?.invoke(cloudSyncUrl.trim(), cloudSyncSecretKey.trim()) { res ->
                                        isTestingCloudConn = false
                                        testCloudResultStatus = Pair(res.success, res.message)
                                    }
                                },
                                onSyncToCloud = {
                                    onSyncToCloud?.invoke(cloudSyncUrl.trim(), cloudSyncSecretKey.trim()) { res ->
                                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onRequestCloudRestore = { showConfirmCloudRestoreDialog = true },
                                onShowSetupGuide = { showSetupGuideDialog = true },
                                lastBackupTime = lastBackupTime,
                                isBackingUp = isBackingUp,
                                isRestoring = isRestoring,
                                onManualBackup = {
                                    onManualBackup?.invoke(
                                        { file, backupData ->
                                            recentBackupSuccessData = Pair(file, backupData)
                                        },
                                        { errorMsg ->
                                            Toast.makeText(context, "Backup failed: $errorMsg", Toast.LENGTH_LONG).show()
                                        }
                                    )
                                },
                                onOpenFilePicker = {
                                    openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                savedBackupFiles = savedBackupFiles,
                                onConfirmFileRestore = { file -> fileToConfirmRestore = file }
                            )
                        }
                        SettingsTab.SMS -> {
                            SmsTabContent(
                                context = context,
                                smsApiKey = smsApiKey,
                                onSmsApiKeyChange = {
                                    smsApiKey = it.trim()
                                    balanceResultText = null
                                },
                                showApiKey = showApiKey,
                                onToggleShowApiKey = { showApiKey = !showApiKey },
                                isCheckingBalance = isCheckingBalance,
                                balanceResultText = balanceResultText,
                                onCheckBalance = {
                                    if (onCheckBalance != null && smsApiKey.isNotBlank()) {
                                        isCheckingBalance = true
                                        balanceResultText = null
                                        onCheckBalance(smsApiKey) { res ->
                                            isCheckingBalance = false
                                            balanceResultText = when (res) {
                                                is SmsBalanceResult.Success -> "Units: ${res.balanceText}"
                                                is SmsBalanceResult.Failure -> "Failed: ${res.errorMessage}"
                                            }
                                        }
                                    }
                                },
                                smsSenderId = smsSenderId,
                                onSmsSenderIdChange = { smsSenderId = it.take(11) },
                                smsAutoSendOnSale = smsAutoSendOnSale,
                                onToggleAutoSendSale = { smsAutoSendOnSale = it },
                                smsAutoSendOnPayment = smsAutoSendOnPayment,
                                onToggleAutoSendPayment = { smsAutoSendOnPayment = it },
                                testPhoneInput = testPhoneInput,
                                onTestPhoneInputChange = { testPhoneInput = it },
                                isTestingSms = isTestingSms,
                                testResultStatus = testResultStatus,
                                onSendTestSms = {
                                    if (onTestSms != null && testPhoneInput.isNotBlank() && smsApiKey.isNotBlank()) {
                                        testResultStatus = null
                                        onTestSms(testPhoneInput) { result ->
                                            when (result) {
                                                is SmsSendResult.Success -> testResultStatus = Pair(true, result.responseMessage)
                                                is SmsSendResult.Failure -> testResultStatus = Pair(false, result.errorMessage)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // ==========================================
    // Modals & Dialogs
    // ==========================================

    // 1. Successful Backup Details Dialog
    recentBackupSuccessData?.let { (file, data) ->
        AlertDialog(
            onDismissRequest = { recentBackupSuccessData = null },
            icon = {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FarmGreen, modifier = Modifier.size(36.dp))
            },
            title = {
                Text("Database Backup Complete", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your farm database has been safely exported to your phone's storage:")
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("• Products: ${data.summary.totalProducts}", style = MaterialTheme.typography.bodySmall)
                            Text("• Customers: ${data.summary.totalCustomers}", style = MaterialTheme.typography.bodySmall)
                            Text("• Sales Invoices: ${data.summary.totalSalesOrders}", style = MaterialTheme.typography.bodySmall)
                            Text("• Payment Records: ${data.summary.totalPayments}", style = MaterialTheme.typography.bodySmall)
                            Text("• Inventory History: ${data.summary.totalInventoryLogs}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        text = "File: ${file.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shareIntent = DatabaseBackupHelper.createShareIntent(context, file)
                        context.startActivity(Intent.createChooser(shareIntent, "Share Farm Database Backup"))
                        recentBackupSuccessData = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share JSON")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            pendingBackupDataForSave = data
                            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            createDocumentLauncher.launch("naadriel_farm_backup_$dateStr.json")
                            recentBackupSuccessData = null
                        }
                    ) {
                        Text("Save to Folder")
                    }
                    TextButton(onClick = { recentBackupSuccessData = null }) {
                        Text("Done")
                    }
                }
            }
        )
    }

    // 2. Confirm Restore from Local File Dialog
    fileToConfirmRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToConfirmRestore = null },
            icon = {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            },
            title = {
                Text("Restore Database from File?", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Restoring from: ${file.name}")
                    Text(
                        "This will merge and update all products, customer ledgers, sales orders, and settings in your active database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetFile = file
                        fileToConfirmRestore = null
                        onRestoreFile?.invoke(targetFile) { result ->
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                ) {
                    Text("Confirm Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToConfirmRestore = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Confirm Restore from External URI Dialog
    uriToConfirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { uriToConfirmRestore = null },
            icon = {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            },
            title = {
                Text("Restore Database from Selected File?", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("You selected a backup file to import.")
                    Text(
                        "This will read the JSON backup and restore all products, customer balances, sales records, and settings into your database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetUri = uri
                        uriToConfirmRestore = null
                        onRestoreUri?.invoke(targetUri) { result ->
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                ) {
                    Text("Confirm Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { uriToConfirmRestore = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Confirm Restore from Cloud MySQL Dialog
    if (showConfirmCloudRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmCloudRestoreDialog = false },
            icon = {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(36.dp))
            },
            title = {
                Text("Pull & Restore from Cloud MySQL?", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This will fetch all products, customers, debt balances, and sales records from your Namecheap MySQL server and merge them into this phone's database.")
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEFF6FF)
                    ) {
                        Text(
                            text = "Target: ${cloudSyncUrl.ifBlank { "Not set" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmCloudRestoreDialog = false
                        onRestoreFromCloud?.invoke(cloudSyncUrl.trim(), cloudSyncSecretKey.trim()) { result ->
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Start Cloud Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmCloudRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 5. Namecheap Setup Guide Dialog
    if (showSetupGuideDialog) {
        AlertDialog(
            onDismissRequest = { showSetupGuideDialog = false },
            icon = {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(36.dp))
            },
            title = {
                Text("Namecheap cPanel Setup Guide", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Follow these 3 quick steps on your Namecheap cPanel account:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("1. Create MySQL Database", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Log into cPanel -> 'MySQL Databases'. Create a database and user, grant all privileges, and note the username & password.", style = MaterialTheme.typography.bodySmall)

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("2. Import schema.sql", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Open phpMyAdmin in cPanel, select your new database, and import schema.sql (provided in project assets).", style = MaterialTheme.typography.bodySmall)

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("3. Upload sync.php", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Upload sync.php to your website public_html/api/sync.php. Edit the DB credentials and \$API_SECRET_KEY at the top of the file.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Text(
                        "Then enter your sync URL (e.g., https://yourdomain.com/api/sync.php) and secret key in the Cloud Sync card and tap 'Test API'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSetupGuideDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                ) {
                    Text("Got it")
                }
            }
        )
    }
}

// ==========================================
// 1. PROFILE TAB CONTENT
// ==========================================
@Composable
private fun ProfileTabContent(
    businessName: String,
    onBusinessNameChange: (String) -> Unit,
    businessTagline: String,
    onBusinessTaglineChange: (String) -> Unit,
    businessPhone: String,
    onBusinessPhoneChange: (String) -> Unit,
    momoPaymentDetails: String,
    onMomoPaymentDetailsChange: (String) -> Unit,
    currencySymbol: String,
    onCurrencySymbolChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(BrandGreenPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Storefront, contentDescription = null, tint = BrandGreenPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Farm & Business Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Appears on receipts, invoices & debtor reminders",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            OutlinedTextField(
                value = businessName,
                onValueChange = onBusinessNameChange,
                label = { Text("Business Name") },
                placeholder = { Text("e.g. BizTrack Store") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = businessTagline,
                onValueChange = onBusinessTaglineChange,
                label = { Text("Tagline / Slogan") },
                placeholder = { Text("e.g. Chicken at its best") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.FormatQuote, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = businessPhone,
                    onValueChange = onBusinessPhoneChange,
                    label = { Text("Contact Phone") },
                    placeholder = { Text("024 000 0000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    modifier = Modifier.weight(1.5f),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = currencySymbol,
                    onValueChange = onCurrencySymbolChange,
                    label = { Text("Currency") },
                    placeholder = { Text("GH₵") },
                    singleLine = true,
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            OutlinedTextField(
                value = momoPaymentDetails,
                onValueChange = onMomoPaymentDetailsChange,
                label = { Text("Mobile Money / Payment Instructions") },
                placeholder = { Text("e.g. MTN MoMo: 0244XXXXXX (BizTrack Store)") },
                supportingText = { Text("Included automatically in customer receipts & debt reminders") },
                singleLine = false,
                maxLines = 3,
                leadingIcon = { Icon(Icons.Default.Payment, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("momo_details_input"),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

// ==========================================
// 2. BACKUP & CLOUD SYNC TAB CONTENT
// ==========================================
@Composable
private fun BackupTabContent(
    context: android.content.Context,
    settings: AppSettingsEntity,
    cloudSyncUrl: String,
    onCloudSyncUrlChange: (String) -> Unit,
    cloudSyncSecretKey: String,
    onCloudSyncSecretKeyChange: (String) -> Unit,
    showSecretKey: Boolean,
    onToggleShowSecretKey: () -> Unit,
    isTestingCloudConn: Boolean,
    testCloudResultStatus: Pair<Boolean, String>?,
    isCloudSyncing: Boolean,
    onTestCloudConnection: () -> Unit,
    onSyncToCloud: () -> Unit,
    onRequestCloudRestore: () -> Unit,
    onShowSetupGuide: () -> Unit,
    lastBackupTime: Long,
    isBackingUp: Boolean,
    isRestoring: Boolean,
    onManualBackup: () -> Unit,
    onOpenFilePicker: () -> Unit,
    savedBackupFiles: List<BackupFileInfo>,
    onConfirmFileRestore: (File) -> Unit
) {
    var expandedCloudCard by remember { mutableStateOf(true) }
    var expandedPhoneCard by remember { mutableStateOf(true) }

    // Section 1: Namecheap Cloud Database
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedCloudCard = !expandedCloudCard },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3B82F6).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Namecheap MySQL Cloud",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (settings.cloudSyncUrl.isNotBlank()) "Connected to remote server" else "Tap to configure remote sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onShowSetupGuide,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Guide", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { expandedCloudCard = !expandedCloudCard }) {
                        Icon(
                            imageVector = if (expandedCloudCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                }
            }

            if (expandedCloudCard) {
                // Status Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (settings.cloudSyncUrl.isNotBlank()) Color(0xFFEFF6FF) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = if (settings.cloudSyncUrl.isNotBlank()) Color(0xFF2563EB) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (settings.lastCloudSyncTime > 0L) {
                                    "Last synced: ${Formatters.formatDate(settings.lastCloudSyncTime)}"
                                } else {
                                    "Remote database sync active"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = settings.lastCloudSyncStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Inputs
                OutlinedTextField(
                    value = cloudSyncUrl,
                    onValueChange = onCloudSyncUrlChange,
                    label = { Text("Namecheap API Endpoint URL") },
                    placeholder = { Text("https://yourdomain.com/api/sync.php") },
                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF2563EB)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_sync_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = cloudSyncSecretKey,
                    onValueChange = onCloudSyncSecretKeyChange,
                    label = { Text("Secret API Key (X-Api-Key)") },
                    placeholder = { Text("Matches \$API_SECRET_KEY in sync.php") },
                    leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color(0xFF2563EB)) },
                    trailingIcon = {
                        IconButton(onClick = onToggleShowSecretKey) {
                            Icon(
                                imageVector = if (showSecretKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showSecretKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    visualTransformation = if (showSecretKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_sync_key_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                // Test Connection Status Banner
                testCloudResultStatus?.let { (success, message) ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (success) FarmGreen else DebtRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (success) FarmGreen else DebtRed
                            )
                        }
                    }
                }

                // Action Buttons: Hick's Law - 1 primary, 2 secondary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestCloudConnection,
                        enabled = !isTestingCloudConn && !isCloudSyncing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTestingCloudConn) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test API", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Button(
                        onClick = onSyncToCloud,
                        enabled = !isCloudSyncing && cloudSyncUrl.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        if (isCloudSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Syncing...", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync to Cloud", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onRequestCloudRestore,
                    enabled = !isCloudSyncing && cloudSyncUrl.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF2563EB))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore Database from Cloud MySQL", color = Color(0xFF2563EB))
                }
            }
        }
    }

    // Section 2: Local Phone Database & 24h WorkManager
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedPhoneCard = !expandedPhoneCard },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BrandGreenPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = BrandGreenPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Phone Storage & Auto-Backup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "24h periodic JSON snapshot on device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFDCFCE7)
                ) {
                    Text(
                        text = "Auto Active",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FarmGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (expandedPhoneCard) {
                // Info Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF0FDF4),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBBF7D0))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Last Auto-Backup",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (lastBackupTime > 0) Formatters.formatDate(lastBackupTime) else "Scheduled every 24 hours",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = FarmGreen
                            )
                        }
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = FarmGreen, modifier = Modifier.size(22.dp))
                    }
                }

                // Quick Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onManualBackup,
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("manual_backup_now_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Saving...", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup Now", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenFilePicker,
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("restore_from_file_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, tint = BrandGreenPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore File", color = BrandGreenPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }

                // Saved Backups List (Miller's law: limit 3-5 latest)
                if (savedBackupFiles.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Text(
                        text = "Recent Local Backups (${savedBackupFiles.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        savedBackupFiles.take(4).forEach { backupInfo ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Description, contentDescription = null, tint = BrandGreenPrimary, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = backupInfo.fileName,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "${backupInfo.formattedDate} • ${backupInfo.formattedSize}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                val file = File(backupInfo.filePath)
                                                if (file.exists()) {
                                                    val shareIntent = DatabaseBackupHelper.createShareIntent(context, file)
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share Farm Database Backup"))
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = "Share", tint = BrandGreenPrimary, modifier = Modifier.size(15.dp))
                                        }

                                        Button(
                                            onClick = { onConfirmFileRestore(File(backupInfo.filePath)) },
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Restore", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. SMS GATEWAY TAB CONTENT
// ==========================================
@Composable
private fun SmsTabContent(
    context: android.content.Context,
    smsApiKey: String,
    onSmsApiKeyChange: (String) -> Unit,
    showApiKey: Boolean,
    onToggleShowApiKey: () -> Unit,
    isCheckingBalance: Boolean,
    balanceResultText: String?,
    onCheckBalance: () -> Unit,
    smsSenderId: String,
    onSmsSenderIdChange: (String) -> Unit,
    smsAutoSendOnSale: Boolean,
    onToggleAutoSendSale: (Boolean) -> Unit,
    smsAutoSendOnPayment: Boolean,
    onToggleAutoSendPayment: (Boolean) -> Unit,
    testPhoneInput: String,
    onTestPhoneInputChange: (String) -> Unit,
    isTestingSms: Boolean,
    testResultStatus: Pair<Boolean, String>?,
    onSendTestSms: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BrandGreenPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null, tint = BrandGreenPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "SMSOnlineGH Gateway",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Automated transaction receipts & debtor alerts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.smsonlinegh.com/"))
                        context.startActivity(browserIntent)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Portal", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp))
                }
            }

            // Connection Status
            val isConfigured = smsApiKey.isNotBlank()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (isConfigured) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isConfigured) FarmGreen else Color(0xFFB45309),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConfigured) "Service Connected • Ready for alerts" else "API Key Required for automatic SMS delivery",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConfigured) FarmGreen else Color(0xFF92400E)
                    )
                }
            }

            // API Key Input
            OutlinedTextField(
                value = smsApiKey,
                onValueChange = onSmsApiKeyChange,
                label = { Text("SMSOnlineGH API Key") },
                placeholder = { Text("Paste API key from SMSOnlineGH dashboard") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onToggleShowApiKey) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide API Key" else "Show API Key"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sms_api_key_input"),
                shape = RoundedCornerShape(12.dp)
            )

            // Balance Check Row
            if (smsApiKey.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCheckBalance,
                        enabled = !isCheckingBalance,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        if (isCheckingBalance) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Checking...", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Verify Key & Balance", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    balanceResultText?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (!text.startsWith("Failed")) FarmGreen else DebtRed
                        )
                    }
                }
            }

            // Sender ID
            OutlinedTextField(
                value = smsSenderId,
                onValueChange = onSmsSenderIdChange,
                label = { Text("Sender ID (Max 11 characters)") },
                placeholder = { Text("BizTrack") },
                singleLine = true,
                supportingText = { Text("${smsSenderId.length}/11 chars • Must match approved sender name on SMSOnlineGH") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sms_sender_id_input"),
                shape = RoundedCornerShape(12.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Automated Triggers Section
            Text(
                text = "AUTOMATED NOTIFICATION TRIGGERS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = BrandGreenPrimary
            )

            // Trigger 1: Sales
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-send on New Sale", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Sends instant receipt directly to customer upon checkout", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = smsAutoSendOnSale,
                    onCheckedChange = onToggleAutoSendSale,
                    modifier = Modifier.testTag("toggle_auto_sms_sale")
                )
            }

            // Trigger 2: Payments
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-send on Debt Payment", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Sends payment acknowledgment & remaining debt update", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = smsAutoSendOnPayment,
                    onCheckedChange = onToggleAutoSendPayment,
                    modifier = Modifier.testTag("toggle_auto_sms_payment")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Test SMS Tool
            Text(
                text = "SEND A TEST SMS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = testPhoneInput,
                    onValueChange = onTestPhoneInputChange,
                    placeholder = { Text("0244123456") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("test_sms_phone_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = onSendTestSms,
                    enabled = !isTestingSms && testPhoneInput.isNotBlank() && smsApiKey.isNotBlank(),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("send_test_sms_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreenPrimary)
                ) {
                    if (isTestingSms) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test")
                    }
                }
            }

            testResultStatus?.let { (success, message) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (success) FarmGreen else DebtRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (success) FarmGreen else DebtRed
                        )
                    }
                }
            }
        }
    }
}
