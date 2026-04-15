package com.osrsbot.autotrainer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger

class MainActivity : AppCompatActivity() {

    private var botService: BotService? = null
    private var serviceBound = false
    private var selectedWalkerArea = "none"
    private var selectedScriptId: String? = null

    private lateinit var projectionManager: MediaProjectionManager
    private var projectionGranted = false

    private data class ScriptOption(val radioId: Int, val cardId: Int, val scriptId: String)

    private val walkerAreas = listOf(
        "None (stay put - use TARGET to mark trees)" to "none",
        "Lumbridge - Trees NE of castle"             to "lumbridge",
        "Draynor Village - Willows by bank"          to "draynor",
        "Varrock West - Trees NW of bank"            to "varrock_west",
        "Varrock East - Trees NE of bank"            to "varrock_east",
        "Falador - Park trees (centre)"              to "falador",
        "Edgeville - Trees SE of bank"               to "edgeville",
        "Barbarian Village to Edgeville bank"        to "barbarian",
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as BotService.LocalBinder
            botService = binder.getService()
            serviceBound = true
            botService?.statusListener = { status, running ->
                runOnUiThread { updateStatusUI(status, running) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        startAndBindService()
        setupUI()
        updatePermissionStatus()
    }

    private fun startAndBindService() {
        val intent = Intent(this, BotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupUI() {
        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener { requestMissingPermissions() }
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnLaunchOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                requestOverlayPermission(); return@setOnClickListener
            }
            if (!OSRSAccessibilityService.isRunning) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return@setOnClickListener
            }
            if (!applyConfig()) return@setOnClickListener
            if (!projectionGranted) requestScreenCapture() else launchOverlay()
        }
        val seekTime = findViewById<SeekBar>(R.id.seekStopTime)
        val tvTime   = findViewById<TextView>(R.id.tvStopTime)
        seekTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { tvTime.text = "Stop after: " + v.coerceAtLeast(10) + " minutes" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        val seekAct = findViewById<SeekBar>(R.id.seekStopActions)
        val tvAct   = findViewById<TextView>(R.id.tvStopActions)
        seekAct.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { tvAct.text = "Stop after: " + v.coerceAtLeast(50) + " actions" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        setupWalkerSpinner()
        setupScriptToggles()
    }

    private fun requestScreenCapture() {
        Logger.info("Requesting screen capture permission...")
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                projectionGranted = true
                Logger.ok("Screen capture permission granted.")
                botService?.setMediaProjection(projectionManager.getMediaProjection(resultCode, data))
                launchOverlay()
            } else {
                projectionGranted = false
                Logger.warn("Screen capture denied — falling back to accessibility detection.")
                Toast.makeText(this, "Screen capture denied. Falling back to accessibility detection.", Toast.LENGTH_LONG).show()
                launchOverlay()
            }
        }
    }

    private fun launchOverlay() {
        botService?.showOverlay()
        Toast.makeText(this, "Overlay launched! Open OSRS now.", Toast.LENGTH_LONG).show()
    }

    private fun setupScriptToggles() {
        val options = listOf(
            ScriptOption(R.id.rbChocolate, R.id.cardChocolate, "chocolate"),
            ScriptOption(R.id.rbWoodcutting, R.id.cardWoodcutting, "woodcutting"),
            ScriptOption(R.id.rbFishing, R.id.cardFishing, "fishing"),
            ScriptOption(R.id.rbCombat, R.id.cardCombat, "combat"),
            ScriptOption(R.id.rbMining, R.id.cardMining, "mining"),
        )
        for (option in options) {
            val rb = findViewById<RadioButton>(option.radioId)
            val card = findViewById<View>(option.cardId)
            val click = View.OnClickListener {
                setScriptSelection(if (selectedScriptId == option.scriptId) null else option.scriptId, options)
            }
            rb.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) { setScriptSelection(if (selectedScriptId == option.scriptId) null else option.scriptId, options); true } else true
            }
            card.setOnClickListener(click)
        }
        setScriptSelection(null, options)
    }

    private fun setScriptSelection(scriptId: String?, options: List<ScriptOption>) {
        selectedScriptId = scriptId
        findViewById<RadioGroup>(R.id.rgScripts).clearCheck()
        for (option in options) {
            val checked = option.scriptId == scriptId
            findViewById<RadioButton>(option.radioId).isChecked = checked
            findViewById<View>(option.cardId).setBackgroundResource(
                if (checked) R.drawable.script_card_selected_bg else R.drawable.script_card_bg
            )
        }
    }

    private fun setupWalkerSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinnerWalkerArea)
        val tvNote  = findViewById<TextView>(R.id.tvWalkerNote)
        val labels  = walkerAreas.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                selectedWalkerArea = walkerAreas[pos].second
                tvNote.text = when (selectedWalkerArea) {
                    "none"         -> "Bot stays where you put it. Tap TARGET to mark trees/spots manually."
                    "lumbridge"    -> "Bot walks: Lumbridge bank to trees NE of castle."
                    "draynor"      -> "Bot walks: Draynor bank to willows south of bank."
                    "varrock_west" -> "Bot walks: Varrock West bank to trees NW."
                    "varrock_east" -> "Bot walks: Varrock East bank to trees NE."
                    "falador"      -> "Bot walks: Falador West bank to park trees."
                    "edgeville"    -> "Bot walks: Edgeville bank to trees SE."
                    "barbarian"    -> "Bot walks: Barbarian Village trees to Edgeville bank."
                    else           -> ""
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun applyConfig(): Boolean {
        val scriptId = selectedScriptId ?: run {
            Toast.makeText(this, "Please select a script first.", Toast.LENGTH_LONG).show()
            return false
        }
        val config = BotConfig(
            scriptId             = scriptId,
            antiBanBreaks        = findViewById<SwitchMaterial>(R.id.switchBreaks).isChecked,
            stopOnPlayerNearby   = findViewById<SwitchMaterial>(R.id.switchStopPlayer).isChecked,
            humanLikeMouse       = findViewById<SwitchMaterial>(R.id.switchHuman).isChecked,
            stopAfterTime        = findViewById<SwitchMaterial>(R.id.switchStopTime).isChecked,
            stopAfterMinutes     = findViewById<SeekBar>(R.id.seekStopTime).progress.coerceAtLeast(10),
            stopAfterActions     = findViewById<SwitchMaterial>(R.id.switchStopActions).isChecked,
            stopAfterActionCount = findViewById<SeekBar>(R.id.seekStopActions).progress.coerceAtLeast(50),
            walkerArea           = selectedWalkerArea,
        )
        botService?.updateConfig(config)
        Logger.info("Config applied: script=$scriptId area=$selectedWalkerArea")
        return true
    }

    private fun updatePermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk  = OSRSAccessibilityService.isRunning
        findViewById<TextView>(R.id.tvOverlayStatus).apply {
            text = if (overlayOk) "Granted" else "Not Granted"
            setTextColor(getColor(if (overlayOk) R.color.green else R.color.red))
        }
        findViewById<TextView>(R.id.tvAccessibilityStatus).apply {
            text = if (accessOk) "Enabled" else "Not Enabled"
            setTextColor(getColor(if (accessOk) R.color.green else R.color.red))
        }
        val allOk = overlayOk && accessOk
        findViewById<Button>(R.id.btnGrantPermissions).text =
            if (allOk) "All Permissions Granted" else "Grant Permissions"
    }

    private fun requestMissingPermissions() {
        if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return }
        if (!OSRSAccessibilityService.isRunning) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun updateStatusUI(status: String, running: Boolean) {
        val btn = findViewById<Button>(R.id.btnLaunchOverlay)
        btn.text = if (running) "BOT RUNNING - Tap to open overlay" else "LAUNCH FLOATING OVERLAY"
        btn.backgroundTintList = getColorStateList(if (running) R.color.red else R.color.green)
    }

    override fun onResume() { super.onResume(); updatePermissionStatus() }
    override fun onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        super.onDestroy()
    }

    companion object { private const val REQ_SCREEN_CAPTURE = 101 }
}
