package io.github.pronzzz.wheelhouse.ui.device

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import io.github.pronzzz.wheelhouse.data.Prefs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The wheel's tactile "click": a tiny synthesized tick plus a haptic pulse.
 * The sound is generated at first launch, so no third-party audio is bundled.
 *
 * Haptics drive the vibrator directly instead of using View.performHapticFeedback. The
 * system "clock tick" effect is silently dropped on many phones: custom ROMs such as
 * LineageOS on older Samsungs map it to an empty pattern, and Android skips it whenever
 * the system "touch vibration" switch is off. A short one-shot pulse works everywhere.
 */
class Feedback(context: Context, private val prefs: Prefs) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private val amplitudeControl = Build.VERSION.SDK_INT >= 26 && vibrator?.hasAmplitudeControl() == true
    private var lastBuzz = 0L
    private val mediaAudio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

    private var tickId = 0
    private var pressId = 0

    init {
        val dir = context.cacheDir
        tickId = pool.load(writeWav(File(dir, "tick_v1.wav"), 3600.0, 0.0011, 0.35).path, 1)
        pressId = pool.load(writeWav(File(dir, "press_v1.wav"), 1700.0, 0.0024, 0.45).path, 1)
    }

    val hasVibrator: Boolean get() = vibrator != null

    fun tick(view: View) {
        if (prefs.clickSound) pool.play(tickId, 0.55f, 0.55f, 1, 0, 1f)
        buzz(Haptics.tick(prefs.hapticLevel, amplitudeControl), throttle = true)
    }

    fun press(view: View) {
        if (prefs.clickSound) pool.play(pressId, 0.7f, 0.7f, 1, 0, 1f)
        buzz(Haptics.press(prefs.hapticLevel, amplitudeControl), throttle = false)
    }

    /** A single pulse at the given level, used to preview the setting. */
    fun preview() = buzz(Haptics.press(prefs.hapticLevel, amplitudeControl), throttle = false)

    private fun buzz(pulse: Haptics.Pulse?, throttle: Boolean) {
        val v = vibrator ?: return
        pulse ?: return
        val now = SystemClock.uptimeMillis()
        // When the wheel spins fast, ticks arrive quicker than an old motor can start and stop.
        // Skipping a few keeps every pulse distinct instead of blurring into one long buzz.
        if (throttle && now - lastBuzz < pulse.ms + Haptics.GAP_MS) return
        lastBuzz = now
        try {
            val effect = if (Build.VERSION.SDK_INT >= 26) {
                VibrationEffect.createOneShot(pulse.ms, pulse.amplitude ?: VibrationEffect.DEFAULT_AMPLITUDE)
            } else null
            // Without a usage Android files short pulses under "touch feedback", which the system
            // switch can mute. Tagging them as media leaves Wheelhouse's own Haptics setting in charge.
            when {
                Build.VERSION.SDK_INT >= 33 -> v.vibrate(effect!!, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
                Build.VERSION.SDK_INT >= 26 -> @Suppress("DEPRECATION") v.vibrate(effect, mediaAudio)
                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(pulse.ms)
                }
            }
        } catch (_: Exception) {
            // A missing VIBRATE permission or a broken HAL must never crash the wheel.
        }
    }

    fun release() = pool.release()

    /** A short decaying sine + noise burst, written as 16-bit mono PCM WAV. */
    private fun writeWav(file: File, freq: Double, decay: Double, noise: Double): File {
        if (file.exists() && file.length() > 44) return file
        val rate = 44_100
        val n = (rate * 0.012).toInt()
        val rnd = Random(7)
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = exp(-t / decay)
            val v = env * ((1 - noise) * sin(2 * PI * freq * t) + noise * (rnd.nextDouble() * 2 - 1))
            (v * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(n * 2)
        pcm.forEach { bb.putShort(it) }
        file.writeBytes(bb.array())
        return file
    }
}

/** Pulse lengths for each haptic level. Pure so it can be unit tested. */
object Haptics {
    const val OFF = 0
    const val LIGHT = 1
    const val MEDIUM = 2
    const val STRONG = 3
    val labels = listOf("Off", "Light", "Medium", "Strong")

    /** Pause kept between wheel ticks so each one is felt separately. */
    const val GAP_MS = 14L

    class Pulse(val ms: Long, val amplitude: Int?)

    // Phones without amplitude control (most budget phones) have a plain vibration motor that
    // needs roughly 10 ms just to spin up, so their pulses are longer.
    private val tickMs = longArrayOf(0, 12, 18, 28)
    private val tickMsAmp = longArrayOf(0, 8, 10, 14)
    private val amp = intArrayOf(0, 90, 170, 255)

    fun tick(level: Int, amplitudeControl: Boolean): Pulse? {
        val l = level.coerceIn(OFF, STRONG)
        if (l == OFF) return null
        return if (amplitudeControl) Pulse(tickMsAmp[l], amp[l]) else Pulse(tickMs[l], null)
    }

    fun press(level: Int, amplitudeControl: Boolean): Pulse? {
        val t = tick(level, amplitudeControl) ?: return null
        return Pulse(t.ms + if (amplitudeControl) 6 else 10, t.amplitude)
    }
}
