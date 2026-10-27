package pl.hm.courierradar.core

/** Что удалось вытащить из экрана оффера. */
data class Offer(
    val price: Double,
    /** Итоговые км (null — не нашли ни одного «km»). */
    val km: Double?,
    /** Итоговые минуты от приложения (null — приложение время не показало). */
    val minutes: Int?,
    val kmParts: List<Double>,
    val minuteParts: List<Int>,
    val looksLikeOffer: Boolean,
    /** Весь текст экрана одной строкой — нужен для поиска зон. */
    val text: String,
)

enum class Verdict { GOOD, MAYBE, BAD }

data class Score(
    val verdict: Verdict,
    val offer: Offer,
    val totalKm: Double,
    val kmEstimated: Boolean,
    val totalMinutes: Double,
    val net: Double,
    /** Брутто zł за км (null — км неизвестны). */
    val perKm: Double?,
    /** Чистыми zł в час (после топлива). */
    val perHour: Double,
    val notes: List<String>,
)

/** Место, где теряешь время (парковка, ТЦ, долгая выдача). Ищется по тексту оффера. */
data class ZoneRule(val keyword: String, val penaltyMin: Int)

data class Config(
    /** Минимум брутто zł за 1 км всего пути. */
    val minPerKm: Double = 2.5,
    /** Минимум чистыми zł в час. */
    val minPerHour: Double = 40.0,
    /** Ниже этой суммы — сразу мимо (0 = выключено). */
    val minPrice: Double = 0.0,
    /** Топливо + износ машины на 1 км. */
    val costPerKm: Double = 0.6,
    /** Средняя скорость по городу, если приложение не дало время. */
    val avgSpeedKmh: Double = 22.0,
    /** Минуты на ресторан + парковку + сдачу заказа, добавляются всегда. */
    val overheadMin: Int = 6,
    /** Множитель к времени приложения (1.0 = верим как есть). */
    val appTimeFactor: Double = 1.0,
    /** Множитель к своей оценке времени в часы пик. */
    val rushMultiplier: Double = 1.3,
    /** Часы пик, например "7-10,15-19" (конец не включается). */
    val rushHours: String = "7-10,15-19",
    val rushWeekdaysOnly: Boolean = true,
    val zones: List<ZoneRule> = DEFAULT_ZONES,
    /** Требовать на экране слово «принять/akceptuj/accept», чтобы не срабатывать на экранах заработка. */
    val requireOfferWords: Boolean = true,
    /** Если обе метрики не ниже этой доли от порога — «смотри», иначе «мимо». */
    val maybeBand: Double = 0.85,
) {
    companion object {
        /** Примеры — правь под себя в настройках. */
        val DEFAULT_ZONES = listOf(
            ZoneRule("Złote Tarasy", 7),
            ZoneRule("Arkadia", 7),
            ZoneRule("Galeria Mokotów", 6),
            ZoneRule("Westfield Mokotów", 6),
            ZoneRule("Nowy Świat", 5),
            ZoneRule("Chmielna", 5),
            ZoneRule("Stare Miasto", 6),
        )

        fun zonesToText(z: List<ZoneRule>): String =
            z.joinToString("\n") { "${it.keyword}=${it.penaltyMin}" }

        /** Строки вида «Złote Tarasy=7». Без «=» — штраф 5 минут. */
        fun zonesFromText(s: String): List<ZoneRule> =
            s.lines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                val i = t.lastIndexOf('=')
                if (i < 0) ZoneRule(t, 5)
                else {
                    val kw = t.substring(0, i).trim()
                    val p = t.substring(i + 1).trim().toIntOrNull() ?: 5
                    if (kw.isEmpty()) null else ZoneRule(kw, p)
                }
            }
    }
}
