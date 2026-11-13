package com.rgcoltrane.clapwhistle

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listens to the microphone on a background thread.
 *
 * - "clap": two sharp, noisy bursts within 1.5 seconds
 * - "whistle": a steady, pure tone between ~800 Hz and ~4 kHz for about a third of a second
 *
 * Calls onHeard("clap") or onHeard("whistle") from the background thread.
 */
class SoundDetector(
    private val sensitivity: () -> Int,
    private val onLevel: (Float) -> Unit,
    private val onHeard: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 512 // 32 ms of audio per step
    }

    @Volatile private var running = false

    /** While true, we keep listening but ignore everything (used while the alarm rings). */
    @Volatile var paused = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also {
            it.name = "sound-detector"
            it.start()
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (e: InterruptedException) {
            // ignore
        }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, FRAME * 8)
            )
        } catch (e: Exception) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        val pcm = ShortArray(FRAME)
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val pw = DoubleArray(FRAME / 2)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }

        var noise = 0.01          // running guess of the background loudness
        var prevRms = 0.0         // loudness of the previous frame
        var firstClapAt = 0L      // when the first clap of a pair happened
        var lastOnsetAt = 0L      // debounce: ignore echoes right after a clap
        var toneBin = -1          // frequency bin of the current whistle candidate
        var toneFrames = 0        // how many frames in a row it has held steady

        try {
            record.startRecording()
            while (running) {
                // Fill one full frame.
                var got = 0
                while (got < FRAME && running) {
                    val n = record.read(pcm, got, FRAME - got)
                    if (n < 0) return
                    got += n
                }
                if (!running) break

                // Loudness (RMS) of this frame, plus a windowed copy for the FFT.
                var sum = 0.0
                for (i in 0 until FRAME) {
                    val sample = pcm[i] / 32768.0
                    sum += sample * sample
                    re[i] = sample * window[i]
                    im[i] = 0.0
                }
                val rms = sqrt(sum / FRAME)
                onLevel(rms.toFloat())
                val now = SystemClock.elapsedRealtime()

                if (paused) {
                    prevRms = rms
                    firstClapAt = 0L
                    toneFrames = 0
                    toneBin = -1
                    continue
                }

                // Spectrum: how much of the energy sits in one narrow spot? (whistle = lots)
                fft(re, im)
                var total = 1e-12
                for (k in 2 until FRAME / 2) {
                    pw[k] = re[k] * re[k] + im[k] * im[k]
                    total += pw[k]
                }
                var peakBin = 26 // 26 * 31.25 Hz = ~800 Hz
                var peakVal = 0.0
                for (k in 26..128) { // up to ~4 kHz
                    if (pw[k] > peakVal) {
                        peakVal = pw[k]
                        peakBin = k
                    }
                }
                var band = 0.0
                for (k in (peakBin - 2)..(peakBin + 2)) band += pw[k]
                val tonal = band / total

                val sens = sensitivity().coerceIn(1, 10)
                val clapMin = 0.40 - 0.035 * sens   // sens 1 -> 0.365, sens 10 -> 0.05
                val whistleMin = clapMin * 0.4

                // --- Whistle: loud enough, pure tone, held steady ---
                if (rms > whistleMin && tonal > 0.55) {
                    if (toneBin >= 0 && abs(peakBin - toneBin) <= 2) {
                        toneFrames++
                    } else {
                        toneFrames = 1
                    }
                    toneBin = peakBin
                } else {
                    toneFrames = 0
                    toneBin = -1
                }
                if (toneFrames >= 10) { // ~320 ms
                    toneFrames = 0
                    toneBin = -1
                    firstClapAt = 0L
                    prevRms = rms
                    onHeard("whistle")
                    continue
                }

                // --- Clap: sudden loud, noisy burst. Two of them = trigger. ---
                val isOnset = rms > clapMin && rms > noise * 4 && rms > prevRms * 2 && tonal < 0.5
                if (isOnset && now - lastOnsetAt > 150) {
                    lastOnsetAt = now
                    if (firstClapAt != 0L && now - firstClapAt <= 1500) {
                        firstClapAt = 0L
                        onHeard("clap")
                    } else {
                        firstClapAt = now
                    }
                }
                if (firstClapAt != 0L && now - firstClapAt > 1500) firstClapAt = 0L

                // Slowly learn what "quiet" sounds like here.
                noise = if (rms < noise * 3) noise * 0.98 + rms * 0.02 else noise * 0.999 + rms * 0.001
                noise = max(noise, 0.002)
                prevRms = rms
            }
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                // ignore
            }
            record.release()
        }
    }

    /** Plain in-place radix-2 FFT. re/im must have a power-of-two length. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] = re[a] + vr
                    im[a] = im[a] + vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
