package com.gnssinspector

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssAntennaInfo
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Sat(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
    val usedInFix: Boolean,
    val carrierHz: Double?,
    val basebandCn0: Double?,
) {
    val id get() = Labels.satId(constellation, svid)
    val band get() = Labels.band(constellation, carrierHz)
}

class NavEntry(
    val type: Int,
    val svid: Int,
    val messageId: Int,
    val submessageId: Int,
    val status: Int,
    val data: ByteArray,
    val receivedAt: Long,
    val lnav: LnavSubframe?,
)

/** One second of a satellite's track: where it was and how strongly it was heard. */
data class SkySample(val atMs: Long, val elevation: Float, val azimuth: Float, val cn0: Float, val used: Boolean)

/** A GnssAutomaticGainControl reading, flattened so the UI does not need API 33 classes. */
data class Agc(val constellation: Int, val carrierHz: Double, val levelDb: Double)

/**
 * Registers every GNSS listener Android offers and exposes what arrives as Compose state.
 * All callbacks are delivered on the main thread.
 */
private const val HISTORY_SAMPLES = 1800 // 30 minutes at one sample per second

class GnssCollector(context: Context) {
    private val app = context.applicationContext
    val lm: LocationManager = app.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var running by mutableStateOf(false); private set

    var location by mutableStateOf<Location?>(null); private set
    var locationCount by mutableIntStateOf(0); private set

    var satellites by mutableStateOf<List<Sat>>(emptyList()); private set
    var ttffMs by mutableStateOf<Int?>(null); private set
    var engineRunning by mutableStateOf<Boolean?>(null); private set

    var clock by mutableStateOf<GnssClock?>(null); private set
    var measurements by mutableStateOf<List<GnssMeasurement>>(emptyList()); private set
    var agc by mutableStateOf<List<Agc>>(emptyList()); private set
    var measurementStatus by mutableStateOf<Int?>(null); private set
    var measurementCount by mutableIntStateOf(0); private set
    var lastMeasurementAt by mutableLongStateOf(0L); private set

    var navStatus by mutableStateOf<Int?>(null); private set
    var navCount by mutableIntStateOf(0); private set
    /** Most recent navigation message per (type, satellite, subframe/page). */
    val navLatest = mutableStateMapOf<String, NavEntry>()
    val gpsNav = mutableStateMapOf<Int, GpsSatNav>()
    val almanacs = mutableStateMapOf<Int, Almanac>()
    var ionoUtc by mutableStateOf<IonoUtc?>(null); private set

    val nmeaLog = mutableStateListOf<String>()
    val nmeaCounts = mutableStateMapOf<String, Int>()
    val nmeaLatest = mutableStateMapOf<String, String>()

    /** Complete GSV sentence groups, keyed by talker + signal ID (e.g. "GP/1"). */
    val gsvGroups = mutableStateMapOf<String, List<String>>()
    private val gsvPending = HashMap<String, MutableList<String>>()

    var antennas by mutableStateOf<List<GnssAntennaInfo>>(emptyList()); private set

    // Per-satellite track, kept outside snapshot state; [historyVersion] signals changes.
    private val history = HashMap<String, ArrayDeque<SkySample>>()
    var historyVersion by mutableIntStateOf(0); private set

    fun historyOf(satId: String): List<SkySample> = history[satId]?.toList() ?: emptyList()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            location = l
            locationCount++
        }

        // Implemented explicitly: on API < 30 these are abstract and calling them would crash.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onStarted() { engineRunning = true }
        override fun onStopped() { engineRunning = false }
        override fun onFirstFix(ttffMillis: Int) { ttffMs = ttffMillis }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellites = (0 until status.satelliteCount).map { i ->
                Sat(
                    constellation = status.getConstellationType(i),
                    svid = status.getSvid(i),
                    cn0 = status.getCn0DbHz(i),
                    elevation = status.getElevationDegrees(i),
                    azimuth = status.getAzimuthDegrees(i),
                    hasAlmanac = status.hasAlmanacData(i),
                    hasEphemeris = status.hasEphemerisData(i),
                    usedInFix = status.usedInFix(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                    basebandCn0 = if (Build.VERSION.SDK_INT >= 30 && status.hasBasebandCn0DbHz(i))
                        status.getBasebandCn0DbHz(i).toDouble() else null,
                )
            }.sortedWith(compareBy({ it.constellation }, { it.svid }, { it.carrierHz }))
            recordHistory()
        }
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            clock = event.clock
            measurements = event.measurements.sortedWith(
                compareBy({ it.constellationType }, { it.svid }, { if (it.hasCarrierFrequencyHz()) it.carrierFrequencyHz else 0f })
            )
            if (Build.VERSION.SDK_INT >= 33) {
                agc = event.gnssAutomaticGainControls.map { Agc(it.constellationType, it.carrierFrequencyHz.toDouble(), it.levelDb) }
            }
            measurementCount++
            lastMeasurementAt = SystemClock.elapsedRealtime()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(status: Int) { measurementStatus = status }
    }

    private val navCallback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(m: GnssNavigationMessage) = onNavMessage(m)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(status: Int) { navStatus = status }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        val line = message.trim()
        nmeaLog.add(line)
        if (nmeaLog.size > 300) nmeaLog.removeRange(0, nmeaLog.size - 300)
        val type = line.substringBefore(',').removePrefix("$")
        if (type.isNotEmpty()) {
            nmeaCounts[type] = (nmeaCounts[type] ?: 0) + 1
            // GSV and GSA come in multi-sentence groups; keeping the last is enough for display.
            nmeaLatest[type.drop(2)] = line
            if (type.endsWith("GSV")) onGsv(line)
        }
    }

    private fun recordHistory() {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        for ((id, signals) in satellites.groupBy { it.id }) {
            val track = history.getOrPut(id) { ArrayDeque() }
            if (track.isNotEmpty() && now - track.last().atMs < 900) continue
            val best = signals.maxBy { it.cn0 }
            track.addLast(SkySample(now, best.elevation, best.azimuth, best.cn0, signals.any { it.usedInFix }))
            while (track.size > HISTORY_SAMPLES) track.removeFirst()
            changed = true
        }
        if (changed) historyVersion++
    }

    private fun onGsv(line: String) {
        val f = line.substringBefore('*').split(',')
        val total = f.getOrNull(1)?.toIntOrNull() ?: return
        val number = f.getOrNull(2)?.toIntOrNull() ?: return
        // NMEA 4.10+ appends a signal ID, leaving one field after the 4-field satellite blocks.
        val signal = if ((f.size - 4) % 4 == 1) f.last() else ""
        val key = f[0].removePrefix("$").take(2) + "/" + signal
        if (number == 1) gsvPending[key] = mutableListOf()
        val group = gsvPending[key] ?: return
        group += line
        if (number == total) gsvGroups[key] = gsvPending.remove(key)!!.toList()
    }

    private val antennaListener by lazy {
        GnssAntennaInfo.Listener { infos -> antennas = infos }
    }

    private fun onNavMessage(m: GnssNavigationMessage) {
        navCount++
        val type = m.type
        val lnav = if (type == 0x0101 || type == 0x0401) LnavSubframe.parse(m.data) else null
        val key = "%04X/%03d/%02d".format(type, m.svid, m.submessageId)
        navLatest[key] = NavEntry(type, m.svid, m.messageId, m.submessageId, m.status, m.data.copyOf(), System.currentTimeMillis(), lnav)

        if (type != 0x0101 || lnav == null || lnav.preamble != 0x8B || lnav.parityOkWords < 10) return
        val prev = gpsNav[m.svid] ?: GpsSatNav(m.svid)
        var next = prev.copy(subframesSeen = prev.subframesSeen + 1, lastTow = lnav.towCount * 6)
        when (lnav.subframeId) {
            1 -> next = next.copy(clock = Lnav.clock(lnav))
            2 -> next = next.copy(orbit1 = Lnav.orbit1(lnav))
            3 -> next = next.copy(orbit2 = Lnav.orbit2(lnav))
            4, 5 -> {
                val page = lnav.pageSvId
                if (page in 1..32) Lnav.almanac(lnav).let { almanacs[it.prn] = it }
                if (lnav.subframeId == 4 && page == 56) ionoUtc = Lnav.ionoUtc(lnav)
            }
        }
        gpsNav[m.svid] = next
    }

    /** Whether the chip says it provides raw measurements; null when Android can't tell (before 12). */
    val supportsMeasurements: Boolean?
        get() = if (Build.VERSION.SDK_INT >= 31) lm.gnssCapabilities.hasMeasurements() else null

    val supportsNavMessages: Boolean?
        get() = if (Build.VERSION.SDK_INT >= 31) lm.gnssCapabilities.hasNavigationMessages() else null

    val locationEnabled: Boolean
        get() = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start() {
        if (running) return
        running = true
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        lm.registerGnssStatusCallback(statusCallback, handler)
        if (Build.VERSION.SDK_INT >= 31) {
            // Full tracking disables duty cycling so measurements are continuous (needed for carrier phase).
            val request = GnssMeasurementRequest.Builder().setFullTracking(true).build()
            lm.registerGnssMeasurementsCallback(request, app.mainExecutor, measurementCallback)
            lm.registerAntennaInfoListener(app.mainExecutor, antennaListener)
        } else {
            lm.registerGnssMeasurementsCallback(measurementCallback, handler)
        }
        lm.registerGnssNavigationMessageCallback(navCallback, handler)
        lm.addNmeaListener(nmeaListener, handler)
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(locationListener)
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measurementCallback)
        lm.unregisterGnssNavigationMessageCallback(navCallback)
        lm.removeNmeaListener(nmeaListener)
        if (Build.VERSION.SDK_INT >= 31) lm.unregisterAntennaInfoListener(antennaListener)
    }

    /** Leap seconds from the receiver clock when it knows them, otherwise the current value. */
    val leapSeconds: Int
        get() = clock?.takeIf { it.hasLeapSecond() }?.leapSecond ?: ionoUtc?.leapSeconds ?: DEFAULT_LEAP_SECONDS

    /** Current GPS (week, time of week) based on the last fix time, or the system clock. */
    fun gpsNow(): Pair<Int, Double> {
        val l = location
        val utcMs = if (l != null) {
            l.time + (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        } else System.currentTimeMillis()
        return gpsTime(utcMs, leapSeconds)
    }
}

/**
 * Pseudorange in metres from a raw measurement, following Android's GnssLogger method.
 * Returns null when the receiver has not decoded enough of the signal to resolve it.
 */
fun pseudorange(clock: GnssClock, m: GnssMeasurement, leapSeconds: Int): Double? {
    if (!clock.hasFullBiasNanos()) return null
    val bias = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
    val rxWhole = clock.timeNanos - clock.fullBiasNanos // GPS time in ns, kept as Long for precision
    val frac = m.timeOffsetNanos - bias
    val weekNs = 604_800_000_000_000L
    val dayNs = 86_400_000_000_000L
    val s = m.state
    val rxNs = when (m.constellationType) {
        GnssStatus.CONSTELLATION_GLONASS -> {
            if ((s and (GnssMeasurement.STATE_GLO_TOD_DECODED or GnssMeasurement.STATE_GLO_TOD_KNOWN)) == 0) return null
            // GLONASS time is UTC(SU) + 3 h and counts days, not weeks.
            Math.floorMod(rxWhole + 10_800_000_000_000L - leapSeconds * 1_000_000_000L, dayNs) + frac
        }
        GnssStatus.CONSTELLATION_BEIDOU -> {
            if ((s and (GnssMeasurement.STATE_TOW_DECODED or GnssMeasurement.STATE_TOW_KNOWN)) == 0) return null
            Math.floorMod(rxWhole - 14_000_000_000L, weekNs) + frac // BDT = GPST − 14 s
        }
        else -> {
            val towOk = GnssMeasurement.STATE_TOW_DECODED or GnssMeasurement.STATE_TOW_KNOWN or
                GnssMeasurement.STATE_GAL_E1B_PAGE_SYNC
            if ((s and towOk) == 0) return null
            Math.floorMod(rxWhole, weekNs) + frac
        }
    }
    var dt = rxNs - m.receivedSvTimeNanos
    // Handle the week/day rollover between transmit and receive.
    val period = if (m.constellationType == GnssStatus.CONSTELLATION_GLONASS) dayNs else weekNs
    if (dt > period / 2) dt -= period
    if (dt < -period / 2) dt += period
    val range = dt / 1e9 * SPEED_OF_LIGHT
    return range.takeIf { it in 1.0e7..5.0e7 }
}
