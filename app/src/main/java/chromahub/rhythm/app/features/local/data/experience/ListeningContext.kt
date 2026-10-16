// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Bundle
import android.os.Looper
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlin.math.round
import kotlinx.coroutines.*

@Keep
data class ListeningSituation(
    val place: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    val locationAgeMs: Long? = null,
    val precise: Boolean = false,
    val routeType: String = "unknown",
    val routeName: String? = null,
    val connectedOutputs: List<OutputSnapshot> = emptyList(),
    val routeEvidence: String = "system_route_inferred",
    val weekday: Int = 0,
    val hour: Int = 0,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val locationProvider: String? = null,
    val systemVolume: Int = 0,
    val systemVolumeMax: Int = 0,
    val charging: Boolean = false,
    val batteryPercent: Int? = null,
    val weather: WeatherSnapshot? = null,
    val brightnessSetting: Int? = null,
    val automaticBrightness: Boolean? = null,
    val playerVolume: Float? = null,
    val ambientSound: AmbientSound? = null,
    val motion: MotionSnapshot? = null,
    val daylight: DaylightSnapshot? = null,
    val observedAt: Long? = null,
    val contextAgeMs: Long? = null,
    val carriedForward: Boolean = false,
)

@Keep
data class OutputSnapshot(
    val type: Int,
    val name: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>,
)

/**
 * No background location permission. Fresh fixes are requested only while the activity is visible.
 */
class ListeningContext private constructor(private val context: Context) : LocationListener {
    private val prefs = context.getSharedPreferences("listening_context", Context.MODE_PRIVATE)
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var latest: Location? = null
    @Volatile private var cached: ListeningSituation? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var refreshJob: Job? = null
    private var lastProbe = Long.MIN_VALUE
    @Volatile private var slowSampling = false
    private var visible = false
    private var playing = false
    val enabled
        get() = prefs.getBoolean("location", false)

    val precise
        get() = prefs.getBoolean("precise", false)

    val place
        get() = prefs.getString("place", "").orEmpty()

    fun configure(
        enabled: Boolean = this.enabled,
        precise: Boolean = this.precise,
        place: String = this.place,
    ) {
        prefs.edit(commit = true) {
            putBoolean("location", enabled)
            putBoolean("precise", precise)
            putString("place", place.trim().take(80))
        }
        if (!enabled) latest = null
        cached = null
        stop()
        refresh()
        requestRefresh(true)
        ListeningLedger.get(context).invalidate()
    }

    fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun refresh() {
        visible = true
        MobilityContext.get(context).visible(true)
        if (!enabled || !playing || !permitted()) return
        try {
            val fine =
                precise &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
            val provider =
                if (fine && locations.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    LocationManager.GPS_PROVIDER
                else LocationManager.NETWORK_PROVIDER
            latest = locations.getLastKnownLocation(provider)
            locations.requestLocationUpdates(provider, 60_000L, 100f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            latest = null
        } catch (_: IllegalArgumentException) {
            latest = null
        }
    }

    fun stop() {
        visible = false
        MobilityContext.get(context).visible(false)
        runCatching { locations.removeUpdates(this) }
    }

    fun playback(value: Boolean) {
        if (playing == value) return
        playing = value
        if (!value) runCatching { locations.removeUpdates(this) } else if (visible) refresh()
    }

    override fun onLocationChanged(location: Location) {
        latest = Location(location)
        requestRefresh(true)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        latest = null
    }

    /** Playback/UI never perform system-service, settings-provider or weather I/O here. */
    fun snapshot(now: Long = System.currentTimeMillis()): ListeningSituation {
        requestRefresh()
        val date = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        val old = cached
        val age = old?.observedAt?.let { now - it }
        if (old == null || age == null || age !in 0..5 * 60_000L)
            return ListeningSituation(
                place = place.takeIf(String::isNotBlank),
                weekday = date.dayOfWeek.value,
                hour = date.hour,
                daylight = ContextSignals.daylight(now, null, null),
                contextAgeMs = age,
            )
        val locationAge = old.locationAgeMs?.plus(age)
        val locationValid = enabled && locationAge != null && locationAge <= 15 * 60_000L
        val weather =
            old.weather?.takeIf {
                enabled && prefs.getBoolean("weather", false) && now - it.at in 0..90 * 60_000L
            }
        return old.copy(
            place = place.takeIf(String::isNotBlank),
            weekday = date.dayOfWeek.value,
            hour = date.hour,
            latitude = old.latitude.takeIf { locationValid },
            longitude = old.longitude.takeIf { locationValid },
            altitudeM = old.altitudeM.takeIf { locationValid },
            speedMps = old.speedMps.takeIf { locationValid },
            bearingDegrees = old.bearingDegrees.takeIf { locationValid },
            locationProvider = old.locationProvider.takeIf { locationValid },
            locationAccuracyM = old.locationAccuracyM.takeIf { locationValid },
            locationAgeMs = locationAge.takeIf { locationValid },
            precise = old.precise && locationValid,
            weather = weather,
            ambientSound =
                old.ambientSound?.takeIf {
                    prefs.getBoolean("ambient_sound", false) && now - it.at in 0..5 * 60_000L
                },
            motion =
                old.motion?.takeIf {
                    prefs.getBoolean("motion", true) && now - it.at in 0..5 * 60_000L
                },
            daylight =
                ContextSignals.daylight(
                    now,
                    old.latitude.takeIf { locationValid },
                    old.longitude.takeIf { locationValid },
                    weather?.isDay,
                ),
            contextAgeMs = age,
            carriedForward = age > 0,
        )
    }

    @Synchronized
    fun requestRefresh(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            refreshJob?.isActive == true ||
                lastProbe != Long.MIN_VALUE &&
                    now - lastProbe <
                        if (force) 5_000L
                        else if (slowSampling) ListeningPolicy.PRESSURE_CONTEXT_MS
                        else ListeningPolicy.CONTEXT_MS
        )
            return
        lastProbe = now
        refreshJob =
            scope.launch {
                try {
                    cached = readSystemSnapshot(System.currentTimeMillis())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    /* Keep the last qualified observation until its explicit TTL. */
                }
            }
    }

    private fun readSystemSnapshot(now: Long): ListeningSituation {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val charging = battery.isCharging
        val percent =
            battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf {
                it in 0..100
            }
        slowSampling =
            power.isPowerSaveMode ||
                !charging && percent != null && percent <= 20 ||
                android.os.Build.VERSION.SDK_INT >= 29 &&
                    power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs =
            runCatching {
                    audio
                        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .filter { it.isSink }
                        .map {
                            OutputSnapshot(
                                it.type,
                                it.productName?.toString().orEmpty().take(100),
                                it.sampleRates.toList(),
                                it.channelCounts.toList(),
                                it.encodings.toList(),
                            )
                        }
                }
                .getOrDefault(emptyList())
        val route =
            runCatching {
                    (context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter)
                        .getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
                }
                .getOrNull()
        val routeType =
            when {
                route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> "bluetooth"
                route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV -> "cast"
                audio.isWiredHeadsetOn -> "wired"
                audio.isBluetoothA2dpOn || audio.isBluetoothScoOn -> "bluetooth"
                outputs.any {
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                } -> "usb"
                else -> "speaker"
            }
        val fine =
            precise &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        val fix =
            latest?.takeIf {
                enabled &&
                    permitted() &&
                    now - it.time in 0..15 * 60_000 &&
                    it.hasAccuracy() &&
                    it.accuracy <= 5000f
            }
        val date = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        return ListeningSituation(
            place.takeIf { it.isNotBlank() },
            fix?.latitude?.let { if (fine) it else round(it * 100) / 100 },
            fix?.longitude?.let { if (fine) it else round(it * 100) / 100 },
            fix?.accuracy?.let { if (fine) it else maxOf(it, 1100f) },
            fix?.let { now - it.time },
            fine && fix != null,
            routeType,
            route?.name?.toString()?.take(100),
            outputs,
            weekday = date.dayOfWeek.value,
            hour = date.hour,
            altitudeM = fix?.takeIf { fine && it.hasAltitude() }?.altitude,
            speedMps = fix?.takeIf { it.hasSpeed() }?.speed,
            bearingDegrees = fix?.takeIf { fine && it.hasBearing() }?.bearing,
            locationProvider = fix?.provider,
            systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            systemVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            charging = charging,
            batteryPercent = percent,
            weather = ListeningWeather.get(context).snapshot(fix?.latitude, fix?.longitude, now),
            brightnessSetting =
                runCatching {
                        android.provider.Settings.System.getInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        )
                    }
                    .getOrNull()
                    ?.coerceIn(0, 255),
            automaticBrightness =
                runCatching {
                        android.provider.Settings.System.getInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        ) == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    }
                    .getOrNull(),
            playerVolume =
                chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(context)
                    .appVolume
                    .value,
            ambientSound = ListeningSound.get(context).snapshot(now),
            motion = MobilityContext.get(context).snapshot(fix, now),
            daylight =
                ContextSignals.daylight(
                    now,
                    fix?.latitude,
                    fix?.longitude,
                    ListeningWeather.get(context)
                        .snapshot(fix?.latitude, fix?.longitude, now)
                        ?.isDay,
                ),
            observedAt = now,
            contextAgeMs = 0,
        )
    }

    companion object {
        @Volatile private var instance: ListeningContext? = null

        fun get(context: Context): ListeningContext =
            instance
                ?: synchronized(this) {
                    instance ?: ListeningContext(context.applicationContext).also { instance = it }
                }

        fun outputGroup(situation: ListeningSituation): String {
            val types =
                when (situation.routeType) {
                    "bluetooth" -> setOf(7, 8, 26, 27)
                    "wired" -> setOf(3, 4)
                    "usb" -> setOf(11, 12, 22)
                    else -> emptySet()
                }
            val names =
                situation.connectedOutputs
                    .filter { it.type in types }
                    .map { it.name.trim().lowercase(java.util.Locale.ROOT) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            return situation.routeType +
                ":" +
                (names.takeIf { it.isNotEmpty() }?.joinToString("|")
                    ?: situation.routeName.orEmpty().lowercase(java.util.Locale.ROOT))
        }

        fun region(situation: ListeningSituation?): String? =
            situation?.let { s ->
                if (s.latitude != null && s.longitude != null)
                    "${round(s.latitude * 100) / 100}:${round(s.longitude * 100) / 100}"
                else s.place?.takeIf { it.isNotBlank() }
            }
    }
}
