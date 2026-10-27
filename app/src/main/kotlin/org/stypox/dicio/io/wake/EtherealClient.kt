package org.stypox.dicio.io.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Ethereal personal-fork behavior: after the wake word, record the utterance, POST it to the
 * Ethereal PC endpoint (over Tailscale) and play back the spoken answer. Replaces Dicio's
 * on-device STT + skills when [ENABLED] is true.
 *
 * The reply is streamed (`?stream=1`): the server sends one length-prefixed PCM frame per spoken
 * sentence as soon as it is rendered (4-byte big-endian length, then 16-bit LE mono PCM at the
 * `X-Ethereal-Sample-Rate` rate; a 0-length frame ends the reply), and they are played back to
 * back through one AudioTrack while later sentences are still being generated. If the streaming
 * request fails before any audio arrives, the old single-WAV request is used instead.
 *
 * Config (optional) is read from `<filesDir>/ethereal.json`:
 *   {"url": "http://100.80.39.83:8790/v1/turn", "token": "...", "stream": true}
 * It is edited from the "Ethereal" section of the settings screen, and can also be written
 * without opening the app (debug build) via `adb shell run-as <pkg>`.
 *
 * A turn starts either from the wake word ([handleWake], on the wake loop thread) or from the
 * assist gesture (EtherealAssistActivity -> [WakeService.requestEtherealTurn]).
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
    private const val STANDALONE_FRAME = 1280 // 80 ms at 16 kHz, like the wake-word frames

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

    data class Config(val url: String, val token: String, val stream: Boolean = true)

    private fun configFile(context: Context) = File(context.filesDir, "ethereal.json")

    fun loadConfig(context: Context): Config {
        val f = configFile(context)
        if (!f.exists()) return Config(DEFAULT_URL, "")
        return try {
            val j = JSONObject(f.readText())
            Config(
                j.optString("url", DEFAULT_URL).ifBlank { DEFAULT_URL },
                j.optString("token", ""),
                j.optBoolean("stream", true),
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad ethereal.json, using defaults")
            Config(DEFAULT_URL, "")
        }
    }

    /** Writes the config, keeping any other keys already present in ethereal.json. */
    fun saveConfig(context: Context, cfg: Config) {
        val f = configFile(context)
        val j = try {
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        j.put("url", cfg.url.trim().ifBlank { DEFAULT_URL })
        j.put("token", cfg.token.trim())
        j.put("stream", cfg.stream)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(j.toString(2))
        if (!tmp.renameTo(f)) {
            f.writeText(j.toString(2))
            tmp.delete()
        }
    }

    /**
     * Calls the token-protected `GET /v1/health` on the same host:port as [url] and returns a
     * short human-readable result (never containing the token). Blocking: call off the main thread.
     */
    fun testConnection(url: String, token: String): String {
        val t0 = System.currentTimeMillis()
        return try {
            val base = URL(url.trim().ifBlank { DEFAULT_URL })
            val health = URL(base.protocol, base.host, base.port, "/v1/health")
            val conn = health.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                if (token.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                }
                val code = conn.responseCode
                val ms = System.currentTimeMillis() - t0
                when (code) {
                    200 -> {
                        val body = conn.inputStream.use { it.readBytes().decodeToString() }
                        val busy = try {
                            JSONObject(body).optBoolean("busy")
                        } catch (_: Exception) {
                            false
                        }
                        "Connected ($ms ms)" + (if (busy) ", server busy with a turn" else "")
                    }
                    401 -> "Server reached, but the token was rejected (401)"
                    404 -> "Server reached, but it has no /v1/health (404)"
                    else -> "Server answered HTTP $code"
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "Failed: ${e.javaClass.simpleName}" + (e.message?.let { ": $it" } ?: "")
        }
    }

    private val standaloneBusy = AtomicBoolean(false)

    /**
     * Runs one turn with a microphone of its own, for when the wake-word loop is not running
     * (e.g. the wake word is disabled). Blocking.
     */
    @SuppressLint("MissingPermission")
    fun runStandaloneTurn(context: Context) {
        if (!standaloneBusy.compareAndSet(false, true)) return
        var ar: AudioRecord? = null
        try {
            ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(6400, AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)),
            )
            ar.startRecording()
            handleWake(context, ar, STANDALONE_FRAME)
        } catch (e: Exception) {
            Log.e(TAG, "standalone turn failed", e)
        } finally {
            ar?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            standaloneBusy.set(false)
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
            val cfg = loadConfig(context)
            val streamed = if (!cfg.stream) StreamResult.FAILED else try {
                streamAndPlay(context, cfg, pcm)
            } catch (e: Exception) {
                Log.e(TAG, "Ethereal stream failed", e)
                StreamResult.FAILED
            }
            if (streamed == StreamResult.PLAYED) return
            if (streamed == StreamResult.EMPTY) {
                tones?.startTone(ToneGenerator.TONE_SUP_ERROR, 600)
                Thread.sleep(700)
                return
            }
            if (cfg.stream) Log.w(TAG, "falling back to the non-streaming endpoint")
            val reply = try {
                send(cfg, pcm)
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

    private enum class StreamResult { PLAYED, EMPTY, FAILED }

    /** Marker queued by the reader thread: end of stream (clean or not). */
    private val END_OF_STREAM = ByteArray(0)

    /**
     * POSTs to the streaming endpoint and plays frames as they arrive. Returns PLAYED once any
     * audio was played (even if the stream broke later), EMPTY if the server answered but sent
     * no audio, and FAILED (or throws) if nothing usable arrived, so the caller can fall back.
     */
    private fun streamAndPlay(context: Context, cfg: Config, pcm: ShortArray): StreamResult {
        val sep = if (cfg.url.contains("?")) "&" else "?"
        val conn = URL("${cfg.url}${sep}device=phone&stream=1").openConnection() as HttpURLConnection
        val t0 = System.currentTimeMillis()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            if (cfg.token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            val body = wav(pcm)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "Ethereal stream endpoint returned HTTP $code")
                return StreamResult.FAILED
            }
            val ctype = conn.contentType ?: ""
            if (!ctype.startsWith("application/x-ethereal-pcm")) {
                // older server that ignores stream=1: it answered with the whole WAV
                val buf = ByteArrayOutputStream()
                conn.inputStream.use { it.copyTo(buf) }
                if (buf.size() < 64) return StreamResult.EMPTY
                play(context, buf.toByteArray())
                return StreamResult.PLAYED
            }
            val sampleRate = conn.getHeaderField("X-Ethereal-Sample-Rate")?.toIntOrNull() ?: 24000

            // Network reads run on their own thread so a frame that arrives while the previous
            // one is still playing is already buffered when the AudioTrack needs it.
            val frames = LinkedBlockingQueue<ByteArray>()
            val input = DataInputStream(BufferedInputStream(conn.inputStream, 64 * 1024))
            val reader = Thread {
                try {
                    while (true) {
                        val n = input.readInt()
                        if (n <= 0 || n > 50_000_000) break
                        val frame = ByteArray(n)
                        input.readFully(frame)
                        frames.put(frame)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stream read ended early", e)
                } finally {
                    frames.put(END_OF_STREAM)
                }
            }
            reader.isDaemon = true
            reader.start()

            var track: AudioTrack? = null
            var bufFrames = 0
            var framesWritten = 0L
            try {
                while (true) {
                    val frame = frames.poll(125, TimeUnit.SECONDS) ?: break
                    if (frame === END_OF_STREAM) break
                    if (track == null) {
                        Log.i(TAG, "first audio after ${System.currentTimeMillis() - t0} ms")
                        bufFrames = trackBufferBytes(sampleRate) / 2
                        track = newTrack(sampleRate, bufFrames * 2)
                        track.play()
                    }
                    var off = 0
                    while (off < frame.size) {
                        val w = track.write(frame, off, frame.size - off)
                        if (w <= 0) throw IllegalStateException("AudioTrack.write returned $w")
                        off += w
                    }
                    framesWritten += frame.size / 2
                }
                if (track == null) return StreamResult.EMPTY
                drain(track, sampleRate, bufFrames, framesWritten)
                Log.i(TAG, "stream done after ${System.currentTimeMillis() - t0} ms, " +
                    "${framesWritten * 1000 / sampleRate} ms of audio")
                return StreamResult.PLAYED
            } finally {
                track?.let {
                    try { it.stop() } catch (_: Exception) {}
                    it.release()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun trackBufferBytes(sampleRate: Int): Int {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // keep it small: a streaming AudioTrack only starts playing once its buffer is full
        return max(minBuf * 2, sampleRate / 10 * 2)
    }

    private fun newTrack(sampleRate: Int, bufBytes: Int): AudioTrack {
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    /**
     * Pushes the tail of the reply out of the AudioTrack buffer with silence, then waits until
     * the playback head has passed the last real sample (so focus is not released mid-word).
     */
    private fun drain(track: AudioTrack, sampleRate: Int, bufFrames: Int, framesWritten: Long) {
        val pad = ByteArray((bufFrames + sampleRate / 4) * 2)
        track.write(pad, 0, pad.size)
        val deadline = System.currentTimeMillis() + framesWritten * 1000 / sampleRate + 3000
        while (System.currentTimeMillis() < deadline) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= framesWritten) break
            Thread.sleep(50)
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
