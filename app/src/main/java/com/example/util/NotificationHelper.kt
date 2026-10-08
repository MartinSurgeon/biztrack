package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_SALES = "naadriel_channel_sales"
    const val CHANNEL_PAYMENTS = "naadriel_channel_payments"
    const val CHANNEL_SMS = "naadriel_channel_sms"
    const val CHANNEL_ALERTS = "naadriel_channel_alerts"

    const val EXTRA_NAVIGATE_SCREEN = "extra_navigate_screen"
    const val EXTRA_ORDER_ID = "extra_order_id"
    const val EXTRA_CUSTOMER_ID = "extra_customer_id"

    // Primary Brand Forest Green
    private const val BRAND_COLOR = 0xFF1B5E20.toInt()
    private const val DEBT_RED_COLOR = 0xFFC62828.toInt()

    /**
     * Set up Notification Channels for Android 8.0+ (API 26+)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // 1. Sales Channel (High priority)
            val salesChannel = NotificationChannel(
                CHANNEL_SALES,
                "Sales & Invoices",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant alerts when new egg and poultry sales are recorded"
                enableLights(true)
                lightColor = BRAND_COLOR
                enableVibration(true)
                setShowBadge(true)
            }

            // 2. Payments & Debt Channel (High priority)
            val paymentsChannel = NotificationChannel(
                CHANNEL_PAYMENTS,
                "Debt Payments & Settlements",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for customer payments and cleared debt balances"
                enableLights(true)
                lightColor = BRAND_COLOR
                enableVibration(true)
                setShowBadge(true)
            }

            // 3. SMS Gateway Channel (Default priority)
            val smsChannel = NotificationChannel(
                CHANNEL_SMS,
                "SMSOnlineGH Gateway",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "SMS delivery status, dispatch receipts, and SMS account balances"
                enableLights(false)
            }

            // 4. Farm & Inventory Alerts (Default priority)
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Farm Alerts & Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Customer overdue debt reminders and inventory notifications"
            }

            notificationManager.createNotificationChannels(
                listOf(salesChannel, paymentsChannel, smsChannel, alertsChannel)
            )
        }
    }

    /**
     * Display a beautifully designed Custom View notification when a sale is completed
     */
    fun showSaleCompletedNotification(
        context: Context,
        invoiceNumber: String,
        customerName: String,
        totalAmountFormatted: String,
        amountPaidFormatted: String,
        balanceDue: Double,
        currencySymbol: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_SCREEN, "DASHBOARD")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            invoiceNumber.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Quick New Sale
        val newSaleIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_SCREEN, "NEW_SALE")
        }
        val newSalePendingIntent = PendingIntent.getActivity(
            context,
            (invoiceNumber + "_new").hashCode(),
            newSaleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Debtors / Ledger
        val debtorsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_SCREEN, "DEBTORS")
        }
        val debtorsPendingIntent = PendingIntent.getActivity(
            context,
            (invoiceNumber + "_debt").hashCode(),
            debtorsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isFullyPaid = balanceDue <= 0.001
        val paymentSummary = if (isFullyPaid) {
            "Paid in Full (Cash/MoMo)"
        } else {
            "Credit: Paid $amountPaidFormatted (Owes $currencySymbol ${String.format("%.2f", balanceDue)})"
        }

        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        // 1. Build Beautiful Collapsed RemoteView
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_custom_collapsed).apply {
            setTextViewText(R.id.notif_header_tag, "NAADRIEL FARM • SALE ORDER")
            setTextViewText(R.id.notif_time, timeStr)
            setTextViewText(R.id.notif_title, "Sale Order #$invoiceNumber")
            setTextViewText(R.id.notif_subtitle, "$customerName • $paymentSummary")
            setTextViewText(R.id.notif_amount_text, totalAmountFormatted)
        }

        // 2. Build Beautiful Expanded RemoteView
        val expandedView = RemoteViews(context.packageName, R.layout.notification_custom_expanded).apply {
            setTextViewText(R.id.notif_exp_business_name, "NAADRIEL ENTERPRISE")
            setTextViewText(R.id.notif_exp_category_tag, "Egg & Poultry Sales Receipt")
            setTextViewText(R.id.notif_exp_title, "Order #$invoiceNumber")
            setTextViewText(R.id.notif_exp_customer, "Customer: $customerName")
            setTextViewText(R.id.notif_exp_total_amount, totalAmountFormatted)
            setTextViewText(R.id.notif_exp_payment_status, paymentSummary)
            setTextViewText(
                R.id.notif_exp_badge_status,
                if (isFullyPaid) "PAID IN FULL" else "CREDIT SALE"
            )
            setTextViewText(
                R.id.notif_exp_footer_note,
                "📱 Tap to view order details, print digital receipt, or share via WhatsApp"
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_SALES)
            .setSmallIcon(R.drawable.ic_naadriel_logo)
            .setColor(BRAND_COLOR)
            .setContentTitle("Sale Recorded: #$invoiceNumber")
            .setContentText("$customerName • $totalAmountFormatted")
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_naadriel_logo, "+ New Sale", newSalePendingIntent)
            .addAction(R.drawable.ic_naadriel_logo, "👥 Debtors", debtorsPendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(invoiceNumber.hashCode(), notification)
        } catch (e: SecurityException) {
            // Android 13+ permission not granted
        }
    }

    /**
     * Display a beautifully designed Custom View notification when debt payment is recorded
     */
    fun showPaymentReceivedNotification(
        context: Context,
        customerName: String,
        amountPaidFormatted: String,
        remainingBalance: Double,
        currencySymbol: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_SCREEN, "DEBTORS")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            customerName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isCleared = remainingBalance <= 0.001
        val balanceSummary = if (isCleared) {
            "🎉 Balance Cleared (GH₵ 0.00)"
        } else {
            "Remaining Debt: $currencySymbol ${String.format("%.2f", remainingBalance)}"
        }

        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        // 1. Build Beautiful Collapsed RemoteView
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_custom_collapsed).apply {
            setTextViewText(R.id.notif_header_tag, "NAADRIEL FARM • DEBT PAYMENT")
            setTextViewText(R.id.notif_time, timeStr)
            setTextViewText(R.id.notif_title, "Payment from $customerName")
            setTextViewText(R.id.notif_subtitle, balanceSummary)
            setTextViewText(R.id.notif_amount_text, "+$amountPaidFormatted")
        }

        // 2. Build Beautiful Expanded RemoteView
        val expandedView = RemoteViews(context.packageName, R.layout.notification_custom_expanded).apply {
            setTextViewText(R.id.notif_exp_business_name, "NAADRIEL ENTERPRISE")
            setTextViewText(R.id.notif_exp_category_tag, "Customer Debt Ledger Update")
            setTextViewText(R.id.notif_exp_title, "Payment Received from $customerName")
            setTextViewText(R.id.notif_exp_customer, "Account: $customerName")
            setTextViewText(R.id.notif_exp_total_amount, amountPaidFormatted)
            setTextViewText(R.id.notif_exp_payment_status, balanceSummary)
            setTextViewText(
                R.id.notif_exp_badge_status,
                if (isCleared) "DEBT CLEARED" else "PARTIAL PAYMENT"
            )
            setTextViewText(
                R.id.notif_exp_footer_note,
                "📱 Customer ledger updated. Tap to open Debtors list & send SMS confirmation."
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_PAYMENTS)
            .setSmallIcon(R.drawable.ic_naadriel_logo)
            .setColor(BRAND_COLOR)
            .setContentTitle("Payment Received: $amountPaidFormatted")
            .setContentText("From $customerName • $balanceSummary")
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                ("pay_" + customerName + System.currentTimeMillis()).hashCode(),
                notification
            )
        } catch (e: SecurityException) {
            // Android 13+ permission not granted
        }
    }

    /**
     * Display a clean styled notification for SMS delivery status
     */
    fun showSmsStatusNotification(
        context: Context,
        title: String,
        message: String,
        isSuccess: Boolean
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_SCREEN, "SETTINGS")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        val collapsedView = RemoteViews(context.packageName, R.layout.notification_custom_collapsed).apply {
            setTextViewText(R.id.notif_header_tag, "SMSONLINEGH GATEWAY")
            setTextViewText(R.id.notif_time, timeStr)
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_subtitle, message)
            setTextViewText(R.id.notif_amount_text, if (isSuccess) "SENT" else "FAILED")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_naadriel_logo)
            .setColor(if (isSuccess) BRAND_COLOR else DEBT_RED_COLOR)
            .setContentTitle(title)
            .setContentText(message)
            .setCustomContentView(collapsedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(if (isSuccess) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
        } catch (e: SecurityException) {
            // Android 13+ permission not granted
        }
    }
}
