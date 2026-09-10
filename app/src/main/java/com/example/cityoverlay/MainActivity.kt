// CITY_OVERLAY_UI_R4 — zgodny komplet: LocationService.kt + MainActivity.kt
package com.example.cityoverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat

class MainActivity : AppCompatActivity() {
    private lateinit var btnToggle: Button
    private lateinit var cbCities: CheckBox
    private lateinit var cbRivers: CheckBox
    private lateinit var cbForests: CheckBox
    private lateinit var diagnosticView: TextView
    private val main = Handler(Looper.getMainLooper())
    private var pendingAction: String? = null
    private var radius = 2_000
    private val stateListener: () -> Unit = {
        pendingAction = null
        updateButtonState()
    }
    private val diagnosticListener: (String) -> Unit = { text ->
        if (::diagnosticView.isInitialized) diagnosticView.text = text
    }

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) ensureLocationPermission()
        else toast("Do działania nakładki potrzebna jest zgoda na wyświetlanie nad aplikacjami")
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) ensureNotificationPermission()
        else toast("Włącz dokładną lokalizację dla aplikacji, aby rozpoznawać pobliskie obiekty")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Odmowa powiadomień nie zabrania działania usługi pierwszoplanowej.
        startOverlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            // Odstępy z pierwowzoru: duże wolne pole nad ustawieniami.
            setPadding(50, 50, 50, 50)
        }
        cbCities = CheckBox(this).apply {
            text = "📍 Miejscowości"
            isChecked = prefs.getBoolean("show_cities", true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 80
            }
        }
        cbRivers = CheckBox(this).apply {
            text = "🌊 Rzeki / kanały"
            isChecked = prefs.getBoolean("show_rivers", true)
        }
        cbForests = CheckBox(this).apply {
            text = "🌲 Lasy / puszcze / rezerwaty / parki narodowe"
            isChecked = prefs.getBoolean("show_forests", true)
        }
        val checks = listOf(cbCities, cbRivers, cbForests)
        if (checks.none { it.isChecked }) cbCities.isChecked = true
        fun saveCategories() {
            prefs.edit().putBoolean("show_cities", cbCities.isChecked)
                .putBoolean("show_rivers", cbRivers.isChecked)
                .putBoolean("show_forests", cbForests.isChecked).apply()
        }
        saveCategories()
        var correctingCheckbox = false
        checks.forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ ->
                if (!correctingCheckbox) {
                    if (checks.none { it.isChecked }) {
                        correctingCheckbox = true
                        checkbox.isChecked = true
                        correctingCheckbox = false
                        toast("Pozostaw przynajmniej jedną kategorię")
                    }
                    saveCategories()
                }
            }
            layout.addView(checkbox)
        }

        val steps = LocationService.RADIUS_STEPS
        val savedRadius = prefs.getInt("search_radius", 2_000)
        val index = steps.indices.minByOrNull { kotlin.math.abs(steps[it].toLong() - savedRadius.toLong()) } ?: 4
        radius = steps[index]
        if (radius != savedRadius) prefs.edit().putInt("search_radius", radius).apply()
        val label = TextView(this).apply {
            text = "Promień wyszukiwania: $radius m"
            textSize = 16f
            setPadding(0, 60, 0, 20)
        }
        val seekBar = SeekBar(this).apply { max = steps.lastIndex; progress = index }
        var dragging = false
        fun saveRadius() {
            if (prefs.getInt("search_radius", 2_000) != radius) prefs.edit().putInt("search_radius", radius).apply()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                radius = steps[progress.coerceIn(steps.indices)]
                label.text = "Promień wyszukiwania: $radius m"
                // Klawiatura i dostępność także zapisują wybór, mimo braku gestu przeciągania.
                if (fromUser && !dragging) saveRadius()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { dragging = false; saveRadius() }
        })
        layout.addView(label)
        layout.addView(seekBar)

        btnToggle = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 100 }
            setOnClickListener {
                if (LocationService.isRunning) {
                    pendingAction = "Zatrzymywanie…"
                    updateButtonState()
                    stopService(Intent(this@MainActivity, LocationService::class.java))
                    scheduleStateRefresh()
                } else ensureOverlayPermission()
            }
        }
        layout.addView(btnToggle)
        diagnosticView = TextView(this).apply {
            text = LocationService.diagnosticsText
            textSize = 13f
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.START
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        layout.addView(diagnosticView)
        layout.addView(TextView(this).apply {
            text = "Etykietkę nakładki można przesuwać w dowolne miejsce ekranu. Odległości są przybliżone. Poniżej 50 m pozostaje sama nazwa. Strzałka wskazuje położenie względem kierunku jazdy; na postoju lub przy niepewnym kierunku znika."
            setPadding(0, dp(20), 0, dp(12))
        })
        layout.addView(TextView(this).apply {
            text = "Dane obiektów: © OpenStreetMap contributors"
            textSize = 12f
            setTextColor(Color.rgb(30, 100, 180))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
        })
        layout.addView(TextView(this).apply {
            text = HtmlCompat.fromHtml(
                "<h2><b>${getString(R.string.app_name)}</b></h2> v. ${getString(R.string.ver_number)}<br>" +
                        "Nakładka nawigacyjna z nazwami miast, rzek i lasów<br>" +
                        "© ${getString(R.string.author)} + GPT-6 Astra, 2026",
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            setPadding(0, dp(20), 0, dp(12))
        })
        setContentView(ScrollView(this).apply { addView(layout) })
        updateButtonState()
    }

    override fun onStart() {
        super.onStart()
        LocationService.addStateListener(stateListener)
        LocationService.addDiagnosticListener(diagnosticListener)
    }
    override fun onResume() { super.onResume(); updateButtonState() }
    override fun onStop() {
        LocationService.removeStateListener(stateListener)
        LocationService.removeDiagnosticListener(diagnosticListener)
        super.onStop()
    }
    override fun onPause() {
        // Zachowaj także suwak przerwany np. przejściem do innej aplikacji.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getInt("search_radius", 2_000) != radius) prefs.edit().putInt("search_radius", radius).apply()
        super.onPause()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { toast("Wymagany Android 8 lub nowszy"); return }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else ensureLocationPermission()
    }
    private fun ensureLocationPermission() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) ensureNotificationPermission()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startOverlay()
    }
    private fun startOverlay() {
        if (LocationService.isRunning) { updateButtonState(); return }
        pendingAction = "Uruchamianie…"
        updateButtonState()
        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
            scheduleStateRefresh()
        } catch (e: Exception) {
            pendingAction = null
            updateButtonState()
            toast("Nie można uruchomić nakładki: ${e.message}")
        }
    }
    private fun scheduleStateRefresh() {
        main.postDelayed({ pendingAction = null; updateButtonState() }, 4_000)
    }
    private fun updateButtonState() {
        if (!::btnToggle.isInitialized) return
        btnToggle.isEnabled = pendingAction == null
        btnToggle.text = pendingAction ?: if (LocationService.isRunning) "Zatrzymaj nakładkę" else "Uruchom nakładkę"
        btnToggle.setBackgroundColor(Color.parseColor(if (LocationService.isRunning) "#8B0000" else "#1E88E5"))
        btnToggle.setTextColor(Color.WHITE)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
