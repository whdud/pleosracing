package com.byeolmajung.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.byeolmajung.sky.Observer

private const val TAG = "Byeolmajung"

/** Car position and heading from the Pleos FusedLocation SDK. */
class CarLocation(context: Context, private val onUpdate: (Observer, heading: Double?) -> Unit) {

    private val fused = FusedLocation(context)
    var isAvailable = false
        private set

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            onUpdate(Observer(location.latitude, location.longitude), location.heading.toDouble())
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "FusedLocation error", exception)
        }
    }

    fun start() {
        isAvailable = runCatching { fused.isSupported() }.getOrDefault(false)
        if (!isAvailable) {
            Log.i(TAG, "FusedLocation not supported here; using a preset place")
            return
        }
        runCatching {
            fused.initialize()
            fused.getLastKnownLocation(listener)
            fused.registerFusedLocationCallback(listener)
        }.onFailure {
            isAvailable = false
            Log.w(TAG, "FusedLocation start failed", it)
        }
    }

    fun stop() {
        if (!isAvailable) return
        runCatching {
            fused.unregisterFusedLocationCallback(listener)
            fused.release()
        }
    }
}

/**
 * Car features used in stargazing mode: the car's lights and the sunroof blind.
 * Every call checks the capability first; on cars without the feature (or without permission) it quietly does nothing.
 */
class CarFeatures(context: Context, private val onSunroofBlindChanged: (open: Boolean) -> Unit) {

    private val vehicle = Vehicle(context)
    private val roof = WindowArea(WindowValue.ROOF_TOP_1)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var savedCabin: LightSwitch? = null
    private val savedReading = HashMap<SeatArea, LightSwitch>()
    private val savedAmbient = HashMap<SeatArea, LightSwitch>()
    private var blindRange: IntRange? = null

    var hasSunroofBlind = false
        private set

    private val blindListener = object : WindowPositionListener {
        override fun onWindowPositionUpdated(position: Int?) {
            position?.let { onSunroofBlindChanged(isOpen(it)) }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "sunroof blind listener", e)
        }
    }

    fun start() {
        // On cars or emulators without the Pleos Vehicle Service, every call below may fail; none of it is essential.
        runCatching { startSunroofWatch() }.onFailure { Log.w(TAG, "Vehicle features unavailable", it) }
    }

    private fun startSunroofWatch() {
        vehicle.initialize()
        val window = vehicle.getWindow()
        window.checkWindowBlindPositionCapabilities(
            onComplete = { config, bounds ->
                val b = bounds[roof]
                if (config.accessType != AccessType.NONE && b != null) {
                    blindRange = b.minValue..b.maxValue
                    hasSunroofBlind = true
                    window.registerWindowBlindPosition(roof, blindListener)
                    window.getCurrentWindowBlindPosition(roof, { pos -> pos?.let { onSunroofBlindChanged(isOpen(it)) } }, {})
                }
            },
            onFailed = { Log.i(TAG, "no sunroof blind: ${it.message}") },
        )
    }

    fun stop() {
        runCatching { if (hasSunroofBlind) vehicle.getWindow().unregisterWindowBlindPosition(roof, blindListener) }
        runCatching { vehicle.release() }
    }

    // Assumption to verify on the emulator: like AAOS WINDOW_POS, the maximum value means fully open.
    private fun isOpen(position: Int): Boolean {
        val range = blindRange ?: return false
        return position - range.first > (range.last - range.first) / 2
    }

    // Assumption to verify: FORWARD opens the blind (positive direction, like AAOS WINDOW_MOVE).
    fun openSunroofBlind(onResult: (Boolean) -> Unit) {
        if (!hasSunroofBlind) return onResult(false)
        runCatching {
            vehicle.getWindow().moveWindowBlind(roof, MoveControl.FORWARD, { _, _ -> onResult(true) }, {
                Log.w(TAG, "open blind failed", it)
                onResult(false)
            })
        }.onFailure { onResult(false) }
    }

    /**
     * Stargazing mode: switches every light in the car off (cabin, reading and ambient lights), remembering
     * each one's previous state. [onResult] is called once, with true as soon as any light was switched off.
     */
    fun turnOffAllLights(onResult: (Boolean) -> Unit) {
        var reported = false
        val report = { ok: Boolean -> if (!reported && ok) { reported = true; onResult(true) } }
        runCatching {
            val light = vehicle.getLight()
            light.getCabinLightSwitch({ current ->
                savedCabin = current
                light.setCabinLightSwitch(LightSwitch.OFF, { report(true) }, { Log.i(TAG, "cabin light: ${it.message}") })
            }, { Log.i(TAG, "cabin light read: ${it.message}") })
            light.checkReadingLightSwitchCapabilities({ _: CapabilityConfig, areas: Map<SeatArea, List<LightSwitch>> ->
                areas.keys.forEach { seat ->
                    light.getReadingLightSwitch(seat, { current ->
                        current?.let { savedReading[seat] = it }
                        light.setReadingLightSwitch(seat, LightSwitch.OFF, { report(true) }, {})
                    }, {})
                }
            }, { Log.i(TAG, "reading lights: ${it.message}") })
            light.checkAmbientLightSwitchCapabilities({ _, areas ->
                areas.keys.forEach { seat ->
                    light.getAmbientLightSwitch(seat, { current ->
                        current?.let { savedAmbient[seat] = it }
                        light.setAmbientLightSwitch(seat, LightSwitch.OFF, { _, _ -> report(true) }, {})
                    }, {})
                }
            }, { Log.i(TAG, "ambient lights: ${it.message}") })
        }.onFailure { Log.w(TAG, "lights unavailable", it) }
        // Without a reply within 2 s (no Light API, or no permission), tell the caller nothing changed.
        handler.postDelayed({ if (!reported) { reported = true; onResult(false) } }, 2000)
    }

    fun restoreLights() {
        runCatching {
            val light = vehicle.getLight()
            savedCabin?.let { light.setCabinLightSwitch(it, {}, {}) }
            savedReading.forEach { (seat, value) -> light.setReadingLightSwitch(seat, value, {}, {}) }
            savedAmbient.forEach { (seat, value) -> light.setAmbientLightSwitch(seat, value, { _, _ -> }, {}) }
        }
        savedCabin = null
        savedReading.clear()
        savedAmbient.clear()
    }
}

/** Hides the sky while driving: Pleos lets media and game apps run only when the car is parked. */
class DrivingGuard(private val context: Context, private val onRestricted: (Boolean) -> Unit) {

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        val car = runCatching { Car.createCar(context) }.getOrNull() ?: return
        this.car = car
        val manager = runCatching {
            car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        }.getOrNull() ?: return
        this.manager = manager
        manager.currentCarUxRestrictions?.let { onRestricted(it.isRequiresDistractionOptimization) }
        manager.registerListener { onRestricted(it.isRequiresDistractionOptimization) }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
    }
}
