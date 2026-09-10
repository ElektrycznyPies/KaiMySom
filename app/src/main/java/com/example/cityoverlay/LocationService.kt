// CITY_OVERLAY_FALLBACK_R5 — na podstawie przesłanego kodu; zgodny z MainActivity R4
package com.example.cityoverlay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stan usługi zmienia się wyłącznie w głównym wątku; sieć i geometria pracują osobno. */
class LocationService : Service() {
    companion object {
        private const val TAG = "CityOverlay"
        private const val CHANNEL = "city_overlay_channel"
        private const val ACTION_STOP = "com.example.cityoverlay.STOP"
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        // Aktualny adres dawnego overpass.kumi.systems; zapasowy, z globalnymi danymi.
        private const val OVERPASS_FALLBACK_URL = "https://overpass.openstreetmap.fr/api/interpreter"
        private const val FALLBACK_PREFERENCE_MS = 60_000L
        private const val MIN_FETCH_MS = 5_000L
        private const val CACHE_TTL_MS = 60 * 60_000L
        private const val MAX_GPS_AGE_MS = 30_000L
        private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
        private const val MAX_POINTS = 200_000
        val RADIUS_STEPS = intArrayOf(200, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000)

        var isRunning = false
            private set
        private val stateListeners = linkedSetOf<() -> Unit>()
        fun addStateListener(listener: () -> Unit) { stateListeners.add(listener); listener() }
        fun removeStateListener(listener: () -> Unit) { stateListeners.remove(listener) }
        private fun publishRunning(value: Boolean) {
            isRunning = value
            stateListeners.toList().forEach { it() }
        }

        // Osobny kanał dla ekranu aplikacji. Nigdy nie trafia do nakładki.
        var diagnosticsText = "Nakładka zatrzymana."
            private set
        private val diagnosticListeners = linkedSetOf<(String) -> Unit>()
        fun addDiagnosticListener(listener: (String) -> Unit) {
            diagnosticListeners.add(listener)
            listener(diagnosticsText)
        }
        fun removeDiagnosticListener(listener: (String) -> Unit) { diagnosticListeners.remove(listener) }
        private fun publishDiagnostics(text: String) {
            if (diagnosticsText == text) return
            diagnosticsText = text
            diagnosticListeners.toList().forEach { it(text) }
        }
    }

    private data class Config(val cities: Boolean, val rivers: Boolean, val forests: Boolean, val radius: Int)
    private enum class Kind { RIVER, FOREST }
    private enum class Stage(val label: String) { RIVERS("Rzeka"), FORESTS("Las"), INSIDE("Wnętrze obszaru") }
    private data class Feature(
        val key: String, val name: String, val kind: Kind, val importance: Int,
        val lines: List<List<GeoMath.Point>>, val rings: List<List<GeoMath.Point>>
    )
    private data class GeometryCache(
        val centre: GeoMath.Point, val radius: Int, val at: Long,
        val features: List<Feature>
    )
    private class FetchState {
        var cache: GeometryCache? = null
        var lastAttempt: Long? = null
        var retryAt = 0L
        var failures = 0
        var error: String? = null
        var server: String? = null
        var transfer: String? = null
    }
    private data class CityResult(val text: String, val point: GeoMath.Point, val at: Long)
    private data class Candidate(val feature: Feature, val nearest: GeoMath.Nearest)
    private data class RenderResult(
        val text: String, val riverName: String?, val forestName: String?, val error: String? = null
    )
    private class FetchException(
        message: String, val summary: String, val retryMs: Long = 0,
        val terminal: Boolean = false, val pauseAll: Boolean = false,
        val allowFallback: Boolean = false
    ) : IOException(message)

    private val main = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val legacyGeocoderExecutor = Executors.newSingleThreadExecutor()
    private val geometryExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    @Volatile private var destroyed = false
    @Volatile private var activeCall: Call? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var config: Config
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder
    private var overlayAttached = false
    private var latestLocation: Location? = null
    private val fetchStates = Stage.values().associateWith { FetchState() }
    private var city: CityResult? = null
    private var cityFailed = false
    private var cityError: String? = null
    private var geometryError: String? = null
    private var startupError: String? = null
    private var geocoderBusy = false
    private var geocoderGeneration = 0L
    private var lastGeocodeAt = 0L
    private var lastGeocodePoint: GeoMath.Point? = null
    private var geocodeRetryAt = 0L
    private var fetchBusy = false
    private var activeStage: Stage? = null
    private var serverRetryAt = 0L
    private var lastAnyFetchAt: Long? = null
    // Używane wyłącznie przez pojedynczy networkExecutor.
    private var preferFallbackUntil = 0L
    private var rendering = false
    private var renderVersion = 0L
    private var previousRiver: String? = null
    private var previousForest: String? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("show_cities", "show_rivers", "show_forests", "search_radius")) {
            main.post {
                if (!destroyed) {
                    val newConfig = readConfig()
                    if (newConfig != config) {
                        val geographyChanged = newConfig.radius != config.radius ||
                                newConfig.rivers != config.rivers || newConfig.forests != config.forests
                        val cityWasEnabled = config.cities
                        config = newConfig
                        if (geographyChanged) fetchStates.values.forEach {
                            it.error = null; it.retryAt = 0; it.failures = 0
                            // Wspólnej przerwy po HTTP 429 / Retry-After nie resetujemy.
                        }
                        // Wynik rozpoczęty przed wyłączeniem kategorii nie ma przywracać tekstu.
                        if (!config.cities && cityWasEnabled) {
                            city = null; geocoderGeneration++; lastGeocodePoint = null; cityFailed = false; cityError = null
                        }
                        onTick()
                    }
                }
            }
        }
    }
    private val timer = object : Runnable {
        override fun run() {
            if (destroyed) return
            onTick()
            main.postDelayed(this, 5_000)
        }
    }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (destroyed) return
            val location = result.lastLocation ?: return
            if (!fresh(location) || !location.hasAccuracy() || location.accuracy > 150f) return
            if (latestLocation?.let { location.elapsedRealtimeNanos < it.elapsedRealtimeNanos } == true) return
            latestLocation = Location(location)
            onTick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        config = readConfig()
        publishDiagnostics("Uruchamianie nakładki…")
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw IllegalStateException("Wymagany Android 8 lub nowszy")
            if (!Settings.canDrawOverlays(this)) throw IllegalStateException("Włącz wyświetlanie nad innymi aplikacjami")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("Włącz dokładną lokalizację dla aplikacji")
            }
            startNotification()
            setupOverlay()
            geocoder = Geocoder(applicationContext, Locale.getDefault())
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000)
                .setMinUpdateIntervalMillis(2_000)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnSuccessListener { if (!destroyed) publishRunning(true) }
                .addOnFailureListener { if (!destroyed) failStart(it) }
            main.post(timer)
        } catch (e: Exception) {
            failStart(e)
        }
    }

    private fun failStart(error: Exception) {
        Log.e(TAG, "Uruchomienie usługi nie powiodło się", error)
        startupError = error.message ?: "Nie można uruchomić nakładki"
        publishDiagnostics("Błąd uruchomienia: $startupError")
        Toast.makeText(this, error.message ?: "Nie można uruchomić nakładki", Toast.LENGTH_LONG).show()
        publishRunning(false)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        // Świadomy start użytkownika; bez automatycznego śledzenia po restarcie procesu.
        return START_NOT_STICKY
    }

    private fun startNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Nakładka geograficzna", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Nazwy miejscowości i obiektów w okolicy")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Zatrzymaj", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Wysoko i na środku ekranu. Wymiary z pierwowzoru pozostają bez zmian.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Nowe klucze przywracają domyślne położenie po tej aktualizacji.
            x = prefs.getInt("overlay_center_x", 0)
            y = prefs.getInt("overlay_top_y", 60)
        }
        overlayView = TextView(this).apply {
            text = overlayLines(config)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER_HORIZONTAL
            maxWidth = (resources.displayMetrics.widthPixels - 70).coerceAtLeast(1)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC000000")); cornerRadius = 24f }
            setPadding(35, 25, 35, 25)
            textSize = 22f
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        overlayView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    if (overlayAttached && !destroyed) windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("overlay_center_x", params.x).putInt("overlay_top_y", params.y).apply()
                    true
                }
                else -> false
            }
        }
        windowManager.addView(overlayView, params)
        overlayAttached = true
    }

    private fun readConfig(): Config {
        val saved = prefs.getInt("search_radius", 2_000)
        val radius = RADIUS_STEPS.minByOrNull { kotlin.math.abs(it.toLong() - saved.toLong()) } ?: 2_000
        return Config(prefs.getBoolean("show_cities", true), prefs.getBoolean("show_rivers", true), prefs.getBoolean("show_forests", true), radius)
    }
    private fun point(location: Location) = GeoMath.Point(location.latitude, location.longitude)
    private fun fresh(location: Location): Boolean {
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return age in 0..MAX_GPS_AGE_MS
    }
    private fun onTick() {
        if (destroyed) return
        latestLocation?.takeIf { fresh(it) }?.let {
            maybeGeocode(it)
            maybeFetch(it)
        }
        requestRender()
        updateDiagnostics()
    }

    private fun maybeGeocode(location: Location) {
        if (!config.cities || geocoderBusy) return
        if (!Geocoder.isPresent()) { cityFailed = true; cityError = "Geokoder niedostępny na urządzeniu"; return }
        val now = SystemClock.elapsedRealtime()
        if (now < geocodeRetryAt) return
        val p = point(location)
        if (lastGeocodePoint != null) {
            if (now - lastGeocodeAt < 10_000) return
            if (!cityFailed && GeoMath.distance(lastGeocodePoint!!, p) < 100 && now - lastGeocodeAt < 5 * 60_000) return
        }
        geocoderBusy = true
        lastGeocodeAt = now
        lastGeocodePoint = p
        val generation = geocoderGeneration
        fun finish(addresses: List<Address>?, error: String?) {
            main.post {
                if (destroyed) return@post
                geocoderBusy = false
                if (generation != geocoderGeneration || !config.cities) { onTick(); return@post }
                val a = addresses?.firstOrNull()
                val locality = a?.locality?.trim()?.takeIf { it.isNotEmpty() }
                val vicinity = a?.subLocality?.trim()?.takeIf { it.isNotEmpty() }
                    ?: a?.subAdminArea?.trim()?.takeIf { it.isNotEmpty() }
                val label = locality ?: vicinity?.let { "okolice: $it" }
                cityFailed = label == null
                if (label != null) {
                    city = CityResult(label, p, SystemClock.elapsedRealtime())
                    cityError = null
                }
                else {
                    cityError = error ?: "Brak nazwy w odpowiedzi geokodera"
                    // Prawidłowa pusta odpowiedź unieważnia dawną nazwę;
                    // sam błąd sieci pozwala zachować ją w granicach ważności.
                    if (error == null) city = null
                    geocodeRetryAt = SystemClock.elapsedRealtime() + 30_000
                    if (error != null) Log.w(TAG, "Geocoder: $error")
                }
                onTick()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(p.lat, p.lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = finish(addresses, null)
                    override fun onError(errorMessage: String?) = finish(null, errorMessage ?: "Błąd geokodera")
                })
            } catch (e: Exception) { finish(null, e.message ?: e.javaClass.simpleName) }
        } else {
            legacyGeocoderExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(p.lat, p.lon, 1)
                    finish(addresses, null)
                } catch (e: Exception) { finish(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun enabled(stage: Stage, options: Config) = when (stage) {
        Stage.RIVERS -> options.rivers
        Stage.FORESTS, Stage.INSIDE -> options.forests
    }

    private fun needsCache(stage: Stage, p: GeoMath.Point, options: Config, stored: GeometryCache?, now: Long): Boolean {
        if (!enabled(stage, options)) return false
        if (stored == null) return true
        val moved = GeoMath.distance(stored.centre, p)
        if (stage == Stage.INSIDE) return now - stored.at >= 60_000L || moved >= minOf(150, options.radius).toDouble()
        if (stored.radius < options.radius) return true
        val spare = (stored.radius - options.radius).toDouble()
        return moved >= maxOf(50.0, spare - 100.0) || now - stored.at >= CACHE_TTL_MS
    }

    private fun maybeFetch(location: Location) {
        if (fetchBusy) return
        val now = SystemClock.elapsedRealtime()
        if (now < serverRetryAt || lastAnyFetchAt?.let { now - it < 2_000L } == true) return
        val p = point(location)
        val options = config
        // Na starcie: rzeka, potem lasy, następnie lekkie is_in. Później najdawniej
        // obsługiwany etap. Błąd lasu nie usuwa rzeki ani nie blokuje jej ponowień.
        val stage = Stage.values().filter { candidate ->
            val state = fetchStates.getValue(candidate)
            needsCache(candidate, p, options, state.cache, now) && now >= state.retryAt &&
                    (state.lastAttempt?.let { now - it >= MIN_FETCH_MS } ?: true)
        }.minWithOrNull(compareBy<Stage> { fetchStates.getValue(it).lastAttempt ?: Long.MIN_VALUE }.thenBy { it.ordinal }) ?: return
        val state = fetchStates.getValue(stage)
        // Mały zapas na postoju; większy dopiero przy rzeczywistym ruchu.
        val margin = if (location.hasSpeed()) (location.speed * 45).toInt().coerceIn(200, 1_500) else 200
        val fetchRadius = options.radius + margin
        fetchBusy = true
        activeStage = stage
        state.lastAttempt = now
        lastAnyFetchAt = now
        networkExecutor.execute {
            var loaded: List<Feature>? = null
            var failure: Exception? = null
            try { loaded = fetchFeatures(p, fetchRadius, stage) }
            catch (e: Exception) { failure = e }
            val result = loaded
            val error = failure
            main.post {
                if (destroyed) return@post
                fetchBusy = false
                activeStage = null
                state.transfer = null
                val finished = SystemClock.elapsedRealtime()
                if (result != null) {
                    state.failures = 0; state.retryAt = 0; state.error = null
                    // Znacznik czasu dotyczy punktu wysłanego do serwera, nie chwili
                    // otrzymania odpowiedzi: to istotne dla orientacyjnego is_in.
                    if (options.radius == config.radius && enabled(stage, config)) {
                        state.cache = GeometryCache(p, fetchRadius, now, result)
                    }
                } else {
                    val fetchError = error as? FetchException
                    state.failures = (state.failures + 1).coerceAtMost(3)
                    val delay = maxOf(
                        if (state.failures == 1) 5_000L else 10_000L,
                        fetchError?.retryMs ?: 0L
                    )
                    state.retryAt = if (fetchError?.terminal == true) Long.MAX_VALUE else finished + delay
                    state.error = fetchError?.summary ?: when (error) {
                        is SocketTimeoutException, is java.io.InterruptedIOException -> "przekroczony czas odpowiedzi"
                        is UnknownHostException -> "brak połączenia DNS"
                        is javax.net.ssl.SSLException -> "błąd połączenia TLS"
                        else -> "${error?.javaClass?.simpleName}: ${error?.message.orEmpty().take(70)}"
                    }
                    if (fetchError?.pauseAll == true) serverRetryAt = maxOf(serverRetryAt, finished + delay)
                    Log.w(TAG, "Overpass ${stage.name}: ${error?.message}", error)
                }
                // Od razu publikujemy wynik danego etapu, przed następnym zapytaniem.
                onTick()
            }
        }
    }

    // Każdy filtr wymaga własnej nazwy. Brak zwykłych, bezimiennych płatów zieleni.
    private val forestFilters = listOf(
        "[\"landuse\"=\"forest\"][\"name\"]",
        "[\"natural\"=\"wood\"][\"name\"]",
        "[\"boundary\"~\"^(forest|national_park)$\"][\"name\"]",
        "[\"leisure\"=\"nature_reserve\"][\"name\"]",
        "[\"boundary\"=\"protected_area\"][\"protect_class\"~\"^(1|1a|1b|2|4)$\"][\"name\"]"
    )
    private fun overpassQuery(p: GeoMath.Point, radius: Int, stage: Stage): String = buildString {
        append("[out:json][timeout:8];\n")
        when (stage) {
            Stage.RIVERS -> {
                append("way[\"waterway\"~\"^(river|canal)$\"][\"name\"](around:$radius,${p.lat},${p.lon});\n")
                append("out geom;")
            }
            Stage.FORESTS -> {
                append("(\n")
                for (filter in forestFilters) {
                    append("way$filter(around:$radius,${p.lat},${p.lon});\n")
                    append("rel$filter(around:$radius,${p.lat},${p.lon});\n")
                }
                append(");\nout geom;")
            }
            Stage.INSIDE -> {
                append("is_in(${p.lat},${p.lon})->.containing;\n(\n")
                for (filter in forestFilters) {
                    append("area.containing$filter;\n")
                    append("way.containing$filter;\n")
                }
                // Wystarczą nazwy i tagi. Nie pobieramy całych wielkich puszcz
                // przez pivot/out geom tylko po to, by rozpoznać pobyt wewnątrz.
                append(");\nout tags;")
            }
        }
    }

    private fun fetchFeatures(p: GeoMath.Point, radius: Int, stage: Stage): List<Feature> {
        val query = overpassQuery(p, radius, stage)
        val preferFallback = SystemClock.elapsedRealtime() < preferFallbackUntil
        val servers = if (preferFallback) listOf(OVERPASS_FALLBACK_URL, OVERPASS_URL)
        else listOf(OVERPASS_URL, OVERPASS_FALLBACK_URL)
        var firstFailure: Exception? = null
        for ((index, server) in servers.withIndex()) {
            if (destroyed || Thread.currentThread().isInterrupted) throw IOException("Usługa zatrzymana")
            main.post {
                if (!destroyed) {
                    val state = fetchStates.getValue(stage)
                    state.server = server.removePrefix("https://").substringBefore('/')
                    state.transfer = if (server == OVERPASS_URL) "pobieranie z serwera głównego…"
                    else "pobieranie z serwera zapasowego…"
                    updateDiagnostics()
                }
            }
            try {
                // Pusty poprawny wynik jest sukcesem: nie odpytujemy drugiego serwera.
                val features = fetchFeaturesFrom(query, stage, server)
                if (server == OVERPASS_URL) preferFallbackUntil = 0L
                else if (!preferFallback) {
                    // Po udanym przełączeniu przez minutę omijamy niesprawny serwer.
                    // Potem następne potrzebne zapytanie znów zacznie się od głównego.
                    preferFallbackUntil = SystemClock.elapsedRealtime() + FALLBACK_PREFERENCE_MS
                }
                return features
            } catch (e: Exception) {
                if (destroyed || Thread.currentThread().isInterrupted) throw e
                if (index == servers.lastIndex || !canTryFallback(e)) {
                    firstFailure?.let { e.addSuppressed(it) }
                    throw e
                }
                firstFailure = e
                Log.w(TAG, "Overpass ${stage.name}: $server niedostępny; próbuję drugi serwer", e)
                // Druga próba od razu, bez zwykłej przerwy 5–10 s. Połączenia
                // pozostają sekwencyjne; każdy adres najwyżej raz w tym pobraniu.
            }
        }
        throw IOException("Brak dostępnego serwera Overpass")
    }

    private fun canTryFallback(error: Exception): Boolean = when (error) {
        is FetchException -> error.allowFallback && !error.terminal && !error.pauseAll
        is javax.net.ssl.SSLException -> false
        is java.net.SocketException, is UnknownHostException,
        is java.io.EOFException, is java.io.InterruptedIOException -> true
        else -> false
    }

    private fun retryAfterMillis(header: String?): Long {
        val value = header?.trim() ?: return 0L
        value.toLongOrNull()?.let {
            return TimeUnit.SECONDS.toMillis(it.coerceAtLeast(0L)).coerceAtMost(Long.MAX_VALUE / 2)
        }
        // Retry-After dopuszcza także datę HTTP, np. Wed, 21 Oct 2015 07:28:00 GMT.
        return try {
            val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            (at - System.currentTimeMillis()).coerceIn(0L, Long.MAX_VALUE / 2)
        } catch (_: Exception) { 0L }
    }

    private fun fetchFeaturesFrom(query: String, stage: Stage, server: String): List<Feature> {
        val request = Request.Builder().url(server + "?data=" + URLEncoder.encode(query, "UTF-8"))
            .header("User-Agent", "CityOverlayApp/2.1 (personal Android geographic overlay)")
            .build()
        val call = httpClient.newCall(request)
        activeCall = call
        try {
            if (destroyed) { call.cancel(); throw IOException("Usługa zatrzymana") }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val retryHeader = response.header("Retry-After")
                    val limited = response.code in setOf(429, 406)
                    val retry = maxOf(retryAfterMillis(retryHeader), if (limited) 30_000L else 0L)
                    // Awaria odczytu opisu błędu nie może zgubić HTTP 429 / Retry-After.
                    val detail = runCatching {
                        response.peekBody(2_048).string().replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").take(700)
                    }.getOrDefault("")
                    val summary = when (response.code) {
                        400 -> "HTTP 400 — błąd zapytania"
                        404 -> "HTTP 404 — brak endpointu"
                        406 -> "HTTP 406 — serwer wymaga przerwy"
                        429 -> "HTTP 429 — limit serwera"
                        504 -> "HTTP 504 — serwer nie zdążył odpowiedzieć"
                        else -> "HTTP ${response.code}"
                    }
                    throw FetchException("HTTP ${response.code}: $detail", summary, retry,
                        terminal = response.code in setOf(400, 401, 403, 404),
                        pauseAll = limited || retryHeader != null,
                        allowFallback = response.code in setOf(408, 500, 502, 503, 504))
                }
                val body = response.body ?: throw FetchException("Pusta odpowiedź", "pusta odpowiedź serwera", allowFallback = true)
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Zbyt obszerna geometria")
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        if (destroyed || Thread.currentThread().isInterrupted) throw IOException("Anulowano")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) throw IOException("Zbyt obszerna geometria")
                        bytes.write(buffer, 0, count)
                    }
                }
                val text = bytes.toString("UTF-8")
                if (!text.trimStart().startsWith("{")) throw FetchException(text.take(700), "odpowiedź serwera nie jest JSON", allowFallback = true)
                val root = JSONObject(text)
                if (root.optString("remark").isNotBlank()) {
                    val remark = root.optString("remark")
                    val timedOut = remark.contains("timed out", true)
                    throw FetchException(remark, if (timedOut) "limit czasu Overpass" else "niepełna odpowiedź Overpass", allowFallback = timedOut)
                }
                return parseFeatures(root.getJSONArray("elements"), stage == Stage.INSIDE)
            }
        } finally { if (activeCall === call) activeCall = null }
    }

    private fun parseFeatures(elements: JSONArray, insideOnly: Boolean): List<Feature> {
        val features = linkedMapOf<String, Feature>()
        var pointCount = 0
        fun geometry(array: JSONArray?): List<GeoMath.Point>? {
            if (array == null || array.length() < 2) return null
            val points = ArrayList<GeoMath.Point>(array.length())
            for (j in 0 until array.length()) {
                if (++pointCount > MAX_POINTS) throw IOException("Za dużo punktów geometrii")
                val item = array.optJSONObject(j) ?: return null
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                points.add(GeoMath.Point(lat, lon))
            }
            return points
        }
        for (i in 0 until elements.length()) {
            if (destroyed || Thread.currentThread().isInterrupted) throw IOException("Anulowano")
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name:pl").ifBlank { tags.optString("name") }.trim().replace(Regex("\\s+"), " ").take(160)
            if (name.isBlank()) continue
            if (name.lowercase(Locale.ROOT) in setOf("las", "lasy", "puszcza", "forest", "wood", "woods", "rezerwat", "nature reserve", "park", "rzeka", "river", "kanał", "canal")) continue
            val kind = when {
                tags.optString("waterway") in setOf("river", "canal") -> Kind.RIVER
                tags.optString("landuse") == "forest" || tags.optString("natural") == "wood" ||
                        tags.optString("boundary") in setOf("forest", "national_park") ||
                        tags.optString("leisure") == "nature_reserve" ||
                        (tags.optString("boundary") == "protected_area" && tags.optString("protect_class") in setOf("1", "1a", "1b", "2", "4")) -> Kind.FOREST
                else -> continue
            }
            val importance = when {
                tags.optString("leisure") == "nature_reserve" || tags.optString("protect_class") in setOf("1", "1a", "1b", "4") -> 0
                tags.optString("boundary") == "national_park" || tags.optString("protect_class") == "2" -> 1
                tags.optString("boundary") == "forest" -> 2
                else -> 3
            }
            val key = "${el.optString("type")}/${el.getLong("id")}"
            if (insideOnly) {
                // is_in już ustaliło zawieranie punktu. Te rekordy mają tylko tagi.
                if (kind == Kind.FOREST) features[key] = Feature(key, name, kind, importance, emptyList(), emptyList())
                continue
            }
            val lines = mutableListOf<List<GeoMath.Point>>()
            val outerParts = mutableListOf<List<GeoMath.Point>>()
            val innerParts = mutableListOf<List<GeoMath.Point>>()
            var complete = true
            when (el.optString("type")) {
                "way" -> {
                    val g = geometry(el.optJSONArray("geometry")) ?: continue
                    lines.add(g)
                    if (kind == Kind.FOREST && g.first() == g.last()) outerParts.add(g)
                }
                "relation" -> {
                    val members = el.optJSONArray("members") ?: continue
                    for (j in 0 until members.length()) {
                        val member = members.optJSONObject(j) ?: continue
                        val role = member.optString("role")
                        val type = member.optString("type")
                        if (type == "relation") { complete = false; continue }
                        if (type != "way" || role !in setOf("", "outer", "inner")) continue
                        val g = geometry(member.optJSONArray("geometry"))
                        if (g == null) { complete = false; continue }
                        lines.add(g)
                        if (role == "inner") innerParts.add(g) else outerParts.add(g)
                    }
                }
                else -> continue
            }
            if (lines.isEmpty()) continue
            var rings: List<List<GeoMath.Point>> = emptyList()
            if (kind == Kind.FOREST && complete && outerParts.isNotEmpty()) {
                val outer = GeoMath.stitchRings(outerParts)
                val inner = GeoMath.stitchRings(innerParts)
                if (outer.complete && inner.complete) rings = outer.closed + inner.closed
            }
            features[key] = Feature(key, name, kind, importance, lines, rings)
        }
        return features.values.toList()
    }

    private fun requestRender() {
        renderVersion++
        if (!rendering) renderLatest()
    }

    private fun currentCity(p: GeoMath.Point, options: Config, now: Long): CityResult? =
        city?.takeIf {
            now - it.at < 10 * 60_000L &&
                    GeoMath.distance(it.point, p) < minOf(options.radius, 1_000).toDouble()
        }

    /** Tylko dane lub kreski; jedna linia na każdą włączoną kategorię. */
    private fun overlayLines(options: Config, cityLine: String? = null, riverLine: String? = null, forestLine: String? = null): String {
        val lines = mutableListOf<String>()
        if (options.cities) lines.add(cityLine ?: "📍 -----")
        if (options.rivers) lines.add(riverLine ?: "🌊 -----")
        if (options.forests) lines.add(forestLine ?: "🌲 -----")
        return lines.joinToString("\n")
    }

    /** Stan bieżący dla MainActivity; wywoływany tylko w głównym wątku. */
    private fun updateDiagnostics() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val location = latestLocation?.takeIf { fresh(it) }
        val lines = mutableListOf(if (location == null) "GPS: oczekiwanie na świeżą pozycję." else "GPS: pozycja aktualna.")
        if (config.cities) {
            val label = when {
                geocoderBusy -> "ustalam nazwę…"
                cityFailed -> {
                    val seconds = ((geocodeRetryAt - now).coerceAtLeast(0L) + 999) / 1_000
                    (cityError ?: "brak nazwy miejscowości") + if (seconds > 0) " · ponowię za $seconds s" else ""
                }
                location == null -> "oczekiwanie na GPS"
                currentCity(point(location), config, now) != null -> "nazwa aktualna"
                else -> "oczekiwanie na nową nazwę"
            }
            lines.add("Miejscowość: $label")
        }
        for (stage in Stage.values().filter { enabled(it, config) }) {
            val state = fetchStates.getValue(stage)
            val cached = state.cache
            val label = when {
                activeStage == stage -> state.transfer ?: if (state.error == null) "pobieranie…" else "ponawiam po błędzie: ${state.error}"
                state.retryAt == Long.MAX_VALUE -> "${state.error} · automatyczne ponawianie wstrzymane"
                state.error != null -> {
                    val seconds = ((maxOf(state.retryAt, serverRetryAt) - now).coerceAtLeast(0L) + 999) / 1_000
                    "${state.error} · " + if (seconds > 0) "ponowię za $seconds s" else "oczekiwanie na ponowienie"
                }
                now < serverRetryAt -> "przerwa wymagana przez serwer · ${((serverRetryAt - now) + 999) / 1_000} s"
                location == null -> "oczekiwanie na GPS"
                needsCache(stage, point(location), config, cached, now) -> "oczekiwanie na pobranie"
                cached != null && cached.features.isEmpty() -> "pobrano dane; brak nazwanych obiektów"
                else -> "dane pobrane"
            }
            lines.add("${stage.label}: $label" + state.server?.let { " [$it]" }.orEmpty())
        }
        geometryError?.let { lines.add("Geometria: $it") }
        publishDiagnostics(lines.joinToString("\n"))
    }

    private fun renderLatest() {
        if (destroyed || !overlayAttached) return
        val l = latestLocation?.let { Location(it) }
        if (l == null || !fresh(l)) {
            setOverlayText(overlayLines(config))
            return
        }
        val version = renderVersion
        val options = config
        val riverCache = fetchStates.getValue(Stage.RIVERS).cache
        val forestCache = fetchStates.getValue(Stage.FORESTS).cache
        val insideCache = fetchStates.getValue(Stage.INSIDE).cache
        val p = point(l)
        val now = SystemClock.elapsedRealtime()
        val cityLine = if (!options.cities) null else currentCity(p, options, now)?.let { "\uD83D\uDCCD ${it.text}" }
        val oldRiver = previousRiver
        val oldForest = previousForest
        rendering = true
        geometryExecutor.execute {
            val result = try {
                val nearby = (riverCache?.features.orEmpty() + forestCache?.features.orEmpty()).asSequence()
                    .filter { (it.kind == Kind.RIVER && options.rivers) || (it.kind == Kind.FOREST && options.forests) }
                    .map { Candidate(it, GeoMath.nearest(p, it.lines, it.rings)) }
                    .filter { it.nearest.metres <= options.radius }.toMutableList()
                // Lekka wskazówka is_in wygasa po 90 s lub przemieszczeniu poza
                // min(promień, 500 m). Po oddaleniu o >50 m pokazujemy samą nazwę,
                // bez zapewnienia „w obrębie” i bez pozornie dokładnego metrażu.
                if (options.forests && insideCache != null && now - insideCache.at < 90_000L) {
                    val moved = GeoMath.distance(p, insideCache.centre)
                    if (moved <= minOf(options.radius, 500).toDouble()) {
                        insideCache.features.forEach { hint ->
                            // Jeżeli znamy cały obrys, wynik lokalnej geometrii jest ważniejszy.
                            val hasFullGeometry = forestCache?.features.orEmpty().any { it.name == hint.name && it.rings.isNotEmpty() }
                            if (!hasFullGeometry) nearby.add(Candidate(hint, GeoMath.Nearest(0.0, 0.0, moved <= 50.0)))
                        }
                    }
                }
                val river = choose(nearby.filter { it.feature.kind == Kind.RIVER }, oldRiver)
                val forest = choose(nearby.filter { it.feature.kind == Kind.FOREST }, oldForest)
                RenderResult(
                    overlayLines(options, cityLine, river?.let { featureLabel(it, l) }, forest?.let { featureLabel(it, l) }),
                    river?.feature?.name, forest?.feature?.name
                )
            } catch (e: Exception) {
                Log.e(TAG, "Błąd obliczania geometrii", e)
                RenderResult(overlayLines(options, cityLine), null, null, e.message ?: "Nie można odczytać geometrii")
            }
            main.post {
                if (destroyed) return@post
                rendering = false
                if (version != renderVersion) renderLatest()
                else {
                    previousRiver = result.riverName; previousForest = result.forestName
                    geometryError = result.error
                    setOverlayText(result.text)
                    updateDiagnostics()
                }
            }
        }
    }

    private fun choose(candidates: List<Candidate>, previousName: String?): Candidate? {
        val sorted = candidates.sortedWith(compareBy<Candidate> { if (it.nearest.inside) 0 else 1 }
            .thenBy { if (it.nearest.inside) it.feature.importance else 0 }
            .thenBy { it.nearest.metres }.thenBy { it.feature.name }.thenBy { it.feature.key })
        val best = sorted.firstOrNull() ?: return null
        // Mała histereza zapobiega skakaniu nazw przy dwóch podobnie bliskich obiektach.
        val old = sorted.firstOrNull { it.feature.name == previousName } ?: return best
        return if (old.nearest.inside == best.nearest.inside &&
            (!best.nearest.inside || old.feature.importance == best.feature.importance) &&
            old.nearest.metres <= best.nearest.metres + 100) old else best
    }

    private fun featureLabel(candidate: Candidate, location: Location): String {
        val f = candidate.feature
        val n = candidate.nearest
        val prefix = if (f.kind == Kind.RIVER) "🌊" else "🌲"
        if (n.inside) return "$prefix ${f.name} • w obrębie"
        val distance = GeoMath.approximateDistance(n.metres)
        if (distance.isEmpty()) return "$prefix ${f.name}"
        val cardinal = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun sector(bearing: Double): Int {
            val normalized = ((bearing % 360) + 360) % 360
            return (((normalized + 22.5) % 360) / 45).toInt()
        }
        val compass = cardinal[sector(n.bearing)]
        // Kurs dotyczy kierunku jazdy. Stary lub niepewny odczyt nie daje strzałki.
        val courseAgeMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        val hasCourse = courseAgeMs in 0..10_000L &&
                location.hasBearing() && location.bearing.isFinite() &&
                location.hasSpeed() && location.speed.isFinite() && location.speed >= 1.5f &&
                (!location.hasBearingAccuracy() || (location.bearingAccuracyDegrees.isFinite() && location.bearingAccuracyDegrees in 0f..45f)) &&
                (!location.hasSpeedAccuracy() || (location.speedAccuracyMetersPerSecond.isFinite() &&
                        location.speedAccuracyMetersPerSecond >= 0f && location.speed > location.speedAccuracyMetersPerSecond))
        val direction = if (hasCourse) {
            // Zwykłe znaki tekstowe, bez wariantów emoji / selektorów prezentacji.
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            "${arrows[sector(n.bearing - location.bearing)]} $compass"
        } else compass
        return "$prefix ${f.name} • $distance $direction"
    }

    private fun setOverlayText(text: String) {
        if (!destroyed && overlayAttached && overlayView.text.toString() != text) overlayView.text = text
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        activeCall?.cancel()
        httpClient.connectionPool.evictAll()
        networkExecutor.shutdownNow()
        legacyGeocoderExecutor.shutdownNow()
        geometryExecutor.shutdownNow()
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        if (overlayAttached) {
            try { windowManager.removeView(overlayView) } catch (e: IllegalArgumentException) { Log.w(TAG, "Nakładka już odłączona") }
            overlayAttached = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishRunning(false)
        publishDiagnostics(startupError?.let { "Błąd uruchomienia: $it" } ?: "Nakładka zatrzymana.")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}