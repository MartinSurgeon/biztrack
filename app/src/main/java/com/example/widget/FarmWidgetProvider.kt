package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.util.Formatters
import com.example.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, FarmWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.example.ACTION_REFRESH_WIDGET"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, FarmWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            context.sendBroadcast(intent)
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_farm_summary)

            // Setup Intents for Interactive Actions
            // 1. Root click -> Open Home/Dashboard
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_NAVIGATE_SCREEN, "DASHBOARD")
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 101, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_sales_card, openAppPendingIntent)

            // 2. New Sale button -> Open New Sale
            val newSaleIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_NAVIGATE_SCREEN, "NEW_SALE")
            }
            val newSalePendingIntent = PendingIntent.getActivity(
                context, 102, newSaleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_new_sale, newSalePendingIntent)

            // 3. Debtors button & Debt card -> Open Debtors screen
            val debtorsIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_NAVIGATE_SCREEN, "DEBTORS")
            }
            val debtorsPendingIntent = PendingIntent.getActivity(
                context, 103, debtorsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_debtors, debtorsPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_debt_card, debtorsPendingIntent)

            // 4. Refresh Button -> Broadcast refresh action
            val refreshIntent = Intent(context, FarmWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 104, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)

            // Fetch live data from Room DB in background coroutine
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val settings = db.appSettingsDao().getSettingsDirect()
                    val currencySymbol = settings?.currencySymbol ?: "GH₵"
                    val businessName = settings?.businessName?.ifBlank { "BizTrack Business" } ?: "BizTrack Business"

                    // Calculate Start of Today timestamp
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val startOfToday = cal.timeInMillis

                    val todayOrders = db.saleOrderDao().getOrdersSinceDirect(startOfToday)
                    val todayTotalSales = todayOrders.sumOf { it.totalAmount }
                    val todayOrderCount = todayOrders.size

                    val debtorsList = db.customerDao().getDebtorsDirect()
                    val totalDebt = debtorsList.sumOf { it.currentBalance }
                    val debtorsCount = debtorsList.size

                    val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
                    val updateTime = timeFormatter.format(Date())

                    // Update UI views
                    views.setTextViewText(R.id.widget_business_title, businessName)
                    views.setTextViewText(R.id.widget_time_label, "Updated $updateTime")
                    views.setTextViewText(
                        R.id.widget_today_sales_text,
                        Formatters.formatCurrency(todayTotalSales, currencySymbol)
                    )
                    views.setTextViewText(
                        R.id.widget_today_orders_count,
                        "$todayOrderCount sale${if (todayOrderCount == 1) "" else "s"} today"
                    )

                    views.setTextViewText(
                        R.id.widget_total_debt_text,
                        Formatters.formatCurrency(totalDebt, currencySymbol)
                    )
                    views.setTextViewText(
                        R.id.widget_debtors_count,
                        "$debtorsCount debtor${if (debtorsCount == 1) "" else "s"} owing"
                    )

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (e: Exception) {
                    // Fallback to default
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }
    }
}
