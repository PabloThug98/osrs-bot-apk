package com.osrsbot.autotrainer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger

class MainActivity : AppCompatActivity() {

    private var botService: BotService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as BotService.LocalBinder
            botService = binder.getService()
            serviceBound = true
            botService?.statusListener = { status, running ->
                runOnUiThread { updateStatusUI(status, running) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startAndBindService()
        setupUI()
        updatePermissionStatus()
    }

    private fun startAndBindService() {
        val intent = Intent(this, BotService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupUI() {
        // ── Permission button ──
        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener {
            requestMissingPermissions()
        }

        // ── Open accessibility settings ──
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── Launch overlay ──
        findViewById<Button>(R.id.btnLaunchOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                requestOverlayPermission()
                return@setOnClickListener
            }
            if (!OSRSAccessibilityService.isRunning) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            applyConfig()
            botService?.showOverlay()
            Toast.makeText(this, "Overlay launched! Open OSRS now.", Toast.LENGTH_LONG).show()
        }

        // ── Stop-time seekbar ──
        val seekTime = findViewById<SeekBar>(R.id.seekStopTime)
        val tvTime = findViewById<TextView>(R.id.tvStopTime)
        seekTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) {
                tvTime.text = "Stop after: ${v.coerceAtLeast(10)} minutes"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Stop-actions seekbar ──
        val seekAct = findViewById<SeekBar>(R.id.seekStopActions)
        val tvAct = findViewById<TextView>(R.id.tvStopActions)
        seekAct.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) {
                tvAct.text = "Stop after: ${v.coerceAtLeast(50)} actions"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun applyConfig() {
        val scriptId = when (findViewById<RadioGroup>(R.id.rgScripts).checkedRadioButtonId) {
            R.id.rbWoodcutting -> "woodcutting"
            R.id.rbFishing     -> "fishing"
            R.id.rbCombat      -> "combat"
            else               -> "chocolate"
        }
        val config = BotConfig(
            scriptId           = scriptId,
            antiBanBreaks      = findViewById<Switch>(R.id.switchBreaks).isChecked,
            stopOnPlayerNearby = findViewById<Switch>(R.id.switchStopPlayer).isChecked,
            humanLikeMouse     = findViewById<Switch>(R.id.switchHuman).isChecked,
            stopAfterTime      = findViewById<Switch>(R.id.switchStopTime).isChecked,
            stopAfterMinutes   = findViewById<SeekBar>(R.id.seekStopTime).progress.coerceAtLeast(10),
            stopAfterActions   = findViewById<Switch>(R.id.switchStopActions).isChecked,
            stopAfterActionCount = findViewById<SeekBar>(R.id.seekStopActions).progress.coerceAtLeast(50),
        )
        botService?.updateConfig(config)
        Logger.info("Config applied: $config")
    }

    private fun updatePermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk  = OSRSAccessibilityService.isRunning

        val tvOverlay = findViewById<TextView>(R.id.tvOverlayStatus)
        val tvAccess  = findViewById<TextView>(R.id.tvAccessibilityStatus)

        tvOverlay.text      = if (overlayOk) "✅ Granted"     else "❌ Not Granted"
        tvOverlay.setTextColor(getColor(if (overlayOk) R.color.green else R.color.red))
        tvAccess.text       = if (accessOk)  "✅ Enabled"     else "❌ Not Enabled"
        tvAccess.setTextColor(getColor(if (accessOk) R.color.green else R.color.red))

        val allOk = overlayOk && accessOk
        findViewById<Button>(R.id.btnGrantPermissions).text =
            if (allOk) "✅ All Permissions Granted" else "Grant Permissions"
    }

    private fun requestMissingPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission(); return
        }
        if (!OSRSAccessibilityService.isRunning) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun updateStatusUI(status: String, running: Boolean) {
        val btn = findViewById<Button>(R.id.btnLaunchOverlay)
        btn.text = if (running) "⬛ BOT RUNNING — Tap to open overlay" else "🚀 LAUNCH FLOATING OVERLAY"
        btn.backgroundTintList = getColorStateList(if (running) R.color.red else R.color.green)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
