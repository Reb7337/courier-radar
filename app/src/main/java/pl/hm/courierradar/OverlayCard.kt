package pl.hm.courierradar

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import pl.hm.courierradar.core.Score
import pl.hm.courierradar.core.Scorer
import pl.hm.courierradar.core.Verdict

/**
 * Карточка поверх экрана. Только показывает — ничего не нажимает.
 * Тап по карточке — скрыть. Сама скрывается через 30 с.
 * Создаётся из AccessibilityService: окно TYPE_ACCESSIBILITY_OVERLAY не требует
 * отдельного разрешения «поверх других приложений».
 */
class OverlayCard(private val ctx: Context, private val yDp: () -> Int) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hideTask = Runnable { hide() }

    val isShowing: Boolean get() = view != null

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(s: Score) {
        val (title, color) = when (s.verdict) {
            Verdict.GOOD -> "БЕРИ" to Color.parseColor("#1B8A3A")
            Verdict.MAYBE -> "СМОТРИ" to Color.parseColor("#C98A00")
            Verdict.BAD -> "МИМО" to Color.parseColor("#B3261E")
        }
        val notes = s.notes.joinToString(" · ")
        showRaw(title, Scorer.summary(s), notes, color)
    }

    fun showRaw(title: String, summary: String, notes: String, color: Int) {
        hide()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color)
            }
            alpha = 0.94f
            setOnClickListener { hide() }
        }
        box.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(ctx).apply {
            text = summary
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        if (notes.isNotEmpty()) {
            box.addView(TextView(ctx).apply {
                text = notes
                setTextColor(Color.parseColor("#F0F0F0"))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(yDp())
            horizontalMargin = 0.03f
        }
        try {
            wm.addView(box, lp)
            view = box
            handler.postDelayed(hideTask, 30_000)
        } catch (_: Exception) {
            view = null
        }
    }

    fun hide() {
        handler.removeCallbacks(hideTask)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }
}
