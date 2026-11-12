package com.cwk021t.precip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit

object Prefs {
    private const val FILE = "precip"
    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun saveConfig(c: Context, json: String) = prefs(c).edit().putString("config", json).apply()
    fun config(c: Context): JSONObject? = prefs(c).getString("config", null)?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }
}

object Scheduler {
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<PrecipWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("precip-check", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

object Notifier {
    private const val CHANNEL = "precip_alerts"

    fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Rain & snow alerts", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Upcoming precipitation, heavy totals and daily summaries"
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun show(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_drop)
            .setColor(0xFF2F80ED.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (e: SecurityException) { }
    }
}

/** Runs about every 30 minutes in the background and posts alerts. */
class PrecipWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val c = applicationContext
        val cfg = Prefs.config(c) ?: return Result.success()
        val lat = cfg.optDouble("lat", Double.NaN)
        val lon = cfg.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return Result.success()
        val threshold = cfg.optDouble("threshold", 1.0)
        val place = cfg.optString("name", "Home").ifBlank { "Home" }

        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&past_days=2&forecast_days=2" +
                "&precipitation_unit=inch&hourly=precipitation,rain,showers,snowfall,precipitation_probability",
            lat, lon
        )
        val json = try { JSONObject(get(url)) } catch (e: Exception) { return Result.success() }

        val off = json.optInt("utc_offset_seconds", 0)
        val h = json.getJSONObject("hourly")
        val time = h.getJSONArray("time")
        val pr = h.getJSONArray("precipitation")
        val rain = h.getJSONArray("rain")
        val showers = h.getJSONArray("showers")
        val snow = h.getJSONArray("snowfall")
        val prob = h.getJSONArray("precipitation_probability")
        fun d(a: org.json.JSONArray, i: Int) = if (a.isNull(i)) 0.0 else a.optDouble(i, 0.0)

        val local = Instant.now().plusSeconds(off.toLong()).atOffset(ZoneOffset.UTC)
        val nowKey = local.toString().substring(0, 13)          // yyyy-MM-ddTHH
        val today = nowKey.substring(0, 10)
        val yesterday = local.minusDays(1).toString().substring(0, 10)
        var i0 = -1
        for (i in 0 until time.length()) if (time.getString(i).substring(0, 13) > nowKey) { i0 = i; break }
        if (i0 < 0) return Result.success()

        val sp = Prefs.prefs(c)
        val now = System.currentTimeMillis()

        // 1) Rain or snow starting within ~2 hours
        if (cfg.optBoolean("nSoon", true)) {
            var start = -1
            var r3 = 0.0; var s3 = 0.0; var pmax = 0
            for (i in i0 until minOf(i0 + 3, time.length())) {
                val r = d(rain, i) + d(showers, i); val s = d(snow, i)
                if (start < 0 && i < i0 + 2 && (r >= 0.01 || s >= 0.1)) start = i
                r3 += r; s3 += s; pmax = maxOf(pmax, prob.optInt(i, 0))
            }
            // Was it already precipitating in the last hour? Then it isn't "starting".
            val wetNow = d(pr, i0 - 1) >= 0.01
            if (start >= 0 && !wetNow && now - sp.getLong("lastSoon", 0) > 6 * 3600_000L) {
                val isSnow = s3 >= 0.1
                val hr = hour(time.getString(start))
                val amt = if (isSnow) String.format(Locale.US, "%.1f\" of snow", s3) else String.format(Locale.US, "%.2f\" of rain", r3)
                Notifier.show(c, 1, if (isSnow) "Snow starting soon" else "Rain starting soon",
                    "$place: around $hr. About $amt over the next 3 hours ($pmax% chance).")
                sp.edit().putLong("lastSoon", now).apply()
            }
        }

        // 2) Heavy total in the last 24 hours
        if (cfg.optBoolean("nHeavy", true)) {
            var p24 = 0.0
            for (i in maxOf(0, i0 - 24) until i0) p24 += d(pr, i)
            if (p24 >= threshold && now - sp.getLong("lastHeavy", 0) > 24 * 3600_000L) {
                Notifier.show(c, 2, "Heavy precipitation",
                    String.format(Locale.US, "%s: %.2f\" in the last 24 hours (your alert is %.2f\").", place, p24, threshold))
                sp.edit().putLong("lastHeavy", now).apply()
            }
        }

        // 3) Morning summary of yesterday
        if (cfg.optBoolean("nDaily", true) && local.hour >= 7 && sp.getString("lastDaily", "") != today) {
            var r = 0.0; var s = 0.0
            for (i in 0 until time.length()) if (time.getString(i).startsWith(yesterday)) {
                r += d(rain, i) + d(showers, i); s += d(snow, i)
            }
            if (r >= 0.01 || s >= 0.1) {
                val parts = mutableListOf<String>()
                if (r >= 0.01) parts += String.format(Locale.US, "%.2f\" rain", r)
                if (s >= 0.1) parts += String.format(Locale.US, "%.1f\" snow", s)
                Notifier.show(c, 3, "Yesterday's precipitation", "$place: ${parts.joinToString(" · ")}")
            }
            sp.edit().putString("lastDaily", today).apply()
        }
        return Result.success()
    }

    private fun hour(t: String): String {
        val h = t.substring(11, 13).toInt()
        return "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"
    }

    private fun get(u: String): String {
        val con = URL(u).openConnection() as HttpURLConnection
        con.connectTimeout = 15000
        con.readTimeout = 15000
        return try { con.inputStream.bufferedReader().readText() } finally { con.disconnect() }
    }
}
