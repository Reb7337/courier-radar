package pl.hm.courierradar.core

import kotlin.math.abs

/**
 * Вытаскивает цену, км и минуты из текстов, которые видны на экране оффера.
 * Работает по эвристикам, без привязки к вёрстке конкретного приложения,
 * поэтому переживает их обновления. Тонкая подстройка — по журналу экранов.
 */
object OfferParser {

    private val SPACES = Regex("[    ]")

    private val PRICE_AFTER =
        Regex("""(?<![\d.,])(\d{1,3})(?:[.,](\d{1,2}))?\s*(?:zł|zl|pln)(?![a-ząćęłńóśźż])""")
    private val PRICE_BEFORE =
        Regex("""(?<![a-ząćęłńóśźż])(?:pln|zł)\s*(\d{1,3})(?:[.,](\d{1,2}))?(?![\d.,])(?!\s*(?:km|m\b|min|мин|h\b))""")

    private val KM = Regex("""(?<![\d.,])(\d{1,3}(?:[.,]\d{1,2})?)\s*km(?![a-ząćęłńóśźż/])""")
    private val METERS = Regex("""(?<![\d.,])(\d{2,4})\s*m(?![a-ząćęłńóśźż/.])""")
    private val MINUTES =
        Regex("""(?:(\d{1,2})\s*(?:h|godz\.?|ч|год\.?)\s+)?(?<![\d.,:])(\d{1,3})\s*(?:min|мин|хв)""")

    /** Строки с этими словами — не заработок курьера (наличка клиента, бонус-челлендж и т.п.). */
    private val PRICE_SKIP = listOf(
        "gotówk", "gotowk", "cash", "pobierz", "pobrania", "collect", "wartość", "wartosc",
        "order value", "do zapłaty", "do zaplaty", "zapłać", "zaplac", "наличн",
        "bonus", "quest", "wyzwan", "challenge", "tydzień", "tydzien", "dzisiaj zarobiłeś", "today",
    )

    /** Строки с этими словами — главный заработок. */
    private val PRICE_PREFER = listOf(
        "zarob", "earn", "wynagrodz", "łącznie", "lacznie", "razem", "total", "payout",
        "otrzymasz", "dostaniesz", "заработ", "доход", "получ", "oferta", "offer",
    )

    private val TOTAL_WORDS = listOf(
        "łącznie", "lacznie", "razem", "w sumie", "suma", "całk", "calk", "total", "всего", "общ",
    )

    /** Минуты в таких строках — не дорога (время готовки, «2 мин назад»). */
    private val MIN_SKIP = listOf(
        "temu", "ago", "назад", "gotow", "ready", "przygot", "готов",
        "czekan", "oczekiw", "wait", "ожид",
    )

    private val OFFER_WORDS = listOf(
        "akcept", "accept", "przyjm", "odrzu", "decline", "reject", "приня", "отклон", "прийн",
        "nowe zlecenie", "nowa dostawa", "nowe zamówienie", "nowe zamowienie", "nowa oferta",
        "new order", "new delivery", "new request", "new offer", "dopasow", "match",
    )

    fun normalize(s: String): String = SPACES.replace(s, " ").lowercase()

    fun parse(texts: List<String>): Offer? {
        val lines = texts
            .flatMap { it.split('\n') }
            .map { normalize(it).trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        // --- цена ---
        data class P(val v: Double, val preferred: Boolean)
        val prices = mutableListOf<P>()
        for (line in lines) {
            if (PRICE_SKIP.any { line.contains(it) }) continue
            val pref = PRICE_PREFER.any { line.contains(it) }
            val after = PRICE_AFTER.findAll(line).toList()
            for (m in after) prices += P(num(m.groupValues[1], m.groupValues[2]), pref)
            // «PLN 18.50» — только если в строке нет «18,50 zł», иначе «zł 6,2 km» даст ложную цену
            if (after.isEmpty()) {
                for (m in PRICE_BEFORE.findAll(line)) prices += P(num(m.groupValues[1], m.groupValues[2]), pref)
            }
        }
        val sane = prices.filter { it.v in 3.0..500.0 }
        val price = (sane.filter { it.preferred }.ifEmpty { sane }).maxOfOrNull { it.v } ?: return null

        // --- км ---
        val kmVals = mutableListOf<Pair<Double, Boolean>>()
        for (line in lines) {
            val isTotal = TOTAL_WORDS.any { line.contains(it) }
            for (m in KM.findAll(line)) kmVals += m.groupValues[1].replace(',', '.').toDouble() to isTotal
            for (m in METERS.findAll(line)) kmVals += m.groupValues[1].toDouble() / 1000.0 to isTotal
        }

        // --- минуты ---
        val minVals = mutableListOf<Pair<Double, Boolean>>()
        for (line in lines) {
            if (MIN_SKIP.any { line.contains(it) }) continue
            val isTotal = TOTAL_WORDS.any { line.contains(it) }
            for (m in MINUTES.findAll(line)) {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mm = m.groupValues[2].toIntOrNull() ?: continue
                val total = h * 60 + mm
                if (total in 1..300) minVals += total.toDouble() to isTotal
            }
        }

        val joined = lines.joinToString(" | ")
        return Offer(
            price = price,
            km = resolve(kmVals)?.let { round2(it) },
            minutes = resolve(minVals)?.let { Math.round(it).toInt() },
            kmParts = kmVals.map { it.first },
            minuteParts = minVals.map { it.first.toInt() },
            looksLikeOffer = OFFER_WORDS.any { joined.contains(it) },
            text = joined,
        )
    }

    /**
     * Из нескольких чисел выбирает итог пути:
     * подписано «łącznie/total» → оно; одно число → оно; два → сумма (до ресторана + до клиента);
     * больше → если самое большое ≈ сумме остальных, это итог, иначе сумма.
     */
    internal fun resolve(vals: List<Pair<Double, Boolean>>): Double? {
        if (vals.isEmpty()) return null
        val labeled = vals.filter { it.second }
        if (labeled.isNotEmpty()) return labeled.maxOf { it.first }
        val v = vals.map { it.first }
        return when (v.size) {
            1 -> v[0]
            2 -> v.sum()
            else -> {
                val max = v.max()
                val rest = v.sum() - max
                if (abs(max - rest) <= 0.12 * max) max else v.sum()
            }
        }
    }

    private fun num(whole: String, frac: String): Double =
        if (frac.isEmpty()) whole.toDouble()
        else "$whole.${frac.padEnd(2, '0')}".toDouble()

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}
