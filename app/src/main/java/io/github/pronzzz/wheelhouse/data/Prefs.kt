package io.github.pronzzz.wheelhouse.data

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place, backed by SharedPreferences. */
class Prefs(context: Context) {

    val sp: SharedPreferences = context.getSharedPreferences("wheelhouse", Context.MODE_PRIVATE)

    private val lowRamDevice: Boolean = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice || am.memoryClass <= 96
    }

    // Appearance
    var skin: String by StringPref("skin", "silver")
    var darkScreen: Boolean by BoolPref("dark_screen", false)
    var backlight: Int by IntPref("backlight", -1) // -1 = follow system, else 10..100

    // Wheel
    var clickSound: Boolean by BoolPref("click_sound", true)
    /** 0 off, 1 light, 2 medium, 3 strong. Older builds stored an on/off "haptics" flag. */
    var hapticLevel: Int
        get() = sp.getInt("haptic_level", if (sp.getBoolean("haptics", true)) 2 else 0)
        set(v) = sp.edit().putInt("haptic_level", v).apply()
    var wheelSensitivity: Int by IntPref("wheel_sensitivity", 1) // 0 low, 1 medium, 2 high

    // Appliance behaviour
    var immersive: Boolean by BoolPref("immersive", true)
    /** Keep the screen on at full brightness: 0 never, 1 while charging, 2 always. */
    var keepAwake: Int
        get() = sp.getInt("keep_awake", if (sp.getBoolean("awake_charging", true)) 1 else 0)
        set(v) = sp.edit().putInt("keep_awake", v).apply()
    /** Always-on display: the screen never sleeps and dims to a clock face when idle. */
    var alwaysOn: Boolean by BoolPref("always_on", false)
    var autoNowPlaying: Boolean by BoolPref("auto_now_playing", true)
    var showApps: Boolean by BoolPref("show_apps", true)
    var liteMode: Boolean by BoolPref("lite_mode", lowRamDevice)
    var homePromptShown: Boolean by BoolPref("home_prompt_shown", false)

    // Album artwork from the internet
    var fetchArtwork: Boolean by BoolPref("fetch_artwork", true)
    var artworkWifiOnly: Boolean by BoolPref("artwork_wifi_only", true)

    // Spool: downloads through a computer on the same Wi-Fi
    var spoolHost: String by StringPref("spool_host", "")
    var spoolPort: Int by IntPref("spool_port", 7317)
    var spoolCode: String by StringPref("spool_code", "")
    var spoolName: String by StringPref("spool_name", "")
    var spoolFormat: String by StringPref("spool_format", "m4a") // m4a or mp3

    // Playback
    var shuffle: Boolean by BoolPref("shuffle", false)
    var repeatMode: Int by IntPref("repeat", 0) // 0 off, 1 one, 2 all

    // Saved queue, restored (paused) after a reboot or app restart
    var queueIds: String by StringPref("queue_ids", "")
    var queueIndex: Int by IntPref("queue_index", 0)
    var queuePosition: Long by LongPref("queue_position", 0L)

    fun resetAppearanceAndBehaviour() {
        val keep = setOf("queue_ids", "queue_index", "queue_position", "home_prompt_shown",
            "spool_host", "spool_port", "spool_code", "spool_name")
        val e = sp.edit()
        sp.all.keys.filterNot { it in keep }.forEach { e.remove(it) }
        e.apply()
    }

    private inner class BoolPref(val key: String, val def: Boolean) {
        operator fun getValue(t: Any, p: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        operator fun setValue(t: Any, p: kotlin.reflect.KProperty<*>, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    }

    private inner class IntPref(val key: String, val def: Int) {
        operator fun getValue(t: Any, p: kotlin.reflect.KProperty<*>) = sp.getInt(key, def)
        operator fun setValue(t: Any, p: kotlin.reflect.KProperty<*>, v: Int) = sp.edit().putInt(key, v).apply()
    }

    private inner class LongPref(val key: String, val def: Long) {
        operator fun getValue(t: Any, p: kotlin.reflect.KProperty<*>) = sp.getLong(key, def)
        operator fun setValue(t: Any, p: kotlin.reflect.KProperty<*>, v: Long) = sp.edit().putLong(key, v).apply()
    }

    private inner class StringPref(val key: String, val def: String) {
        operator fun getValue(t: Any, p: kotlin.reflect.KProperty<*>): String = sp.getString(key, def) ?: def
        operator fun setValue(t: Any, p: kotlin.reflect.KProperty<*>, v: String) = sp.edit().putString(key, v).apply()
    }
}
