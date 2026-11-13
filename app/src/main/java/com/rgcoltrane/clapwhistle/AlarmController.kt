package com.rgcoltrane.clapwhistle

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/** Rings at full alarm volume and blinks the flashlight. Call everything from the main thread. */
class AlarmController(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var player: MediaPlayer? = null
    private var torchId: String? = null
    private var torchOn = false
    private var savedVolume = -1

    var ringing = false
        private set

    /** Called after the alarm stops (by hand or by the automatic timeout). */
    var onStopped: (() -> Unit)? = null

    private val blink = object : Runnable {
        override fun run() {
            if (!ringing) return
            torchOn = !torchOn
            setTorch(torchOn)
            handler.postDelayed(this, 350)
        }
    }

    private val autoStop = Runnable { stop() }

    fun ring() {
        if (ringing) return
        ringing = true

        // Crank the alarm volume to max, remember what it was.
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (e: Exception) {
            savedVolume = -1
        }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            mp.release() // no sound available; the flashlight still works
        }

        torchId = findTorch()
        handler.post(blink)
        handler.postDelayed(autoStop, 60_000) // give up after a minute
    }

    fun stop() {
        if (!ringing) return
        ringing = false
        handler.removeCallbacks(blink)
        handler.removeCallbacks(autoStop)

        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // ignore
            }
            it.release()
        }
        player = null

        setTorch(false)
        torchOn = false

        if (savedVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (e: Exception) {
                // ignore
            }
            savedVolume = -1
        }
        onStopped?.invoke()
    }

    private fun findTorch(): String? = try {
        camera.cameraIdList.firstOrNull { id ->
            val c = camera.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) {
        null
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        try {
            camera.setTorchMode(id, on)
        } catch (e: Exception) {
            // camera busy or no flash; ignore
        }
    }
}
