package se.bofr.dspi.bridge

/**
 * App channel ids <-> firmware wire channels (DSPiBridge.cpp wire/appId/
 * outputOf/inputOf/inputAppId; same scheme as Windows `Core/Models/ChannelMap.cs`):
 *
 *     0, 1    inputs 1-2 (USB L/R)
 *     2..10   outputs 1-9
 *     11..16  inputs 3-8 (RP2350 multichannel inputs)
 *
 * The UI speaks app ids only; the core speaks wire indices
 * (`[inputs 0..numIn-1][outputs numIn..]`). Every function returns -1 when the
 * channel does not exist on the connected device.
 */
data class ChannelMap(val numInputChannels: Int, val numOutputChannels: Int) {
    val numChannels: Int get() = numInputChannels + numOutputChannels

    /** App id -> wire channel. */
    fun wire(appCh: Int): Int {
        if (appCh < 0 || appCh >= APP_CHANNEL_COUNT) return -1
        if (appCh < 2) return if (appCh < numInputChannels) appCh else -1
        if (appCh >= APP_EXTRA_INPUT_FIRST) {
            val input = 2 + (appCh - APP_EXTRA_INPUT_FIRST)
            return if (input < numInputChannels) input else -1
        }
        val output = appCh - 2
        return if (output < numOutputChannels) numInputChannels + output else -1
    }

    /** Wire channel -> app id. */
    fun appId(wireCh: Int): Int {
        if (wireCh < 0 || wireCh >= numChannels) return -1
        if (wireCh < numInputChannels) return if (wireCh < 2) wireCh else APP_EXTRA_INPUT_FIRST + (wireCh - 2)
        return 2 + (wireCh - numInputChannels)
    }

    /** Output index of an output app id, else -1. */
    fun outputOf(appCh: Int): Int {
        if (appCh < 2 || appCh >= APP_EXTRA_INPUT_FIRST) return -1
        val output = appCh - 2
        return if (output < numOutputChannels) output else -1
    }

    /** Input index of an input app id, else -1. */
    fun inputOf(appCh: Int): Int {
        val input =
            when {
                appCh in 0..1 -> appCh
                appCh in APP_EXTRA_INPUT_FIRST until APP_CHANNEL_COUNT -> 2 + (appCh - APP_EXTRA_INPUT_FIRST)
                else -> -1
            }
        return if (input in 0 until numInputChannels) input else -1
    }

    /** Input index -> app id. */
    fun inputAppId(input: Int): Int {
        if (input < 0 || input >= numInputChannels) return -1
        return if (input < 2) input else APP_EXTRA_INPUT_FIRST + (input - 2)
    }

    fun isInputChannel(appCh: Int): Boolean = inputOf(appCh) >= 0

    fun channelExists(appCh: Int): Boolean = wire(appCh) >= 0

    companion object {
        const val APP_CHANNEL_COUNT = 17
        const val APP_EXTRA_INPUT_FIRST = 11
    }
}
