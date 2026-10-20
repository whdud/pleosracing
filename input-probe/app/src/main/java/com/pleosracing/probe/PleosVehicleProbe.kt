package com.pleosracing.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context

/**
 * Reads game inputs through the Pleos Vehicle SDK (permission: pleos.car.permission.CAR_DRIVE).
 *
 * - Steering: front-wheel steering angle in degrees, left is negative (NOT the steering wheel rotation).
 * - Brake: pedal pressed / released only (Boolean).
 *
 * Gear is read from AAOS GEAR_SELECTION instead: the SDK deprecates its Gear API in favor of it.
 */
class PleosVehicleProbe(context: Context, private val board: ProbeBoard) {

    private val vehicle = Vehicle(context)

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            board.value(STEER, angle?.let { "%.2f°".format(it) } ?: "null")
        }

        override fun onFailed(e: Exception) = fail(STEER, "listener", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            board.value(BRAKE, pressed.toPressedText())
        }

        override fun onFailed(e: Exception) = fail(BRAKE, "listener", e)
    }

    fun start() {
        board.define(STEER, SECTION, "조향각 (앞바퀴, °)")
        board.define(BRAKE, SECTION, "브레이크 페달 (Boolean)")

        vehicle.initialize()
        board.log("Pleos Vehicle SDK initialize()")

        val steeringWheel = vehicle.getSteeringWheel()
        steeringWheel.checkSteeringWheelAngleCapabilities(
            onComplete = { config, bounds ->
                board.detail(STEER, "access=${config.accessType}, range=${bounds.minValue}..${bounds.maxValue}")
                board.log("steering capability: ${config.accessType} ${bounds.minValue}..${bounds.maxValue}")
            },
            onFailed = { fail(STEER, "capability", it) },
        )
        steeringWheel.getCurrentSteeringWheelAngle(
            onComplete = { board.value(STEER, it?.let { a -> "%.2f°".format(a) } ?: "null") },
            onFailed = { fail(STEER, "get", it) },
        )
        steeringWheel.registerSteeringWheelAngle(steeringListener)

        val brake = vehicle.getBrake()
        brake.checkBrakePedalStatusCapabilities(
            onComplete = { config ->
                board.detail(BRAKE, "access=${config.accessType}")
                board.log("brake capability: ${config.accessType}")
            },
            onFailed = { fail(BRAKE, "capability", it) },
        )
        brake.getCurrentBrakePedalStatus(
            onComplete = { board.value(BRAKE, it.toPressedText()) },
            onFailed = { fail(BRAKE, "get", it) },
        )
        brake.registerBrakePedalStatus(brakeListener)
    }

    fun stop() {
        runCatching {
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
        }
        vehicle.release()
    }

    private fun fail(key: String, step: String, e: Exception) {
        board.detail(key, "$step 실패: ${e.javaClass.simpleName}: ${e.message}")
        board.log("[$key] $step failed: $e")
    }

    private fun Boolean?.toPressedText() = when (this) {
        true -> "밟음"
        false -> "뗌"
        null -> "null"
    }

    companion object {
        private const val SECTION = "Pleos Vehicle SDK"
        private const val STEER = "pleos.steer"
        private const val BRAKE = "pleos.brake"
    }
}
