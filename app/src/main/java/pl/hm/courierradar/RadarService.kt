package pl.hm.courierradar

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import pl.hm.courierradar.core.OfferParser
import pl.hm.courierradar.core.Score
import pl.hm.courierradar.core.Scorer
import pl.hm.courierradar.core.Verdict
import java.time.LocalDateTime

/**
 * Читает текст на экране Uber Driver / Wolt и оценивает оффер.
 * ВАЖНО: сервис ничего не нажимает и не принимает заказы — решение всегда за курьером.
 */
class RadarService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RadarService? = null
            private set

        private val DIGITS = Regex("\\d")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var overlay: OverlayCard? = null
    private var tone: ToneGenerator? = null

    private var scanQueued = false
    private var lastSig = ""
    private var lastSigAt = 0L
    private var missesInRow = 0
    private val recentScreens = ArrayDeque<Int>()

    private val scanTask = Runnable {
        scanQueued = false
        scan()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        RadarLog.init(this)
        overlay = OverlayCard(this) { prefs.overlayYdp }
        tone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (_: Exception) {
            null
        }
        RadarLog.add("СЕРВИС", "включён, слушаю: " + prefs.packages().joinToString())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!prefs.listenAll && pkg !in prefs.packages()) return
        if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(scanTask, 250)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(scanTask)
        overlay?.hide()
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun isWatched(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return false
        return prefs.listenAll || pkg in prefs.packages()
    }

    private fun scan() {
        val texts = ArrayList<String>()
        var seenPkg = ""

        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                roots += r
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            try {
                rootInActiveWindow?.let { roots += it }
            } catch (_: Exception) {
            }
        }
        for (root in roots) {
            val p = root.packageName?.toString() ?: ""
            if (!isWatched(p)) continue
            seenPkg = p
            collect(root, texts, 0)
        }

        if (texts.isEmpty()) {
            onNoOffer()
            return
        }

        if (prefs.debugScreens) logScreen(seenPkg, texts)

        val offer = OfferParser.parse(texts)
        val cfg = prefs.config()
        val valid = offer != null &&
            (offer.km != null || offer.minutes != null) &&
            (!cfg.requireOfferWords || offer.looksLikeOffer)
        if (offer == null || !valid) {
            onNoOffer()
            return
        }
        missesInRow = 0

        val sig = offer.price.toString() + "|" + offer.km + "|" + offer.minutes
        val now = SystemClock.elapsedRealtime()
        if (sig == lastSig && now - lastSigAt < 90_000 && overlay?.isShowing == true) return
        val isNew = sig != lastSig || now - lastSigAt >= 90_000
        lastSig = sig
        lastSigAt = now

        val t = LocalDateTime.now()
        val score = Scorer.score(offer, cfg, t.hour, t.dayOfWeek.value)
        overlay?.show(score)
        if (isNew) {
            feedback(score)
            RadarLog.add(
                "ОФФЕР " + score.verdict + " (" + seenPkg + ")",
                Scorer.summary(score) +
                    (if (score.notes.isEmpty()) "" else "\n" + score.notes.joinToString(" · ")) +
                    "\nкм: " + offer.kmParts + "  мин: " + offer.minuteParts,
            )
        }
    }

    /** Карточку прячем, только если оффера нет два скана подряд — чтобы не мигала. */
    private fun onNoOffer() {
        missesInRow++
        if (missesInRow >= 2 && overlay?.isShowing == true) {
            val shownFor = SystemClock.elapsedRealtime() - lastSigAt
            if (shownFor > 2_500) overlay?.hide()
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 400) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) out += t
        if (d.isNotEmpty() && d != t) out += d
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, out, depth + 1)
        }
    }

    /** Пишет в журнал каждый новый экран (без повторов), чтобы по нему подстроить парсер. */
    private fun logScreen(pkg: String, texts: List<String>) {
        val body = texts.joinToString(" | ")
        // цифры не учитываем: тикающий таймер оффера не должен плодить записи
        val h = body.replace(DIGITS, "#").hashCode()
        if (h in recentScreens) return
        recentScreens.addLast(h)
        while (recentScreens.size > 50) recentScreens.removeFirst()
        RadarLog.add("ЭКРАН $pkg", body.take(1500))
    }

    private fun feedback(s: Score) {
        if (prefs.sound) {
            try {
                when (s.verdict) {
                    Verdict.GOOD -> tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400)
                    Verdict.MAYBE -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    Verdict.BAD -> tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                }
            } catch (_: Exception) {
            }
        }
        if (prefs.vibrate) {
            try {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val effect = when (s.verdict) {
                    Verdict.GOOD -> VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE)
                    Verdict.MAYBE -> VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                    Verdict.BAD -> VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
                }
                v?.vibrate(effect)
            } catch (_: Exception) {
            }
        }
    }

    /** Кнопка «Показать пример» в настройках. */
    fun showTest() {
        val offer = OfferParser.parse(
            listOf("Nowe zlecenie", "Szacowany zarobek 19,50 zł", "Odbiór: Złote Tarasy", "1,4 km", "3,1 km", "Zaakceptuj")
        ) ?: return
        val t = LocalDateTime.now()
        val score = Scorer.score(offer, prefs.config(), t.hour, t.dayOfWeek.value)
        handler.post {
            overlay?.show(score)
            feedback(score)
        }
    }
}
