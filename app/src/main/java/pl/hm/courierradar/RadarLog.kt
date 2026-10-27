package pl.hm.courierradar

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал: офферы с расчётом и (в режиме отладки) тексты экранов Uber/Wolt.
 * Его можно отправить в Telegram — по нему подстраивается парсер.
 */
object RadarLog {

    private const val MAX_BYTES = 400_000L
    private const val KEEP_CHARS = 200_000
    private var file: File? = null
    private val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "radar_log.txt")
    }

    @Synchronized
    fun add(tag: String, body: String) {
        val f = file ?: return
        try {
            f.appendText("[" + time.format(Date()) + "] " + tag + "\n" + body.trimEnd() + "\n\n")
            if (f.length() > MAX_BYTES) {
                val all = f.readText()
                f.writeText(all.takeLast(KEEP_CHARS))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(): String {
        val f = file ?: return ""
        return try {
            if (f.exists()) f.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Последние записи, не длиннее maxChars — чтобы влезло в сообщение. */
    fun tail(maxChars: Int): String {
        val all = read()
        if (all.length <= maxChars) return all
        val cut = all.takeLast(maxChars)
        val i = cut.indexOf("\n[")
        return if (i >= 0) cut.substring(i + 1) else cut
    }

    @Synchronized
    fun clear() {
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
    }
}
