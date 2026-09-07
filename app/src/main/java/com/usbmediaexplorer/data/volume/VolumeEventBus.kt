package com.usbmediaexplorer.data.volume

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for storage events. Manifest receivers (which may be the only thing running
 * when a stick is plugged in) publish here and the repository reacts.
 */
object VolumeEventBus {

    private val _events = MutableSharedFlow<VolumeEvent>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<VolumeEvent> = _events.asSharedFlow()

    fun publish(event: VolumeEvent) {
        _events.tryEmit(event)
    }
}
