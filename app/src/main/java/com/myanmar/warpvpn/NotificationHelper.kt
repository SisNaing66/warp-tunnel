package com.myanmar.warpvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.myanmar.warpvpn.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "WARP_TUNNEL_SERVICE_CHANNEL"
        const val NOTIFICATION_ID = 4040
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WARP TUNNEL Connection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active VPN status and real-time ping"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(pingMs: String = "Measuring..."): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val subText = if (pingMs.endsWith("ms") || pingMs == "Timeout" || pingMs == "Measuring...") {
            "Ping: $pingMs"
        } else {
            "Ping: $pingMs ms"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("SN Tulip Vpn")
            .setContentText("SN Tulip Vpn Active & Protected | $subText")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(pingMs: String) {
        val notification = buildNotification(pingMs)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
