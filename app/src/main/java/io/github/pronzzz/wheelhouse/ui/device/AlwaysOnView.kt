package io.github.pronzzz.wheelhouse.ui.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.min

/**
 * The always-on display: a dim clock on black, drawn over everything when the player is idle.
 * Mostly black pixels, so on AMOLED phones (like the Galaxy J7 Pro) it costs very little power.
 * The content drifts to a new spot every minute so nothing burns into the screen.
 */
@SuppressLint("ViewConstructor")
class AlwaysOnView(context: Context, private val onWake: () -> Unit) : View(context) {

    var battery = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var charging = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** "Title — Artist" while music plays, else null. */
    var nowPlaying: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val dp = resources.displayMetrics.density
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = 0xFFD8D8D8.toInt()
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = 0xFF8C8C8C.toInt()
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C8C8C.toInt() }
    private val path = Path()
    private var shiftStep = 0

    private val tick = object : Runnable {
        override fun run() {
            shiftStep++
            invalidate()
            postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        contentDescription = "Always-on display. Touch to wake."
    }

    fun start() {
        removeCallbacks(tick)
        postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000)
        invalidate()
    }

    fun stop() = removeCallbacks(tick)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Swallow the whole gesture so the touch that wakes the screen does not also scroll or click.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onWake()
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cal = Calendar.getInstance()
        val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", cal).toString()
        val date = DateFormat.format("EEE, d MMM", cal).toString()

        big.textSize = min(w * 0.24f, h * 0.14f)
        small.textSize = big.textSize * 0.2f
        val (dx, dy) = AodDrift.offset(shiftStep, w * 0.18f, h * 0.22f)
        val cx = w / 2 + dx
        var y = h * 0.36f + dy

        c.drawText(time, cx, y, big)
        y += small.textSize * 2.0f
        val batteryText = if (battery >= 0) "  ·  $battery%" + if (charging) " charging" else "" else ""
        c.drawText(date + batteryText, cx, y, small)

        nowPlaying?.let { np ->
            y += small.textSize * 2.6f
            val maxW = w - 48 * dp
            val line = TextUtils.ellipsize(np, small, maxW - small.textSize * 1.6f, TextUtils.TruncateAt.END).toString()
            val tw = small.measureText(line)
            drawPlayState(c, cx - tw / 2 - small.textSize * 0.9f, y - small.textSize * 0.35f, small.textSize * 0.4f)
            c.drawText(line, cx + small.textSize * 0.4f, y, small)
        }
    }

    private fun drawPlayState(c: Canvas, x: Float, cy: Float, s: Float) {
        if (playing) {
            path.reset()
            path.moveTo(x - s * 0.6f, cy - s); path.lineTo(x + s, cy); path.lineTo(x - s * 0.6f, cy + s); path.close()
            c.drawPath(path, icon)
        } else {
            c.drawRect(x - s * 0.7f, cy - s, x - s * 0.15f, cy + s, icon)
            c.drawRect(x + s * 0.15f, cy - s, x + s * 0.7f, cy + s, icon)
        }
    }
}

/** Where the always-on content sits for each minute: a slow walk around the middle. */
object AodDrift {
    private val xs = floatArrayOf(0f, 0.5f, 1f, 0.3f, -0.4f, -1f, -0.6f, 0.2f, 0.8f, -0.2f)
    private val ys = floatArrayOf(0f, 0.6f, -0.3f, 1f, -0.8f, 0.2f, 0.9f, -1f, 0.4f, -0.5f)

    fun offset(step: Int, maxX: Float, maxY: Float): Pair<Float, Float> {
        val i = ((step % xs.size) + xs.size) % xs.size
        return xs[i] * maxX to ys[i] * maxY
    }
}
