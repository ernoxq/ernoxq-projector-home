package md.grin.projector

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class WeatherNow(val temp: Int, val code: Int, val city: String) {
    val icon: Int
        get() = when (code) {
            0, 1 -> R.drawable.ic_sun
            in 51..67, in 80..82, in 95..99 -> R.drawable.ic_rain
            in 71..77, 85, 86 -> R.drawable.ic_snow
            else -> R.drawable.ic_cloud
        }
}

/** Free Open-Meteo forecast (no key). Kept for 30 minutes so the projector isn't hitting the network constantly. */
object Weather {
    private var cached: WeatherNow? = null
    private var at = 0L

    fun city(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("city", "Кишинёв")!!
    private fun lat(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getFloat("lat", 47.0105f)
    private fun lon(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getFloat("lon", 28.8638f)

    fun peek(): WeatherNow? = cached

    /** Blocking; call from a background thread. */
    fun load(ctx: Context): WeatherNow? {
        if (cached != null && System.currentTimeMillis() - at < 30 * 60_000) return cached
        return try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${lat(ctx)}&longitude=${lon(ctx)}&current=temperature_2m,weather_code&timezone=auto")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val cur = JSONObject(body).getJSONObject("current")
            WeatherNow(cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"), city(ctx)).also {
                cached = it
                at = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            cached
        }
    }
}
