package org.stypox.dicio.io.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Ethereal personal-fork behavior: after the wake word, record the utterance, POST it to the
 * Ethereal PC endpoint (over Tailscale) and play back the spoken answer. Replaces Dicio's
 * on-device STT + skills when [ENABLED] is true.
 *
 * Config (optional) is read from `<filesDir>/ethereal.json`:
 *   {"url": "http://100.80.39.83:8790/v1/turn", "token": "..."}
 * It can be written without opening the app (debug build) via `adb shell run-as <pkg>`.
 */
object EtherealClient {
    const val ENABLED = true
    private const val DEFAULT_URL = "http://100.80.39.83:8790/v1/turn"
    private const val TAG = "EtherealClient"

    private const val SAMPLE_RATE = 16000
    private const val NO_SPEECH_TIMEOUT_MS = 5000
    private const val END_SILENCE_MS = 1100
    private const val MAX_UTTERANCE_MS = 15000
    private const val MIN_THRESHOLD = 350.0

    /** Rolling ambient RMS, updated by the wake loop, used to pick the speech threshold. */
    @Volatile
    var ambientRms = 200.0

    fun updateAmbient(frame: ShortArray) {
        ambientRms = ambientRms * 0.98 + rms(frame) * 0.02
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }

    private data class Config(val url: String, val token: String)

    private fun loadConfig(context: Context): Config {
        val f = File(context.filesDir, "ethereal.json")
        if (!f.exists()) return Config(DEFAULT_URL, "")
        return try {
            val j = JSONObject(f.readText())
            Config(j.optString("url", DEFAULT_URL).ifBlank { DEFAULT_URL }, j.optString("token", ""))
        } catch (e: Exception) {
            Log.e(TAG, "bad ethereal.json, using defaults", e)
            Config(DEFAULT_URL, "")
        }
    }

    /**
     * Runs one full interaction. Called from the wake-word loop thread, which owns [ar]
     * (already recording). [ar] is stopped while waiting/playing and restarted at the end.
     */
    fun handleWake(context: Context, ar: AudioRecord, frameSize: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = requestFocus(audioManager)
        val tones = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (_: Exception) { null }
        try {
            tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            val pcm = recordUtterance(ar, frameSize)
            ar.stop()
            if (pcm == null) {
                Log.d(TAG, "no speech after wake word")
                tones?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                Thread.sleep(250)
                return
            }
            tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            val reply = try {
                send(loadConfig(context), pcm)
            } catch (e: Exception) {
                Log.e(TAG, "Ethereal request failed", e)
                null
            }
            if (reply == null || reply.size < 64) {
                tones?.startTone(ToneGenerator.TONE_SUP_ERROR, 600)
                Thread.sleep(700)
                return
            }
            play(context, reply)
        } finally {
            tones?.release()
            abandonFocus(audioManager, focus)
            try { ar.startRecording() } catch (e: Exception) { Log.e(TAG, "restart mic failed", e) }
        }
    }

    private fun recordUtterance(ar: AudioRecord, frameSize: Int): ShortArray? {
        val threshold = max(MIN_THRESHOLD, ambientRms * 2.5)
        val frameMs = frameSize * 1000 / SAMPLE_RATE
        val out = ArrayList<ShortArray>()
        val frame = ShortArray(frameSize)
        var elapsed = 0
        var speechStarted = false
        var silentMs = 0
        while (elapsed < MAX_UTTERANCE_MS) {
            val n = ar.read(frame, 0, frameSize)
            if (n <= 0) break
            val chunk = frame.copyOf(n)
            out.add(chunk)
            elapsed += frameMs
            val loud = rms(chunk) > threshold
            if (loud) {
                speechStarted = true
                silentMs = 0
            } else if (speechStarted) {
                silentMs += frameMs
                if (silentMs >= END_SILENCE_MS) break
            } else if (elapsed >= NO_SPEECH_TIMEOUT_MS) {
                return null
            }
        }
        if (!speechStarted) return null
        val total = out.sumOf { it.size }
        val pcm = ShortArray(total)
        var pos = 0
        for (c in out) { c.copyInto(pcm, pos); pos += c.size }
        return pcm
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SAMPLE_RATE); h.putInt(SAMPLE_RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size * 2)
        return h.array() + data.array()
    }

    private fun send(cfg: Config, pcm: ShortArray): ByteArray? {
        val sep = if (cfg.url.contains("?")) "&" else "?"
        val conn = URL("${cfg.url}${sep}device=phone").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            if (cfg.token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            conn.outputStream.use { it.write(wav(pcm)) }
            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "Ethereal endpoint returned HTTP $code")
                return null
            }
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(buf) }
            return buf.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun play(context: Context, audio: ByteArray) {
        val f = File(context.cacheDir, "ethereal-reply.wav")
        f.writeBytes(audio)
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error $what/$extra"); done.countDown(); true
            }
            mp.prepare()
            mp.start()
            done.await(mp.duration.toLong().coerceAtLeast(1000) + 5000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
        } finally {
            mp.release()
        }
    }

    private fun requestFocus(am: AudioManager): Any? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            req
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            null
        }
    }

    private fun abandonFocus(am: AudioManager, req: Any?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req is AudioFocusRequest) {
            am.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
