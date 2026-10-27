package pl.hm.courierradar.core

import java.util.Locale

object Scorer {

    private val PL: Locale = Locale.forLanguageTag("pl-PL")

    /** Убирает польские буквы и регистр, чтобы «Zlote Tarasy» находило «Złote Tarasy». */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'ą' -> 'a'; 'ć' -> 'c'; 'ę' -> 'e'; 'ł' -> 'l'; 'ń' -> 'n'
                    'ó' -> 'o'; 'ś' -> 's'; 'ź', 'ż' -> 'z'
                    ' ', ' ' -> ' '
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** "7-10,15-19" → [(7,10),(15,19)]; кривые куски пропускаются. */
    fun parseRush(s: String): List<Pair<Int, Int>> =
        s.split(',', ';', ' ').mapNotNull { part ->
            val p = part.trim().split('-')
            if (p.size != 2) return@mapNotNull null
            val a = p[0].trim().toIntOrNull() ?: return@mapNotNull null
            val b = p[1].trim().toIntOrNull() ?: return@mapNotNull null
            if (a in 0..23 && b in 1..24 && a < b) a to b else null
        }

    /**
     * @param hour 0..23 сейчас
     * @param isoDayOfWeek 1 = понедельник … 7 = воскресенье
     */
    fun isRush(cfg: Config, hour: Int, isoDayOfWeek: Int): Boolean {
        if (cfg.rushWeekdaysOnly && isoDayOfWeek >= 6) return false
        return parseRush(cfg.rushHours).any { (a, b) -> hour in a until b }
    }

    fun score(offer: Offer, cfg: Config, hour: Int, isoDayOfWeek: Int): Score {
        val notes = mutableListOf<String>()
        val rush = isRush(cfg, hour, isoDayOfWeek)

        // Километры
        var kmEstimated = false
        val km: Double = when {
            offer.km != null && offer.km > 0 -> offer.km
            offer.minutes != null -> {
                kmEstimated = true
                notes += "км не видно — оценил по времени"
                offer.minutes / 60.0 * cfg.avgSpeedKmh
            }
            else -> 0.0
        }

        // Время в пути
        var drive: Double = if (offer.minutes != null) {
            offer.minutes * cfg.appTimeFactor
        } else {
            notes += "время не видно — оценил по км"
            var t = km / cfg.avgSpeedKmh * 60.0
            if (rush) {
                t *= cfg.rushMultiplier
                notes += "час пик ×" + fmt1(cfg.rushMultiplier)
            }
            t
        }
        if (offer.minutes != null && rush) notes += "час пик (время уже от приложения)"
        if (drive < 1.0) drive = 1.0

        // Зоны: парковка / ТЦ / долгая выдача
        val text = fold(offer.text)
        var penalty = 0
        for (z in cfg.zones) {
            val kw = fold(z.keyword).trim()
            if (kw.isNotEmpty() && text.contains(kw)) {
                penalty += z.penaltyMin
                notes += z.keyword + " +" + z.penaltyMin + " мин"
            }
        }

        val totalMin = drive + cfg.overheadMin + penalty
        val net = offer.price - km * cfg.costPerKm
        val perHour = net / (totalMin / 60.0)
        val perKm: Double? = if (km > 0 && !kmEstimated) offer.price / km else null

        // Вердикт
        val hourRatio = perHour / cfg.minPerHour
        val kmRatio = if (perKm != null && cfg.minPerKm > 0) perKm / cfg.minPerKm else hourRatio
        val verdict = when {
            cfg.minPrice > 0 && offer.price < cfg.minPrice -> {
                notes += "ниже минимума " + money(cfg.minPrice)
                Verdict.BAD
            }
            hourRatio >= 1.0 && kmRatio >= 1.0 -> Verdict.GOOD
            hourRatio >= cfg.maybeBand && kmRatio >= cfg.maybeBand -> Verdict.MAYBE
            else -> Verdict.BAD
        }
        if (perKm != null && kmRatio < 1.0) notes += "zł/km ниже " + fmt2(cfg.minPerKm)
        if (hourRatio < 1.0) notes += "zł/ч ниже " + fmt0(cfg.minPerHour)

        return Score(
            verdict = verdict,
            offer = offer,
            totalKm = km,
            kmEstimated = kmEstimated,
            totalMinutes = totalMin,
            net = net,
            perKm = perKm,
            perHour = perHour,
            notes = notes,
        )
    }

    fun money(x: Double) = String.format(PL, "%.2f zł", x)
    fun fmt2(x: Double) = String.format(PL, "%.2f", x)
    fun fmt1(x: Double) = String.format(PL, "%.1f", x)
    fun fmt0(x: Double) = String.format(PL, "%.0f", x)

    /** Короткая строка для журнала / оверлея. */
    fun summary(s: Score): String {
        val km = (if (s.kmEstimated) "~" else "") + fmt1(s.totalKm) + " km"
        val perKm = s.perKm?.let { fmt2(it) + " zł/km" } ?: "zł/km ?"
        return money(s.offer.price) + " · " + km + " · ~" + fmt0(s.totalMinutes) + " мин\n" +
            perKm + " · " + fmt0(s.perHour) + " zł/ч · чистыми " + money(s.net)
    }
}
