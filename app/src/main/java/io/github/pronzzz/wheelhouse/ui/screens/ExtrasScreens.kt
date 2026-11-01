package io.github.pronzzz.wheelhouse.ui.screens

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.View
import io.github.pronzzz.wheelhouse.ui.screen.Screen
import io.github.pronzzz.wheelhouse.ui.screen.Ui
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A big bedside / desk clock. Turn the wheel to switch between analog and digital. */
class ClockScreen(ui: Ui) : Screen(ui) {
    override val title = "Clock"
    override val allowsIdleNowPlaying get() = false
    private var analog = true
    private lateinit var v: View

    override fun onRotate(steps: Int) { analog = !analog; v.invalidate() }
    override fun onSelect() { ui.app.player.togglePlay() }

    override fun createView(): View = object : View(ui.activity) {
        private val dp = ui.dp
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val tick = object : Runnable {
            override fun run() { invalidate(); postDelayed(this, 1000 - System.currentTimeMillis() % 1000) }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val pal = ui.palette
            c.drawColor(pal.bg)
            val cal = Calendar.getInstance()
            val w = width.toFloat()
            val h = height.toFloat()
            val is24 = DateFormat.is24HourFormat(context)
            val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", cal).toString()
            val date = DateFormat.format("EEEE, d MMMM", cal).toString()
            val nowPlaying = ui.app.player.currentTrack()?.takeIf { ui.app.player.state.value.isPlaying }
            if (analog) {
                val r = min(w, h * 0.78f) * 0.40f
                val cx = w / 2
                val cy = h * 0.42f
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * dp
                p.color = pal.text
                c.drawCircle(cx, cy, r, p)
                for (i in 0 until 60) {
                    val a = Math.toRadians(i * 6.0 - 90)
                    val inner = if (i % 5 == 0) r * 0.86f else r * 0.93f
                    p.strokeWidth = if (i % 5 == 0) 2.4f * dp else 1f * dp
                    p.color = if (i % 5 == 0) pal.text else pal.secondary
                    c.drawLine(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                        cx + (r * 0.97f * cos(a)).toFloat(), cy + (r * 0.97f * sin(a)).toFloat(), p)
                }
                val hour = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60.0
                val min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60.0
                val sec = cal.get(Calendar.SECOND).toDouble()
                hand(c, cx, cy, hour * 30, r * 0.5f, 4.5f * dp, pal.text)
                hand(c, cx, cy, min * 6, r * 0.75f, 3f * dp, pal.text)
                hand(c, cx, cy, sec * 6, r * 0.82f, 1.4f * dp, 0xFFE5484D.toInt())
                p.style = Paint.Style.FILL
                p.color = 0xFFE5484D.toInt()
                c.drawCircle(cx, cy, 3.5f * dp, p)
                t.color = pal.secondary
                t.textSize = h * 0.065f
                c.drawText(date, cx, cy + r + t.textSize * 1.6f, t)
            } else {
                t.color = pal.text
                t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                t.textSize = min(w * 0.30f, h * 0.36f)
                c.drawText(time, w / 2, h * 0.48f, t)
                t.typeface = Typeface.DEFAULT
                t.color = pal.secondary
                t.textSize = h * 0.07f
                c.drawText(date, w / 2, h * 0.48f + t.textSize * 1.9f, t)
            }
            nowPlaying?.let {
                t.color = pal.secondary
                t.textSize = h * 0.055f
                val line = TextUtils.ellipsize("♪ ${it.title} — ${it.artist}", t, w - 24 * dp, TextUtils.TruncateAt.END)
                c.drawText(line.toString(), w / 2, h - t.textSize * 1.2f, t)
            }
        }

        private fun hand(c: Canvas, cx: Float, cy: Float, deg: Double, len: Float, width: Float, color: Int) {
            val a = Math.toRadians(deg - 90)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = width
            p.color = color
            c.drawLine(cx, cy, cx + (len * cos(a)).toFloat(), cy + (len * sin(a)).toFloat(), p)
            p.strokeCap = Paint.Cap.BUTT
        }
    }.also { v = it }
}

/** Select starts/stops, hold Select to reset. */
class StopwatchScreen(ui: Ui) : Screen(ui) {
    override val title = "Stopwatch"
    override val allowsIdleNowPlaying get() = false
    private var running = false
    private var startedAt = 0L
    private var accumulated = 0L
    private val laps = ArrayList<Long>()
    private lateinit var v: View

    private fun elapsed() = accumulated + if (running) SystemClock.elapsedRealtime() - startedAt else 0

    override fun onSelect() {
        if (running) { accumulated = elapsed(); running = false } else { startedAt = SystemClock.elapsedRealtime(); running = true }
        v.invalidate()
    }

    override fun onRotate(steps: Int) {
        if (running && steps > 0) { laps.add(0, elapsed()); if (laps.size > 20) laps.removeAt(laps.lastIndex); v.invalidate() }
    }

    override fun onSelectLong() {
        running = false; accumulated = 0; laps.clear(); v.invalidate()
        ui.flash("Reset")
    }

    override fun createView(): View = object : View(ui.activity) {
        private val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        override fun onDraw(c: Canvas) {
            val pal = ui.palette
            c.drawColor(pal.bg)
            val w = width.toFloat()
            val h = height.toFloat()
            t.color = pal.text
            t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            t.textSize = min(w * 0.17f, h * 0.24f)
            c.drawText(format(elapsed()), w / 2, h * 0.36f, t)
            t.typeface = Typeface.DEFAULT
            t.textSize = h * 0.058f
            t.color = pal.secondary
            val hint = if (running) "Select: Stop · Turn: Lap" else "Select: Start · Hold Select: Reset"
            c.drawText(hint, w / 2, h * 0.36f + t.textSize * 2f, t)
            t.color = pal.text
            var y = h * 0.62f
            for ((i, lap) in laps.withIndex()) {
                if (y > h - 4) break
                c.drawText("Lap ${laps.size - i}   ${format(lap)}", w / 2, y, t)
                y += t.textSize * 1.35f
            }
            if (running) postInvalidateDelayed(50)
        }
    }.also { v = it }

    private fun format(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = (ms / 1000) % 60
        val m = (ms / 60_000)
        return String.format(Locale.ROOT, "%02d:%02d.%02d", m, s, cs)
    }
}

/**
 * Text entry without a keyboard: turn the wheel to pick a character, press Select to type it.
 * Characters can also be tapped directly.
 */
class TextEntryScreen(
    ui: Ui,
    override val title: String,
    initial: String,
    private val hint: (String) -> String,
    /** The characters on the wheel strip. SPACE, DEL and OK are added automatically. */
    alphabet: String = LETTERS,
    /** Keep letters in capitals (pairing codes) instead of sentence-style lower case. */
    private val upperCase: Boolean = false,
    private val maxLength: Int = 40,
    private val onDone: (String) -> Unit,
) : Screen(ui) {

    private val chars: List<String> = alphabet.map { it.toString() } + listOfNotNull(SPACE.takeIf { alphabet == LETTERS }, DEL, DONE)
    private var text = initial
    private var index = 0
    private lateinit var v: EntryView

    override val allowsIdleNowPlaying get() = false

    override fun onRotate(steps: Int) {
        index = ((index + steps) % chars.size + chars.size) % chars.size
        v.invalidate()
    }

    override fun onSelect() = type(chars[index])
    override fun onSelectLong() = type(DONE)

    private fun type(ch: String) {
        when (ch) {
            DEL -> text = text.dropLast(1)
            SPACE -> if (text.isNotEmpty() && !text.endsWith(" ")) text += " "
            DONE -> { onDone(text.trim()); return }
            else -> if (text.length < maxLength) text += if (upperCase || text.isEmpty() || text.endsWith(" ")) ch else ch.lowercase()
        }
        v.invalidate()
    }

    override fun createView(): View = EntryView().also { v = it }

    @SuppressLint("ViewConstructor")
    inner class EntryView : View(ui.activity) {
        private val dp = ui.dp
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private var cellW = 1f
        private var stripTop = 0f

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.action == android.view.MotionEvent.ACTION_UP && e.y >= stripTop) {
                val offset = ((e.x - width / 2f) / cellW).let { if (it < 0) it - 0.5f else it + 0.5f }.toInt()
                index = ((index + offset) % chars.size + chars.size) % chars.size
                type(chars[index])
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val pal = ui.palette
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(pal.bg)
            val pad = 12 * dp
            // Text field
            p.color = pal.trackBg
            c.drawRoundRect(pad, pad, w - pad, pad + h * 0.2f, 6 * dp, 6 * dp, p)
            t.color = pal.text
            t.textSize = h * 0.09f
            t.textAlign = Paint.Align.LEFT
            val shown = TextUtils.ellipsize(text, t, w - 4 * pad, TextUtils.TruncateAt.START).toString()
            val baseline = pad + h * 0.1f - (t.ascent() + t.descent()) / 2
            c.drawText(shown, pad * 2, baseline, t)
            val cursorX = pad * 2 + t.measureText(shown) + 2 * dp
            if ((SystemClock.uptimeMillis() / 500) % 2 == 0L) c.drawRect(cursorX, pad + h * 0.05f, cursorX + 2 * dp, pad + h * 0.15f, p.apply { color = pal.selBottom })
            postInvalidateDelayed(500)
            // Hint / result count
            t.color = pal.secondary
            t.textSize = h * 0.06f
            t.textAlign = Paint.Align.CENTER
            c.drawText(hint(text), w / 2, pad + h * 0.2f + t.textSize * 2f, t)
            c.drawText("Hold Select or choose OK when done", w / 2, pad + h * 0.2f + t.textSize * 3.6f, t)
            // Character strip
            val stripH = h * 0.26f
            stripTop = h - stripH - pad
            cellW = w / 7.5f
            for (k in -4..4) {
                val i = ((index + k) % chars.size + chars.size) % chars.size
                val cx = w / 2 + k * cellW
                if (k == 0) {
                    p.shader = android.graphics.LinearGradient(0f, stripTop, 0f, stripTop + stripH, pal.selTop, pal.selBottom, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRoundRect(cx - cellW / 2 + 2 * dp, stripTop, cx + cellW / 2 - 2 * dp, stripTop + stripH, 8 * dp, 8 * dp, p)
                    p.shader = null
                    t.color = pal.selText
                } else {
                    t.color = if (kotlin.math.abs(k) > 2) pal.secondary else pal.text
                }
                val label = chars[i]
                t.textSize = if (label.length > 1) stripH * 0.28f else stripH * 0.5f
                c.drawText(label, cx, stripTop + stripH / 2 - (t.ascent() + t.descent()) / 2, t)
            }
        }
    }

    companion object {
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val SPACE = "␣"
        const val DEL = "⌫"
        const val DONE = "OK"
    }
}
