// K340 firmware fork: crossfeed output pair map helpers (core patch 0002,
// firmware firmware/DSPi/crossfeed_map.h on branch k340-xfeed).
package se.bofr.dspi.bridge

/**
 * The crossfeed pair map as [DspiBridge.crossfeedPairMap] carries it: 4 bytes,
 * byte p = the outputs of pair p. 0 = the default pairing (2p, 2p+1); any
 * other value = `(right shl 4) or left`, 0-based output indices. The filter is
 * symmetric, so "left"/"right" only label the two sides. Validity is the
 * core's ([DspiBridge.crossfeedPairMapValid]).
 */
object CrossfeedPairs {
    const val MAP_BYTES = 4

    /** All pairs at their default outputs (what upstream firmware reports). */
    val DEFAULT: List<Int> = List(MAP_BYTES) { 0 }

    /**
     * One DAC per ear (the K340 amplifier): out1 = right low, out2 = right
     * high, out3 = left low, out4 = left high (0-based 0..3). Pair 0 = the
     * woofers (left out3, right out1), pair 1 = the tweeters (left out4,
     * right out2). Valid on RP2040 and RP2350; pairs 2/3 stay default.
     */
    val ONE_DAC_PER_EAR: List<Int> = listOf(encode(2, 0), encode(3, 1), 0, 0)

    /** Stereo pairs for a device with [numOutputChannels] outputs (the last is the mono PDM sub). */
    fun pairCount(numOutputChannels: Int): Int = ((numOutputChannels - 1).coerceAtLeast(0) / 2).coerceIn(1, MAP_BYTES)

    /** Outputs (left, right) of pair [p]. */
    fun pair(map: List<Int>, p: Int): Pair<Int, Int> {
        val b = map.getOrElse(p) { 0 } and 0xFF
        return if (b == 0) (2 * p) to (2 * p + 1) else (b and 0x0F) to (b shr 4)
    }

    /** The map byte for an explicit pair. */
    fun encode(left: Int, right: Int): Int = ((right and 0x0F) shl 4) or (left and 0x0F)

    /** A map from explicit (left, right) pairs, pair 0 first; missing pairs stay default. */
    fun of(vararg pairs: Pair<Int, Int>): List<Int> = List(MAP_BYTES) { p -> pairs.getOrNull(p)?.let { encode(it.first, it.second) } ?: 0 }

    /** Same pairing (as the firmware compares after normalizing explicit defaults to 0). */
    fun samePairing(a: List<Int>, b: List<Int>): Boolean = (0 until MAP_BYTES).all { pair(a, it) == pair(b, it) }
}
