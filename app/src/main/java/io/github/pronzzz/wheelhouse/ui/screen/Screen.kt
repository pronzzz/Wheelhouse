package io.github.pronzzz.wheelhouse.ui.screen

import android.app.Activity
import android.view.View
import io.github.pronzzz.wheelhouse.WheelhouseApp
import io.github.pronzzz.wheelhouse.ui.device.Palette

/** What screens can ask of the surrounding device. Implemented by MainActivity. */
interface Ui {
    val activity: Activity
    val app: WheelhouseApp
    val palette: Palette
    val dp: Float
    val liteMode: Boolean
    fun push(screen: Screen)
    fun pop()
    fun popToRoot()
    fun showNowPlaying()
    fun flash(message: String)
    fun settingsChanged()
    /** Plays one haptic pulse at the current level. Returns false if the phone has no vibrator. */
    fun previewHaptics(): Boolean
    fun refreshTitle()
}

/** One page on the device screen. Receives wheel input while it is on top. */
abstract class Screen(protected val ui: Ui) {
    abstract val title: String

    protected abstract fun createView(): View
    private var cached: View? = null
    val view: View get() = cached ?: createView().also { cached = it }

    /** Positive = clockwise. */
    open fun onRotate(steps: Int) {}
    open fun onSelect() {}
    open fun onSelectLong() {}
    /** Return true if the screen handled MENU itself (e.g. leaving a sub-mode). */
    open fun onBack(): Boolean = false
    open fun onShown() {}
    open fun onHidden() {}
    open fun onDisposed() {}
    open fun onLibraryChanged() {}
    open fun onPlayerChanged() {}
    open fun onPaletteChanged() { cached?.invalidate() }

    /** Whether the device may jump to Now Playing after a period of inactivity. */
    open val allowsIdleNowPlaying: Boolean get() = true
    open val isNowPlaying: Boolean get() = false
}
