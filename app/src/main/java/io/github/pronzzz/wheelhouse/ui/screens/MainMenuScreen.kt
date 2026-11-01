package io.github.pronzzz.wheelhouse.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import io.github.pronzzz.wheelhouse.data.Album
import io.github.pronzzz.wheelhouse.data.ArtworkLoader
import io.github.pronzzz.wheelhouse.ui.screen.MenuListView
import io.github.pronzzz.wheelhouse.ui.screen.Row
import io.github.pronzzz.wheelhouse.ui.screen.Screen
import io.github.pronzzz.wheelhouse.ui.screen.Ui
import io.github.pronzzz.wheelhouse.util.Text
import java.util.Calendar
import kotlin.math.max

/** The top-level menu, with a preview panel on the right like the classic split screen. */
class MainMenuScreen(ui: Ui) : Screen(ui) {

    private enum class Preview { ART, NOW_PLAYING, CLOCK, INFO }

    override val title = "Wheelhouse"
    private lateinit var list: MenuListView
    private lateinit var panel: PreviewPanel
    private var previews: List<Preview> = emptyList()

    private fun buildRows(): List<Row> {
        val p = ArrayList<Preview>()
        val rows = ArrayList<Row>()
        fun add(row: Row, preview: Preview) { rows += row; p += preview }
        add(Row("Music", chevron = true, onSelect = { ui.push(Menus.music(ui)) }), Preview.ART)
        add(Row("Cover Flow", chevron = true, onSelect = { ui.push(CoverFlowScreen(ui)) }), Preview.ART)
        add(Row("Spool", chevron = true, onSelect = { ui.push(SpoolMenus.root(ui)) }), Preview.ART)
        add(Row("Extras", chevron = true, onSelect = { ui.push(Menus.extras(ui)) }), Preview.CLOCK)
        add(Row("Settings", chevron = true, onSelect = { ui.push(Menus.settings(ui)) }), Preview.INFO)
        add(Row("Shuffle Songs", onSelect = {
            ui.app.player.shuffleAll()
            if (ui.app.library.index.value.songs.isEmpty()) ui.flash("No songs yet") else ui.showNowPlaying()
        }), Preview.ART)
        if (ui.app.player.state.value.hasQueue) {
            add(Row("Now Playing", chevron = true, onSelect = { ui.showNowPlaying() }), Preview.NOW_PLAYING)
        }
        previews = p
        return rows
    }

    override fun createView(): View {
        list = MenuListView(ui).apply {
            setRows(buildRows(), keepSelection = false)
            onActivate = { i -> rows.getOrNull(i)?.onSelect?.invoke() }
            onSelectionChanged = { panel.invalidate() }
        }
        panel = PreviewPanel()
        return object : ViewGroup(ui.activity) {
            init { addView(list); addView(panel) }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val w = r - l
                val split = (w * 0.52f).toInt()
                list.layout(0, 0, split, b - t)
                panel.layout(split, 0, w, b - t)
            }
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                val w = MeasureSpec.getSize(wSpec)
                val h = MeasureSpec.getSize(hSpec)
                val split = (w * 0.52f).toInt()
                list.measure(MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                panel.measure(MeasureSpec.makeMeasureSpec(w - split, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                setMeasuredDimension(w, h)
            }
        }
    }

    private fun refresh() { if (::list.isInitialized) { list.setRows(buildRows(), keepSelection = true); panel.invalidate() } }

    override fun onRotate(steps: Int) { list.rotate(steps) }
    override fun onSelect() { list.selectedRow()?.onSelect?.invoke() }
    override fun onShown() = refresh()
    override fun onPlayerChanged() {
        val shouldHave = ui.app.player.state.value.hasQueue
        if (::list.isInitialized && shouldHave != list.rows.any { it.title == "Now Playing" }) refresh()
        if (::panel.isInitialized) panel.invalidate()
    }
    override fun onLibraryChanged() { if (::panel.isInitialized) panel.libraryChanged() }
    override fun onPaletteChanged() { if (::list.isInitialized) { list.paletteChanged(); panel.invalidate() } }

    @SuppressLint("ViewConstructor")
    private inner class PreviewPanel : View(ui.activity) {
        private val dp = ui.dp
        private val m = Matrix()
        private val img = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val shade = Paint()
        private val caption = Paint()
        private var captionFor = -1
        private val edge = Paint()
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private var albums: List<Album> = emptyList()
        private var slide = 0
        private var slideStart = SystemClock.uptimeMillis()
        private val slideMs = 7000L
        private val fadeMs = 900L
        private val artListener: () -> Unit = { invalidate() }

        init { libraryChanged() }

        fun libraryChanged() {
            albums = ui.app.library.index.value.albums.shuffled()
            slide = 0
            invalidate()
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); ui.app.artwork.addListener(artListener) }
        override fun onDetachedFromWindow() { ui.app.artwork.removeListener(artListener); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val p = ui.palette
            c.drawColor(p.placeholderBottom)
            when (previews.getOrNull(list.selected) ?: Preview.ART) {
                Preview.ART -> drawSlideshow(c)
                Preview.NOW_PLAYING -> drawNowPlaying(c)
                Preview.CLOCK -> drawClock(c)
                Preview.INFO -> drawInfo(c)
            }
            edge.shader = LinearGradient(0f, 0f, 6 * dp, 0f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 6 * dp, height.toFloat(), edge)
        }

        private fun art(a: Album): Bitmap? = ui.app.artwork.album(a.albumId, a.tracks.firstOrNull(),
            if (ui.liteMode) ArtworkLoader.Quality.LITE_LARGE else ArtworkLoader.Quality.LARGE)

        private fun drawSlideshow(c: Canvas) {
            if (albums.isEmpty()) { drawLabel(c, "Wheelhouse", "Add music to begin"); return }
            val now = SystemClock.uptimeMillis()
            var t = now - slideStart
            if (t > slideMs) { slide = (slide + 1) % albums.size; slideStart = now; t = 0 }
            val cur = albums[slide % albums.size]
            val bmp = art(cur)
            if (bmp == null && ui.app.artwork.hasNoArt(cur.albumId, if (ui.liteMode) ArtworkLoader.Quality.LITE_LARGE else ArtworkLoader.Quality.LARGE)) {
                // Skip albums without artwork quickly.
                slide = (slide + 1) % albums.size
                slideStart = now - slideMs + 300
            }
            val prev = albums[(slide - 1 + albums.size) % albums.size]
            if (!ui.liteMode && t < fadeMs && albums.size > 1) art(prev)?.let { kenBurns(c, it, (t + slideMs).toFloat() / slideMs, 255) }
            val alpha = if (ui.liteMode || t >= fadeMs) 255 else (255 * t / fadeMs).toInt()
            bmp?.let { kenBurns(c, it, t.toFloat() / slideMs, alpha) }
            // Album caption (gradient cached: this runs ~15 times a second)
            if (captionFor != height) {
                caption.shader = LinearGradient(0f, height * 0.65f, 0f, height.toFloat(), 0x00000000, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
                captionFor = height
            }
            c.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(), caption)
            text.color = 0xFFFFFFFF.toInt()
            text.textSize = height * 0.06f
            text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(ell(cur.title), 10 * dp, height - text.textSize * 1.9f, text)
            text.typeface = Typeface.DEFAULT
            text.color = 0xFFD0D3D8.toInt()
            c.drawText(ell(cur.artist), 10 * dp, height - text.textSize * 0.7f, text)
            // The pan is slow, so ~15 fps is plenty and keeps an always-on home screen cheap.
            postInvalidateDelayed(if (ui.liteMode) 1000 else if (t < fadeMs) 33 else 66)
        }

        private fun kenBurns(c: Canvas, b: Bitmap, progress: Float, alpha: Int) {
            val scale = max(width.toFloat() / b.width, height.toFloat() / b.height) * if (ui.liteMode) 1f else 1.18f
            val dw = b.width * scale - width
            val dh = b.height * scale - height
            val pr = progress.coerceIn(0f, 1f)
            m.setScale(scale, scale)
            m.postTranslate(-dw * (if (slide % 2 == 0) pr else 1 - pr), -dh * 0.5f * (1 - pr))
            img.alpha = alpha
            c.drawBitmap(b, m, img)
            img.alpha = 255
        }

        private fun drawNowPlaying(c: Canvas) {
            val t = ui.app.player.currentTrack()
            if (t == null) { drawLabel(c, "Now Playing", ""); return }
            val b = ui.app.artwork.album(t.albumId, t, if (ui.liteMode) ArtworkLoader.Quality.LITE_LARGE else ArtworkLoader.Quality.LARGE)
            b?.let {
                val scale = max(width.toFloat() / it.width, height.toFloat() / it.height)
                m.setScale(scale, scale)
                m.postTranslate((width - it.width * scale) / 2, (height - it.height * scale) / 2)
                c.drawBitmap(it, m, img)
            }
            shade.shader = LinearGradient(0f, height * 0.5f, 0f, height.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, height * 0.5f, width.toFloat(), height.toFloat(), shade)
            text.color = 0xFFFFFFFF.toInt()
            text.textSize = height * 0.065f
            text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(ell(t.title), 10 * dp, height - text.textSize * 1.9f, text)
            text.typeface = Typeface.DEFAULT
            text.color = 0xFFD0D3D8.toInt()
            c.drawText(ell(t.artist), 10 * dp, height - text.textSize * 0.7f, text)
        }

        private fun drawClock(c: Canvas) {
            val p = ui.palette
            shade.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), p.placeholderTop, p.placeholderBottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            val cal = Calendar.getInstance()
            val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", cal).toString()
            text.textAlign = Paint.Align.CENTER
            text.color = 0xFFFFFFFF.toInt()
            text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            text.textSize = width * 0.3f
            c.drawText(time, width / 2f, height * 0.5f, text)
            text.typeface = Typeface.DEFAULT
            text.textSize = height * 0.06f
            c.drawText(DateFormat.format("EEE d MMM", cal).toString(), width / 2f, height * 0.5f + text.textSize * 1.8f, text)
            text.textAlign = Paint.Align.LEFT
            postInvalidateDelayed(15_000)
        }

        private fun drawInfo(c: Canvas) {
            val idx = ui.app.library.index.value
            drawLabel(c, Text.plural(idx.songs.size, "Song"), "${Text.plural(idx.albums.size, "Album")} · ${Text.plural(idx.artists.size, "Artist")}")
        }

        private fun drawLabel(c: Canvas, a: String, b: String) {
            val p = ui.palette
            shade.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), p.placeholderTop, p.placeholderBottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            text.textAlign = Paint.Align.CENTER
            text.color = 0xFFFFFFFF.toInt()
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = height * 0.075f
            c.drawText(ell(a), width / 2f, height * 0.47f, text)
            text.typeface = Typeface.DEFAULT
            text.textSize = height * 0.055f
            c.drawText(ell(b), width / 2f, height * 0.47f + text.textSize * 1.8f, text)
            text.textAlign = Paint.Align.LEFT
        }

        private fun ell(s: String) = TextUtils.ellipsize(s, text, width - 20 * dp, TextUtils.TruncateAt.END).toString()
    }
}
