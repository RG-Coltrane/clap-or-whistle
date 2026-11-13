package com.rgcoltrane.clapwhistle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/** One simple screen: on/off button, a live volume bar, a sensitivity slider. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var toggle: Button
    private lateinit var stopAlarmBtn: Button
    private lateinit var levelBar: ProgressBar

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            levelBar.progress = (ListenerService.level * 2000).toInt().coerceIn(0, 1000)
            ui.postDelayed(this, 80)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        ListenerService.sensitivity = prefs.getInt("sensitivity", 6)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Clap or Whistle"
            textSize = 30f
        }
        root.addView(title, params())

        val subtitle = TextView(this).apply {
            text = "Lost your phone? Clap twice or whistle, and it rings at full volume and flashes the light."
            textSize = 15f
        }
        root.addView(subtitle, params(8))

        statusView = TextView(this).apply { textSize = 18f }
        root.addView(statusView, params(32))

        toggle = Button(this)
        toggle.setOnClickListener {
            if (ListenerService.listening) {
                startService(Intent(this, ListenerService::class.java).setAction(ListenerService.ACTION_QUIT))
            } else {
                ensurePermissionsThenStart()
            }
        }
        root.addView(toggle, params(16))

        stopAlarmBtn = Button(this).apply {
            text = "Stop alarm"
            visibility = View.GONE
        }
        stopAlarmBtn.setOnClickListener {
            startService(Intent(this, ListenerService::class.java).setAction(ListenerService.ACTION_STOP_ALARM))
        }
        root.addView(stopAlarmBtn, params(8))

        val levelLabel = TextView(this).apply {
            text = "What the mic hears right now:"
            textSize = 14f
        }
        root.addView(levelLabel, params(32))

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        root.addView(levelBar, params(4))

        val sensLabel = TextView(this).apply {
            text = "Sensitivity: ${ListenerService.sensitivity} / 10"
            textSize = 14f
        }
        root.addView(sensLabel, params(24))

        val seek = SeekBar(this).apply {
            max = 9
            progress = ListenerService.sensitivity - 1
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value + 1
                ListenerService.sensitivity = v
                prefs.edit().putInt("sensitivity", v).apply()
                sensLabel.text = "Sensitivity: $v / 10"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        root.addView(seek, params(4))

        val hint = TextView(this).apply {
            text = "Too many false alarms? Slide down. Not hearing you? Slide up. " +
                "Keep the phone within a few metres, not buried deep in a bag."
            textSize = 13f
        }
        root.addView(hint, params(16))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ListenerService.onChange = { refresh() }
        refresh()
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ListenerService.onChange = null
        ui.removeCallbacks(poll)
    }

    private fun refresh() {
        statusView.text = ListenerService.status
        toggle.text = if (ListenerService.listening) "Stop listening" else "Start listening"
        stopAlarmBtn.visibility = if (ListenerService.ringing) View.VISIBLE else View.GONE
    }

    private fun ensurePermissionsThenStart() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) startListening() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            statusView.text = "I need the microphone to hear you. Allow it in the app's settings."
        }
    }

    private fun startListening() {
        startForegroundService(Intent(this, ListenerService::class.java).setAction(ListenerService.ACTION_START))
    }
}
