package com.byeolmajung.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
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
 * Car features used in stargazing mode: cabin/ambient lights and the sunroof blind.
 * Every call checks the capability first; on cars without the feature (or without permission) it quietly does nothing.
 */
class CarFeatures(context: Context, private val onSunroofBlindChanged: (open: Boolean) -> Unit) {

    private val vehicle = Vehicle(context)
    private val roof = WindowArea(WindowValue.ROOF_TOP_1)
    private val ambientSeats = SeatArea(SeatValue.ROW_1_LEFT)

    private var savedCabin: LightSwitch? = null
    private var savedAmbient: Int? = null
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
        runCatching { vehicle.initialize() }.onFailure { Log.w(TAG, "Vehicle SDK init failed", it) }
        val window = runCatching { vehicle.getWindow() }.getOrNull() ?: return
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
        vehicle.getWindow().moveWindowBlind(roof, MoveControl.FORWARD, { _, _ -> onResult(true) }, {
            Log.w(TAG, "open blind failed", it)
            onResult(false)
        })
    }

    /** Turns the cabin light off and the ambient light to its minimum, remembering the previous state. */
    fun dimLights(onResult: (Boolean) -> Unit) {
        val light = runCatching { vehicle.getLight() }.getOrNull() ?: return onResult(false)
        var ok = false
        light.getCabinLightSwitch({ current ->
            savedCabin = current
            light.setCabinLightSwitch(LightSwitch.OFF, { ok = true }, { Log.i(TAG, "cabin light: ${it.message}") })
        }, { Log.i(TAG, "cabin light read: ${it.message}") })
        light.checkAmbientLightBrightnessCapabilities({ config, bounds ->
            val min = bounds[ambientSeats]?.minValue
            if (config.accessType == AccessType.READ_WRITE && min != null) {
                light.getAmbientLightBrightness(ambientSeats, { savedAmbient = it }, {})
                light.setAmbientLightBrightness(ambientSeats, min, { _, _ -> ok = true; onResult(true) }, { onResult(ok) })
            } else {
                onResult(ok)
            }
        }, { onResult(ok) })
    }

    fun restoreLights() {
        val light = runCatching { vehicle.getLight() }.getOrNull() ?: return
        savedCabin?.let { light.setCabinLightSwitch(it, {}, {}) }
        savedAmbient?.let { light.setAmbientLightBrightness(ambientSeats, it, { _, _ -> }, {}) }
        savedCabin = null
        savedAmbient = null
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
