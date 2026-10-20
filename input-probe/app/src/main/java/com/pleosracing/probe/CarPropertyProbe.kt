package com.pleosracing.probe

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/**
 * Reads AAOS standard vehicle properties through CarPropertyManager.
 *
 * Pleos Connect runs on Android 14 (API 34). The pedal compression properties only became public in
 * Android 15, so they are queried by raw ID: if the vehicle's VHAL does not implement them, or the
 * permission does not exist on this platform, getCarPropertyConfig() returns null.
 */
class CarPropertyProbe(private val context: Context, private val board: ProbeBoard) {

    private data class Prop(val key: String, val id: Int, val label: String, val format: (Any?) -> String = { "$it" })

    private val props = listOf(
        Prop("aaos.brakePct", BRAKE_PEDAL_COMPRESSION_PERCENTAGE, "브레이크 페달 압력 (%)", ::percent),
        Prop("aaos.accelPct", ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE, "액셀 페달 압력 (%)", ::percent),
        Prop("aaos.steer", VehiclePropertyIds.PERF_STEERING_ANGLE, "PERF_STEERING_ANGLE (°)"),
        Prop("aaos.gearSel", VehiclePropertyIds.GEAR_SELECTION, "GEAR_SELECTION", ::gear),
        Prop("aaos.gearCur", VehiclePropertyIds.CURRENT_GEAR, "CURRENT_GEAR", ::gear),
        Prop("aaos.parkingBrake", VehiclePropertyIds.PARKING_BRAKE_ON, "PARKING_BRAKE_ON"),
        Prop("aaos.speed", VehiclePropertyIds.PERF_VEHICLE_SPEED, "PERF_VEHICLE_SPEED (m/s)"),
        Prop("aaos.ignition", VehiclePropertyIds.IGNITION_STATE, "IGNITION_STATE", ::ignition),
        Prop("aaos.rpm", VehiclePropertyIds.ENGINE_RPM, "ENGINE_RPM"),
    )

    private val byId = props.associateBy { it.id }
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val prop = byId[value.propertyId] ?: return
            board.value(prop.key, prop.format(value.value))
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            val prop = byId[propertyId] ?: return
            board.detail(prop.key, "onErrorEvent area=$areaId")
        }
    }

    fun start() {
        props.forEach { board.define(it.key, SECTION, it.label) }
        board.define(UX, SECTION_UX, "주행 제한 (CarUxRestrictions)")

        val car = Car.createCar(context) ?: run {
            board.log("Car.createCar() returned null — not an AAOS device?")
            return
        }
        this.car = car
        propertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        props.forEach(::subscribe)
        dumpAccessibleProperties()
        startUxRestrictions(car)
    }

    /** Re-subscribe after runtime permissions were granted. */
    fun restart() {
        stop()
        start()
    }

    fun stop() {
        propertyManager?.unregisterCallback(callback)
        uxManager?.unregisterListener()
        car?.disconnect()
        car = null
        propertyManager = null
        uxManager = null
    }

    private fun subscribe(prop: Prop) {
        val manager = propertyManager ?: return
        val config: CarPropertyConfig<*>? = try {
            manager.getCarPropertyConfig(prop.id)
        } catch (e: Exception) {
            board.detail(prop.key, "config 오류: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (config == null) {
            board.detail(prop.key, "미지원 또는 권한 없음 (id=0x%08x)".format(prop.id))
            board.log("${prop.label}: not available (id=0x%08x)".format(prop.id))
            return
        }

        val continuous = config.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS
        val rate = if (continuous) config.maxSampleRate else CarPropertyManager.SENSOR_RATE_ONCHANGE
        board.detail(
            prop.key,
            "access=${access(config.access)}, mode=${changeMode(config.changeMode)}, " +
                "rate=${config.minSampleRate}..${config.maxSampleRate}Hz",
        )
        board.log("${prop.label}: supported, ${changeMode(config.changeMode)}, max ${config.maxSampleRate}Hz")

        try {
            manager.getProperty<Any>(prop.id, 0)?.let { board.value(prop.key, prop.format(it.value)) }
        } catch (e: Exception) {
            board.log("${prop.label}: getProperty failed: $e")
        }
        try {
            manager.registerCallback(callback, prop.id, rate)
        } catch (e: Exception) {
            board.detail(prop.key, "구독 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Lists every property this app can see, to spot vendor properties we did not know about. */
    private fun dumpAccessibleProperties() {
        val list = runCatching { propertyManager?.propertyList }.getOrNull() ?: return
        board.log("accessible properties: ${list.size}")
        list.sortedBy { it.propertyId }.forEach {
            val vendor = if (it.propertyId and VENDOR_MASK == VENDOR_MASK) " [vendor]" else ""
            board.log(
                "  0x%08x %s%s %s".format(
                    it.propertyId,
                    VehiclePropertyIds.toString(it.propertyId),
                    vendor,
                    changeMode(it.changeMode),
                ),
            )
        }
    }

    private fun startUxRestrictions(car: Car) {
        val manager = runCatching {
            car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        }.getOrNull() ?: return
        uxManager = manager
        manager.currentCarUxRestrictions?.let(::reportUx)
        manager.registerListener { reportUx(it) }
    }

    private fun reportUx(restrictions: CarUxRestrictions) {
        val text = if (restrictions.isRequiresDistractionOptimization) {
            "주행 제한 중 (active=0x%x)".format(restrictions.activeRestrictions)
        } else {
            "제한 없음 (정차)"
        }
        board.value(UX, text)
    }

    private fun percent(v: Any?) = (v as? Float)?.let { "%.1f%%".format(it) } ?: "$v"

    private fun gear(v: Any?) = when (v as? Int) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        else -> "$v"
    }

    private fun ignition(v: Any?) = when (v as? Int) {
        0 -> "UNDEFINED"
        1 -> "LOCK"
        2 -> "OFF"
        3 -> "ACC"
        4 -> "ON"
        5 -> "START"
        else -> "$v"
    }

    private fun access(a: Int) = when (a) {
        CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ -> "READ"
        CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE -> "WRITE"
        CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE -> "READ_WRITE"
        else -> "NONE"
    }

    private fun changeMode(m: Int) = when (m) {
        CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC -> "STATIC"
        CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE -> "ONCHANGE"
        CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS -> "CONTINUOUS"
        else -> "$m"
    }

    companion object {
        private const val SECTION = "AAOS CarPropertyManager"
        private const val SECTION_UX = "주행 상태"
        private const val UX = "aaos.ux"
        private const val VENDOR_MASK = 0x20000000

        // Public in Android 15 (API 35) VehiclePropertyIds; not in the API 34 SDK.
        const val ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE = 0x1160030f
        const val BRAKE_PEDAL_COMPRESSION_PERCENTAGE = 0x11600310
        const val PERMISSION_READ_CAR_PEDALS = "android.car.permission.READ_CAR_PEDALS"
    }
}
