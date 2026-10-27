package pl.hm.courierradar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import pl.hm.courierradar.core.Config
import pl.hm.courierradar.core.OfferParser
import pl.hm.courierradar.core.Scorer
import java.time.LocalDateTime

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var testInput: EditText
    private lateinit var testOut: TextView
    private lateinit var logView: TextView
    private val fields = LinkedHashMap<String, EditText>()
    private val checks = LinkedHashMap<String, CheckBox>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        RadarLog.init(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(48))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        build()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ---------- UI ----------

    private fun build() {
        val c = prefs.config()

        title("Курьер Радар")
        note("Читает оффер Uber Eats / Wolt и показывает сверху: БЕРИ / СМОТРИ / МИМО. Сам ничего не нажимает.")

        section("1. Включение")
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(status)
        button("Открыть «Спец. возможности»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        note("Android 13+: если переключатель серый — открой «О приложении» → ⋮ (вверху справа) → «Разрешить ограниченные настройки», потом снова сюда.")
        button("Открыть «О приложении»") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("Показать пример карточки") {
            val s = RadarService.instance
            if (s == null) toast("Сначала включи сервис") else s.showTest()
        }

        section("2. Мои пороги")
        num(Prefs.K_MIN_KM, "Минимум zł за 1 км (весь путь, брутто)", c.minPerKm)
        num(Prefs.K_MIN_HOUR, "Минимум zł в час (чистыми после топлива)", c.minPerHour)
        num(Prefs.K_MIN_PRICE, "Сумма ниже — сразу мимо (0 = выкл)", c.minPrice)

        section("3. Машина и город")
        num(Prefs.K_COST_KM, "Топливо + износ, zł за 1 км", c.costPerKm)
        num(Prefs.K_OVERHEAD, "Минут на ресторан + парковку + сдачу (каждый заказ)", c.overheadMin.toDouble())
        num(Prefs.K_SPEED, "Средняя скорость, км/ч (если приложение не дало время)", c.avgSpeedKmh)
        num(Prefs.K_APP_FACTOR, "Поправка к времени приложения (1 = верю, 1.2 = +20%)", c.appTimeFactor)
        num(Prefs.K_RUSH_MULT, "Час пик: множитель к моей оценке времени", c.rushMultiplier)
        text(Prefs.K_RUSH_HOURS, "Часы пик (через запятую)", c.rushHours, false)
        check(Prefs.K_RUSH_WEEKDAYS, "Час пик только в будни", c.rushWeekdaysOnly)

        section("4. Плохие места (парковка, ТЦ, долгая выдача)")
        note("По строке на место: «название или улица=минуты штрафа». Ищется в тексте оффера (ресторан, адрес). Польские буквы можно не ставить.")
        text(Prefs.K_ZONES, "Места", Config.zonesToText(c.zones), true)

        section("5. Приложения")
        text(Prefs.K_PACKAGES, "Какие приложения слушать (по одному в строке)", prefs.str(Prefs.K_PACKAGES, Prefs.DEFAULT_PACKAGES), true)
        button("Найти Uber / Wolt на телефоне") { findApps() }
        check(Prefs.K_REQUIRE_WORDS, "Срабатывать только если на экране есть «Akceptuj/Accept/Przyjmij»", c.requireOfferWords)
        check(Prefs.K_LISTEN_ALL, "Слушать вообще все приложения (для поиска, потом выключи)", prefs.listenAll)

        section("6. Сигналы")
        check(Prefs.K_SOUND, "Звук", prefs.sound)
        check(Prefs.K_VIBRATE, "Вибрация", prefs.vibrate)
        num(Prefs.K_OVERLAY_Y, "Отступ карточки сверху, dp", prefs.overlayYdp.toDouble())
        check(Prefs.K_DEBUG, "Записывать экраны в журнал (нужно для настройки)", prefs.debugScreens)

        button("💾 Сохранить") { save() }

        section("Проверка без заказа")
        note("Вставь текст оффера (по строке на элемент) и посмотри, что насчитает.")
        testInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            setText("Szacowany zarobek 16,50 zł\n1,3 km\n3,9 km\nZaakceptuj")
        }
        root.addView(testInput)
        button("Посчитать") { runTest() }
        testOut = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(testOut)

        section("Журнал")
        note("Офферы и экраны Uber/Wolt. Отправь его мне — подстрою распознавание под твои экраны.")
        button("Обновить") { refreshLog() }
        button("Отправить (Telegram и т.п.)") { shareLog() }
        button("Скопировать") { copyLog() }
        button("Очистить") {
            RadarLog.clear()
            refreshLog()
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(logView)
        refreshLog()
    }

    private fun title(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun section(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun note(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(4))
        })
    }

    private fun button(t: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun num(key: String, label: String, value: Double) {
        root.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, dp(8), 0, 0) })
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(clean(value))
        }
        fields[key] = e
        root.addView(e)
    }

    private fun text(key: String, label: String, value: String, multi: Boolean) {
        root.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, dp(8), 0, 0) })
        val e = EditText(this).apply {
            inputType = if (multi) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multi) minLines = 3
            setText(value)
        }
        fields[key] = e
        root.addView(e)
    }

    private fun check(key: String, label: String, value: Boolean) {
        val cb = CheckBox(this).apply {
            text = label
            isChecked = value
        }
        checks[key] = cb
        root.addView(cb)
    }

    private fun clean(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    // ---------- действия ----------

    private fun save() {
        val ed = prefs.sp.edit()
        for ((k, e) in fields) ed.putString(k, e.text.toString())
        for ((k, cb) in checks) ed.putBoolean(k, cb.isChecked)
        ed.apply()
        toast("Сохранено")
    }

    private fun isServiceEnabled(): Boolean {
        if (RadarService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains("$packageName/")
    }

    private fun updateStatus() {
        if (isServiceEnabled()) {
            status.text = "✅ Сервис включён"
            status.setTextColor(Color.parseColor("#1B8A3A"))
        } else {
            status.text = "⛔ Сервис выключен — включи «Курьер Радар» в Спец. возможностях"
            status.setTextColor(Color.parseColor("#B3261E"))
        }
    }

    private fun findApps() {
        val found = try {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
                .map { it.packageName }
                .filter { (it.contains("wolt") || it.contains("ubercab")) && it !in Prefs.CUSTOMER_APPS }
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
        val e = fields[Prefs.K_PACKAGES] ?: return
        val current = e.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val merged = (current + found).distinct()
        e.setText(merged.joinToString("\n"))
        if (found.isEmpty()) {
            toast("Не нашёл. Включи «Слушать все приложения», открой Wolt — имя пакета появится в журнале.")
        } else {
            toast("Нашёл: " + found.joinToString() + ". Не забудь «Сохранить».")
        }
    }

    private fun runTest() {
        val lines = testInput.text.toString().lines()
        val offer = OfferParser.parse(lines)
        if (offer == null) {
            testOut.text = "Цену (zł / PLN) не нашёл."
            return
        }
        // берём то, что сейчас в полях, даже если не сохранено
        save()
        val cfg = prefs.config()
        val t = LocalDateTime.now()
        val s = Scorer.score(offer, cfg, t.hour, t.dayOfWeek.value)
        val sb = StringBuilder()
        sb.append(
            when (s.verdict) {
                pl.hm.courierradar.core.Verdict.GOOD -> "БЕРИ"
                pl.hm.courierradar.core.Verdict.MAYBE -> "СМОТРИ"
                pl.hm.courierradar.core.Verdict.BAD -> "МИМО"
            }
        ).append('\n')
        sb.append(Scorer.summary(s)).append('\n')
        if (s.notes.isNotEmpty()) sb.append(s.notes.joinToString(" · ")).append('\n')
        sb.append("км: ").append(offer.kmParts).append("  мин: ").append(offer.minuteParts).append('\n')
        sb.append("слово «принять» на экране: ").append(if (offer.looksLikeOffer) "есть" else "нет")
        if (cfg.requireOfferWords && !offer.looksLikeOffer) sb.append(" → в работе карточка не появится")
        testOut.text = sb.toString()
    }

    private fun refreshLog() {
        val t = RadarLog.tail(8_000)
        logView.text = if (t.isBlank()) "Пока пусто." else t
        logView.visibility = View.VISIBLE
    }

    private fun shareLog() {
        val t = RadarLog.tail(20_000)
        if (t.isBlank()) {
            toast("Журнал пуст")
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t)
        }
        startActivity(Intent.createChooser(i, "Отправить журнал"))
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("radar log", RadarLog.tail(20_000)))
        toast("Скопировано")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
