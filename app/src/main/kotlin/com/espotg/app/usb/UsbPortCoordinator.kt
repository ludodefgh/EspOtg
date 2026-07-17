package com.espotg.app.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PortOccupant { NONE, FLASHER, MONITOR }

/**
 * Arbitrates exclusive access to the (single) USB serial port between the
 * flasher and the serial monitor - flashing and monitoring can't both hold the
 * port at once. Concept mirrors what the SerialFlow competitor app does
 * (PortCoordinator/PortOccupant), reimplemented independently here - see
 * CLAUDE.md. App-scoped singleton, deliberately not DI-managed since nothing
 * else in this app needs a dependency graph yet.
 */
object UsbPortCoordinator {
    private val _occupant = MutableStateFlow(PortOccupant.NONE)
    val occupant: StateFlow<PortOccupant> = _occupant

    @Synchronized
    fun tryAcquire(who: PortOccupant): Boolean {
        if (_occupant.value != PortOccupant.NONE) {
            return false
        }
        _occupant.value = who
        return true
    }

    @Synchronized
    fun release(who: PortOccupant) {
        if (_occupant.value == who) {
            _occupant.value = PortOccupant.NONE
        }
    }
}
