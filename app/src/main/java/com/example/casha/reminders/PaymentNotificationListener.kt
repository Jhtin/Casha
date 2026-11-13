package com.example.casha.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.casha.MainActivity
import com.example.casha.data.CapturedPayment
import com.example.casha.data.FinanceStore
import java.time.LocalDate
import java.util.UUID

class PaymentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val packageName = sbn.packageName ?: return

        // Skip our own app's notifications
        if (packageName == applicationContext.packageName) return

        val store = FinanceStore.current(applicationContext)
        // Check if user enabled the auto-log NFC / payment notification listener
        if (!store.flag("nfc_auto_expense_enabled", false)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()

        val accounts = store.moneyAccounts().map { it.name }
        // MessagingStyle can carry the full latest SMS when EXTRA_TEXT is only a summary.
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.messages.orEmpty()
        val latestSms = if (PaymentNotificationParser.isSmsApp(packageName)) messages.lastOrNull()?.text?.toString() else null
        val effectiveText = latestSms ?: bigText ?: text
        val parsed = PaymentNotificationParser.parse(packageName, title, effectiveText, availableAccountNames = accounts) ?: return

        // Deduplication: prevent logging duplicate notifications within 3 minutes
        val dedupeKey = "dedupe_${parsed.sourcePackage}_${parsed.amountCentavos}_${parsed.merchant}"
        val lastSeen = store.preferences.getLong(dedupeKey, 0L)
        val now = System.currentTimeMillis()
        if (now - lastSeen < 180_000L) return
        store.preferences.edit().putLong(dedupeKey, now).apply()

        // 1. Put into pending FIRST: will NOT be calculated until the user confirms
        val capturedId = "captured_" + UUID.randomUUID().toString()
        val rawSnippet = "${title.orEmpty()} ${effectiveText.orEmpty()}".take(2000)
        val captured = CapturedPayment(
            id = capturedId,
            amountCentavos = parsed.amountCentavos,
            merchant = parsed.merchant,
            sourcePackage = parsed.sourcePackage,
            channel = parsed.suggestedAccountName,
            date = LocalDate.now(),
            rawText = rawSnippet,
            timestampMs = now
        )
        if (store.scope != FinanceStore.scopeFor(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid)) return
        val saved = store.addCapturedPayment(captured)
        if (!saved) return

        // 2. Post high-priority notification so user knows a payment was detected
        showCapturedPaymentNotification(
            context = applicationContext,
            scope = store.scope,
            capturedId = capturedId,
            amountCentavos = parsed.amountCentavos,
            merchant = parsed.merchant,
            channel = parsed.suggestedAccountName,
            withdrawal = parsed.withdrawal
        )
    }

    companion object {
        const val CHANNEL_ID = "payment_auto_log"

        fun showCapturedPaymentNotification(
            context: Context,
            scope: String,
            capturedId: String,
            amountCentavos: Long,
            merchant: String,
            channel: String,
            withdrawal: Boolean = false
        ) {
            if (scope != FinanceStore.scopeFor(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid)) return
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    CHANNEL_ID,
                    "Payment auto-logging",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when an NFC or bank payment is captured awaiting your confirmation"
                }
                manager.createNotificationChannel(chan)
            }

            val amountFormatted = "Ã¢â€šÂ±" + java.text.DecimalFormat("#,##0.00").format(amountCentavos / 100.0)

            // Open app to review & confirm intent
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("openCapturedId", capturedId)
                putExtra("financeScope", scope)
                data = Uri.Builder().scheme("casha").authority("capture").appendPath(scope).appendPath(capturedId).appendPath("review").build()
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                capturedId.hashCode(),
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Dismiss intent
            val dismissIntent = Intent(context, UndoExpenseReceiver::class.java).apply {
                action = "DISMISS_CAPTURED"
                data = Uri.Builder().scheme("casha").authority("capture").appendPath(scope).appendPath(capturedId).appendPath("dismiss").build()
                putExtra("capturedId", capturedId)
                putExtra("scope", scope)
                putExtra("notificationId", capturedId.hashCode())
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                capturedId.hashCode() + 1,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${if (withdrawal) "Withdrawal" else "Payment"} detected: $amountFormatted")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Casha payment captured")
                    .setContentText("Unlock to review").build())
                .setContentText("Captured from $channel Ã‚Â· Open to review")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        if (withdrawal) "Withdrawal of $amountFormatted from $channel. Review the cash transfer and any fee in Casha. This is not automatically recorded as spending." else "Captured $amountFormatted at $merchant from $channel. Open Casha to review before recording it."
                    )
                )
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_save, "Review", openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            manager.notify(capturedId.hashCode(), notification)
        }
    }
}
