package io.carmo.airplay.receiver

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object WeatherIdleRepository {
    private const val TAG = "Receiver-Weather"
    private const val KEY_CACHE = "weather_cache_summary"
    private const val KEY_CACHE_AT = "weather_cache_at"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private val inFlight = AtomicBoolean(false)

    fun summary(context: Context): String {
        val prefs = ReceiverPreferences.prefs(context)
        val location = prefs.getString(ReceiverPreferences.KEY_WEATHER_LOCATION_NAME, null)?.takeIf { it.isNotBlank() }
        val lat = prefs.getString(ReceiverPreferences.KEY_WEATHER_LATITUDE, null)?.toDoubleOrNull()
        val lon = prefs.getString(ReceiverPreferences.KEY_WEATHER_LONGITUDE, null)?.toDoubleOrNull()
        if (location == null || lat == null || lon == null) {
            return "Set location for weather"
        }

        val cached = prefs.getString(KEY_CACHE, null)
        val cachedAt = prefs.getLong(KEY_CACHE_AT, 0L)
        if (System.currentTimeMillis() - cachedAt > REFRESH_MS) {
            refreshAsync(context.applicationContext, location, lat, lon)
        }
        return cached ?: "Weather unavailable"
    }

    private fun refreshAsync(context: Context, location: String, lat: Double, lon: Double) {
        if (!inFlight.compareAndSet(false, true)) return
        Thread({
            try {
                val summary = fetchSummary(location, lat, lon)
                ReceiverPreferences.prefs(context).edit()
                    .putString(KEY_CACHE, summary)
                    .putLong(KEY_CACHE_AT, System.currentTimeMillis())
                    .apply()
            } catch (e: Throwable) {
                Log.w(TAG, "weather refresh failed", e)
            } finally {
                inFlight.set(false)
            }
        }, "ReceiverWeatherRefresh").start()
    }

    private fun fetchSummary(location: String, lat: Double, lon: Double): String {
        val units = if (Locale.getDefault().country.equals("US", ignoreCase = true)) {
            "fahrenheit"
        } else {
            "celsius"
        }
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=$units&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
        }
        connection.inputStream.bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val unit = json.getJSONObject("current_units").optString("temperature_2m", if (units == "fahrenheit") "F" else "C")
            val temp = current.optDouble("temperature_2m")
            val code = current.optInt("weather_code")
            val high = daily.getJSONArray("temperature_2m_max").optDouble(0)
            val low = daily.getJSONArray("temperature_2m_min").optDouble(0)
            return "%s: %s %.0f%s, H %.0f / L %.0f".format(
                Locale.US,
                location,
                condition(code),
                temp,
                unit,
                high,
                low
            )
        }
    }

    private fun condition(code: Int): String {
        return when (code) {
            0 -> "Sunny"
            1, 2, 3 -> "Clouds"
            in 45..48 -> "Fog"
            in 51..67, in 80..82 -> "Rain"
            in 71..77, in 85..86 -> "Snow"
            in 95..99 -> "Storm"
            else -> "Weather"
        }
    }
}
