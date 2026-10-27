package pl.hm.courierradar

import android.content.Context
import android.content.SharedPreferences
import pl.hm.courierradar.core.Config

/** Все настройки в SharedPreferences. Числа хранятся строками — так проще с полями ввода. */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)

    fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun dbl(key: String, def: Double): Double =
        str(key, "").replace(',', '.').trim().toDoubleOrNull() ?: def
    private fun int(key: String, def: Int): Int = str(key, "").trim().toIntOrNull() ?: def

    fun config(): Config {
        val d = Config()
        return Config(
            minPerKm = dbl(K_MIN_KM, d.minPerKm),
            minPerHour = dbl(K_MIN_HOUR, d.minPerHour),
            minPrice = dbl(K_MIN_PRICE, d.minPrice),
            costPerKm = dbl(K_COST_KM, d.costPerKm),
            avgSpeedKmh = dbl(K_SPEED, d.avgSpeedKmh).coerceAtLeast(5.0),
            overheadMin = int(K_OVERHEAD, d.overheadMin),
            appTimeFactor = dbl(K_APP_FACTOR, d.appTimeFactor).coerceIn(0.5, 3.0),
            rushMultiplier = dbl(K_RUSH_MULT, d.rushMultiplier).coerceIn(1.0, 3.0),
            rushHours = str(K_RUSH_HOURS, d.rushHours),
            rushWeekdaysOnly = bool(K_RUSH_WEEKDAYS, d.rushWeekdaysOnly),
            zones = if (sp.contains(K_ZONES)) Config.zonesFromText(str(K_ZONES, "")) else d.zones,
            requireOfferWords = bool(K_REQUIRE_WORDS, d.requireOfferWords),
            maybeBand = d.maybeBand,
        )
    }

    fun packages(): Set<String> =
        str(K_PACKAGES, DEFAULT_PACKAGES).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val sound get() = bool(K_SOUND, true)
    val vibrate get() = bool(K_VIBRATE, true)
    val debugScreens get() = bool(K_DEBUG, true)
    val listenAll get() = bool(K_LISTEN_ALL, false)
    val overlayYdp get() = int(K_OVERLAY_Y, 24)

    companion object {
        const val K_MIN_KM = "min_km"
        const val K_MIN_HOUR = "min_hour"
        const val K_MIN_PRICE = "min_price"
        const val K_COST_KM = "cost_km"
        const val K_SPEED = "speed"
        const val K_OVERHEAD = "overhead"
        const val K_APP_FACTOR = "app_factor"
        const val K_RUSH_MULT = "rush_mult"
        const val K_RUSH_HOURS = "rush_hours"
        const val K_RUSH_WEEKDAYS = "rush_weekdays"
        const val K_ZONES = "zones"
        const val K_REQUIRE_WORDS = "require_words"
        const val K_PACKAGES = "packages"
        const val K_SOUND = "sound"
        const val K_VIBRATE = "vibrate"
        const val K_DEBUG = "debug_screens"
        const val K_LISTEN_ALL = "listen_all"
        const val K_OVERLAY_Y = "overlay_y"

        /** Uber Driver (в нём же работают курьеры Uber Eats). Wolt найдётся кнопкой «Найти». */
        const val DEFAULT_PACKAGES = "com.ubercab.driver"

        /** Клиентские приложения — их не слушаем. */
        val CUSTOMER_APPS = setOf("com.ubercab", "com.ubercab.eats", "com.wolt.android")
    }
}
