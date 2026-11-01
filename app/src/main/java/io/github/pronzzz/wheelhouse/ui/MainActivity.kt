package io.github.pronzzz.wheelhouse.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.pronzzz.wheelhouse.WheelhouseApp
import io.github.pronzzz.wheelhouse.launcher.HomeRole
import io.github.pronzzz.wheelhouse.spool.SpoolLinks
import io.github.pronzzz.wheelhouse.ui.screens.SpoolMenus
import io.github.pronzzz.wheelhouse.ui.device.AlwaysOnView
import io.github.pronzzz.wheelhouse.ui.device.ClickWheelView
import io.github.pronzzz.wheelhouse.ui.device.DeviceLayout
import io.github.pronzzz.wheelhouse.ui.device.Feedback
import io.github.pronzzz.wheelhouse.ui.device.Palette
import io.github.pronzzz.wheelhouse.ui.device.Skins
import io.github.pronzzz.wheelhouse.ui.device.WheelButton
import io.github.pronzzz.wheelhouse.ui.device.WheelListener
import io.github.pronzzz.wheelhouse.ui.screen.Screen
import io.github.pronzzz.wheelhouse.ui.screen.ScreenHost
import io.github.pronzzz.wheelhouse.ui.screen.Ui
import io.github.pronzzz.wheelhouse.ui.screens.MainMenuScreen
import io.github.pronzzz.wheelhouse.ui.screens.Menus
import io.github.pronzzz.wheelhouse.ui.screens.NowPlayingScreen
import io.github.pronzzz.wheelhouse.ui.screens.TextEntryScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The only activity. It is both a normal app entry and a HOME screen, so the phone can boot
 * straight into the music player. Everything on screen is drawn by our own views.
 */
class MainActivity : Activity(), Ui, WheelListener {

    override val app: WheelhouseApp get() = application as WheelhouseApp
    override val activity: Activity get() = this
    override var palette: Palette = Palette.LIGHT
        private set
    override val dp: Float by lazy { resources.displayMetrics.density }
    override val liteMode: Boolean get() = app.prefs.liteMode

    private lateinit var device: DeviceLayout
    private lateinit var host: ScreenHost
    private lateinit var wheel: ClickWheelView
    private lateinit var feedback: Feedback
    private lateinit var aod: AlwaysOnView
    private var aodActive = false
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var lastInput = SystemClock.uptimeMillis()
    private var charging = false
    private var resumed = false
    private var onboarding = false
    private var seekDirection = 0
    private var seekCount = 0

    // ---- Lifecycle ----------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedback = Feedback(this, app.prefs)
        host = ScreenHost(this)
        wheel = ClickWheelView(this, feedback).also { it.listener = this }
        device = DeviceLayout(this, host, wheel)
        aod = AlwaysOnView(this) { exitAlwaysOn() }.also { it.visibility = android.view.View.GONE }
        setContentView(FrameLayout(this).apply {
            addView(device)
            addView(aod)
        })
        volumeControlStream = AudioManager.STREAM_MUSIC

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(device) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        applySettings()
        app.player.connect()
        val granted = hasAudioPermission()
        app.library.start(granted)
        if (granted) showStart() else showWelcome(permanentlyDenied = false)
        handleShare(intent)

        scope.launch {
            app.player.state.collect { st ->
                host.statusBar.playState = when {
                    !st.hasQueue -> 0
                    st.isPlaying || st.playWhenReady -> 1
                    else -> 2
                }
                host.statusBar.sleeping = app.player.sleepTimer.active
                host.screens.forEach { it.onPlayerChanged() }
                aod.playing = st.isPlaying || st.playWhenReady
                aod.nowPlaying = app.player.currentTrack()?.takeIf { st.hasQueue }?.let { "${it.title} — ${it.artist}" }
            }
        }
        scope.launch {
            app.network.state.collect { net -> host.statusBar.wifi = net.online && net.wifi }
        }
        scope.launch {
            app.library.version.collect { host.screens.forEach { it.onLibraryChanged() } }
        }
        handler.post(idleCheck)
    }

    override fun onStart() {
        super.onStart()
        val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { onBattery(it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        app.network.refresh()
        // Turning the screen back on with the power button should show the player, not the dim clock.
        if (aodActive) exitAlwaysOn()
        applyImmersive()
        // Came back from system settings after granting access?
        if (onboarding && hasAudioPermission()) {
            app.library.start(true)
            showStart()
        }
        host.top?.onShown()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(batteryReceiver) }
        app.player.saveQueue()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        feedback.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /** Pressing Home while Wheelhouse is already showing returns to the main menu. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (resumed && intent.hasCategory(Intent.CATEGORY_HOME) && !onboarding) host.popToRoot()
        handleShare(intent)
    }

    /** A YouTube link shared from another app opens in Spool, ready to download. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || onboarding) return
        val url = SpoolLinks.extract(intent.getStringExtra(Intent.EXTRA_TEXT))
        intent.action = null // handled; don't reopen it if Android recreates the activity
        if (aodActive) exitAlwaysOn()
        markInput()
        if (url == null) flash("No YouTube Link Found") else host.push(SpoolMenus.link(this, url))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        markInput()
        if (!goBack()) {
            // As the home screen there is nothing "behind" us. As a normal app, step aside.
            if (!HomeRole.isDefaultHome(this)) moveTaskToBack(true)
        }
    }

    // ---- Start-up flow ------------------------------------------------------------------------

    private fun audioPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return checkSelfPermission(audioPermissions()[0]) == PackageManager.PERMISSION_GRANTED
    }

    private fun showWelcome(permanentlyDenied: Boolean) {
        onboarding = true
        host.setRoot(Menus.welcome(this, permanentlyDenied) {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(audioPermissions(), REQ_AUDIO)
        })
    }

    private fun showStart() {
        onboarding = false
        if (!app.prefs.homePromptShown && !HomeRole.isDefaultHome(this)) {
            host.setRoot(Menus.homePrompt(this) {
                app.prefs.homePromptShown = true
                host.setRoot(MainMenuScreen(this))
            })
        } else {
            host.setRoot(MainMenuScreen(this))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (hasAudioPermission()) {
            app.library.start(true)
            showStart()
        } else {
            val canAskAgain = Build.VERSION.SDK_INT >= 23 && shouldShowRequestPermissionRationale(audioPermissions()[0])
            showWelcome(permanentlyDenied = !canAskAgain)
            flash("Music access is needed")
        }
    }

    // ---- Settings -----------------------------------------------------------------------------

    private fun applySettings() {
        val prefs = app.prefs
        val skin = Skins.byId(prefs.skin)
        palette = if (prefs.darkScreen) Palette.DARK else Palette.LIGHT
        device.skin = skin
        wheel.skin = skin
        wheel.sensitivity = prefs.wheelSensitivity
        host.palette = palette
        host.animate = !prefs.liteMode
        window.attributes = window.attributes.apply {
            screenBrightness = if (prefs.backlight < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.backlight / 100f
        }
        applyKeepAwake()
        applyImmersive()
    }

    private fun applyImmersive() {
        if (aodActive) return
        val c = WindowCompat.getInsetsController(window, window.decorView)
        val darkChrome = Skins.byId(app.prefs.skin).darkChrome
        c.isAppearanceLightStatusBars = !darkChrome
        c.isAppearanceLightNavigationBars = !darkChrome
        if (app.prefs.immersive) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyKeepAwake() {
        val prefs = app.prefs
        val on = prefs.alwaysOn || prefs.keepAwake == 2 || (prefs.keepAwake == 1 && charging)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!prefs.alwaysOn && aodActive) exitAlwaysOn()
    }

    // ---- Always-on display --------------------------------------------------------------------

    private fun enterAlwaysOn() {
        if (aodActive) return
        aodActive = true
        aod.visibility = android.view.View.VISIBLE
        aod.start()
        window.attributes = window.attributes.apply { screenBrightness = AOD_BRIGHTNESS }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitAlwaysOn() {
        if (!aodActive) return
        aodActive = false
        aod.stop()
        aod.visibility = android.view.View.GONE
        markInput()
        applySettings()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { intent?.let { onBattery(it) } }
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) host.statusBar.battery = level * 100 / scale
        host.statusBar.charging = charging
        aod.battery = host.statusBar.battery
        aod.charging = charging
        applyKeepAwake()
    }

    // ---- Ui -----------------------------------------------------------------------------------

    override fun push(screen: Screen) = host.push(screen)
    override fun pop() { host.pop() }
    override fun popToRoot() = host.popToRoot()
    override fun refreshTitle() = host.refreshTitle()
    override fun flash(message: String) = host.flash(message)

    override fun settingsChanged() = applySettings()

    override fun previewHaptics(): Boolean {
        feedback.preview()
        return feedback.hasVibrator
    }

    override fun showNowPlaying() {
        if (host.top?.isNowPlaying == true) return
        host.push(NowPlayingScreen(this))
    }

    private fun goBack(): Boolean {
        val top = host.top ?: return false
        if (top.onBack()) return true
        return host.pop()
    }

    // ---- Wheel --------------------------------------------------------------------------------

    private fun markInput() { lastInput = SystemClock.uptimeMillis() }

    override fun onWheelRotate(steps: Int) {
        markInput()
        host.top?.onRotate(steps)
    }

    override fun onWheelButton(button: WheelButton) {
        markInput()
        when (button) {
            WheelButton.MENU -> goBack()
            WheelButton.SELECT -> host.top?.onSelect()
            WheelButton.PLAY -> app.player.togglePlay()
            WheelButton.NEXT -> app.player.next()
            WheelButton.PREV -> app.player.previous()
        }
    }

    override fun onWheelLongPress(button: WheelButton) {
        markInput()
        when (button) {
            WheelButton.MENU -> if (!onboarding) host.popToRoot()
            WheelButton.SELECT -> host.top?.onSelectLong()
            WheelButton.PLAY -> if (!onboarding) showNowPlaying()
            WheelButton.NEXT -> startSeeking(+1)
            WheelButton.PREV -> startSeeking(-1)
        }
    }

    override fun onWheelHoldEnd(button: WheelButton) {
        seekDirection = 0
        handler.removeCallbacks(seekStep)
    }

    /** Holding ⏮/⏭ rewinds or fast-forwards, speeding up the longer you hold. */
    private fun startSeeking(dir: Int) {
        seekDirection = dir
        seekCount = 0
        handler.post(seekStep)
    }

    private val seekStep = object : Runnable {
        override fun run() {
            if (seekDirection == 0) return
            seekCount++
            val step = when { seekCount < 8 -> 3_000L; seekCount < 20 -> 8_000L; else -> 20_000L }
            app.player.seekBy(seekDirection * step)
            markInput()
            handler.postDelayed(this, 220)
        }
    }

    /** After a while without input during playback, show Now Playing, like the original. */
    private val idleCheck = object : Runnable {
        override fun run() {
            val top = host.top
            val st = app.player.state.value
            val idle = SystemClock.uptimeMillis() - lastInput
            if (app.prefs.alwaysOn && !aodActive && !onboarding && resumed && idle > AOD_IDLE_MS) {
                enterAlwaysOn()
            }
            if (app.prefs.autoNowPlaying && st.isPlaying && top != null && !top.isNowPlaying &&
                top.allowsIdleNowPlaying && top !is TextEntryScreen && !onboarding &&
                SystemClock.uptimeMillis() - lastInput > IDLE_MS
            ) {
                showNowPlaying()
            }
            handler.postDelayed(this, 5_000)
        }
    }

    /** Touches on the screen itself (not just the wheel) count as activity too. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) markInput()
        return super.dispatchTouchEvent(ev)
    }

    // ---- Keyboards, D-pads, TV remotes --------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (aodActive && !isVolumeKey(event.keyCode)) {
            // Any key just wakes the always-on display; it does not also act.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) exitAlwaysOn()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { onWheelRotate(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> { onWheelRotate(1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.isLongPress) onWheelLongPress(WheelButton.SELECT) else if (event.repeatCount == 0) onWheelButton(WheelButton.SELECT)
                true
            }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> { markInput(); goBack(); true }
            KeyEvent.KEYCODE_SPACE -> { onWheelButton(WheelButton.PLAY); true }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    private fun isVolumeKey(code: Int) =
        code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HomeRole.REQUEST_CODE) {
            flash(if (HomeRole.isDefaultHome(this)) "Wheelhouse is now Home" else "Home not changed")
            host.top?.onShown()
        }
    }

    companion object {
        private const val REQ_AUDIO = 10
        private const val IDLE_MS = 30_000L
        /** Idle time before the always-on display dims to its clock face. */
        private const val AOD_IDLE_MS = 60_000L
        private const val AOD_BRIGHTNESS = 0.04f
    }
}
