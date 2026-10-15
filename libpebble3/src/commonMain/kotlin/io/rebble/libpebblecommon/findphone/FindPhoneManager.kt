package io.rebble.libpebblecommon.findphone

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.SystemMessage
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Plays [FindPhoneTone] loudly, even when the phone is silenced or its volume is low. */
interface PhoneRinger {
    /** Starts the tone, or restarts it if it's already playing. */
    fun ring()
}

class FindPhoneManager(
    private val watchScope: ConnectionCoroutineScope,
    private val protocolHandler: PebbleProtocolHandler,
    private val phoneRinger: PhoneRinger,
) {
    private val logger = Logger.withTag("FindPhoneManager")

    fun init() {
        protocolHandler.inboundMessages
            .filterIsInstance<SystemMessage.DrewFindPhone>()
            .onEach {
                logger.i { "Watch asked to find the phone" }
                phoneRinger.ring()
            }
            .launchIn(watchScope)
    }
}
