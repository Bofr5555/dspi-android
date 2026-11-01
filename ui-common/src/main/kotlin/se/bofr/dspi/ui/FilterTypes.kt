// Port of DSPi-Console-b5:DSPi Console/DSPMath.swift (FilterType,
// CrossoverFamily, FilterParams.retyped) and Components.swift
// (FilterRowView.peqMenuGroups, CrossoverFamilyOption) @ b5fdeac.
// Pure presentation logic over the firmware type byte; JVM-tested.
package se.bofr.dspi.ui

import kotlin.math.log10

/**
 * Firmware filter types (`FILTER_*`; raw values are the wire type byte).
 * 0..7 core PEQ, 8..10 first-order PEQ, 11 Linkwitz Transform, 12..13
 * first-order pass, 32..63 crossover (family, order, LP/HP).
 */
enum class FilterType(val raw: Int) {
    FLAT(0),
    PEAKING(1),
    LOW_SHELF(2),
    HIGH_SHELF(3),
    LOW_PASS(4),
    HIGH_PASS(5),
    NOTCH(6),
    ALL_PASS(7),
    ALL_PASS1(8),
    LOW_SHELF1(9),
    HIGH_SHELF1(10),
    LINKWITZ_TRANSFORM(11),
    LOW_PASS1(12),
    HIGH_PASS1(13),
    LR2_LP(32),
    LR2_HP(33),
    LR4_LP(34),
    LR4_HP(35),
    LR6_LP(36),
    LR6_HP(37),
    LR8_LP(38),
    LR8_HP(39),
    BW1_LP(40),
    BW1_HP(41),
    BW2_LP(42),
    BW2_HP(43),
    BW3_LP(44),
    BW3_HP(45),
    BW4_LP(46),
    BW4_HP(47),
    BW5_LP(48),
    BW5_HP(49),
    BW6_LP(50),
    BW6_HP(51),
    BW7_LP(52),
    BW7_HP(53),
    BW8_LP(54),
    BW8_HP(55),
    BES2_LP(56),
    BES2_HP(57),
    BES4_LP(58),
    BES4_HP(59),
    BES6_LP(60),
    BES6_HP(61),
    BES8_LP(62),
    BES8_HP(63)
    ;

    /** LR / BW / BES crossover type (firmware FILTER_XOVER_FIRST..LAST). */
    val isCrossover: Boolean get() = raw in 32..63

    /** First-order PEQ sections (Q ignored by the firmware). */
    val isFirstOrderPeq: Boolean get() = this == ALL_PASS1 ||
        this == LOW_SHELF1 ||
        this == HIGH_SHELF1 ||
        this == LOW_PASS1 ||
        this == HIGH_PASS1

    val isLinkwitzTransform: Boolean get() = this == LINKWITZ_TRANSFORM

    /** Submenu child title for shapes that come in two orders; null otherwise. */
    val orderLabel: String?
        get() =
            when (this) {
                LOW_SHELF1, HIGH_SHELF1, LOW_PASS1, HIGH_PASS1 -> "6 dB/oct"
                LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS -> "12 dB/oct"
                ALL_PASS1 -> "180°"
                ALL_PASS -> "360°"
                else -> null
            }

    /** The full name shown on the type picker's face. */
    val displayName: String
        get() =
            when (this) {
                FLAT -> "Off"
                PEAKING -> "Peaking"
                LOW_SHELF -> "Low Shelf (12dB)"
                HIGH_SHELF -> "High Shelf (12dB)"
                LOW_PASS -> "High Cut (12dB)"
                HIGH_PASS -> "Low Cut (12dB)"
                NOTCH -> "Notch"
                ALL_PASS -> "All Pass (360°)"
                ALL_PASS1 -> "All Pass (180°)"
                LOW_SHELF1 -> "Low Shelf (6dB)"
                HIGH_SHELF1 -> "High Shelf (6dB)"
                LOW_PASS1 -> "High Cut (6dB)"
                HIGH_PASS1 -> "Low Cut (6dB)"
                LINKWITZ_TRANSFORM -> "Linkwitz Transform"
                else -> crossoverName()
            }

    private fun crossoverName(): String {
        val family = crossoverFamily ?: return name
        val lp = if (crossoverIsLowPass == true) "Low Pass" else "High Pass"
        return "${family.shortName}$crossoverOrder $lp"
    }

    /** Compact label ("PK", "LR4 LP"). */
    val shortLabel: String
        get() =
            when (this) {
                FLAT -> "—"
                PEAKING -> "PK"
                LOW_SHELF -> "LS"
                HIGH_SHELF -> "HS"
                LOW_PASS -> "HC"
                HIGH_PASS -> "LC"
                NOTCH -> "NT"
                ALL_PASS -> "AP"
                ALL_PASS1 -> "AP1"
                LOW_SHELF1 -> "LS1"
                HIGH_SHELF1 -> "HS1"
                LOW_PASS1 -> "HC1"
                HIGH_PASS1 -> "LC1"
                LINKWITZ_TRANSFORM -> "LT"
                else -> "${crossoverFamily!!.shortName}$crossoverOrder ${if (crossoverIsLowPass == true) "LP" else "HP"}"
            }

    /** DashboardRow's two/three-letter type code. */
    val dashboardCode: String
        get() =
            when (this) {
                FLAT -> "OFF"
                PEAKING -> "PK"
                LOW_SHELF -> "LS"
                HIGH_SHELF -> "HS"
                LOW_PASS -> "HC"
                HIGH_PASS -> "LC"
                NOTCH -> "NO"
                ALL_PASS -> "AP"
                else -> shortLabel
            }

    val crossoverFamily: CrossoverFamily?
        get() =
            when (raw) {
                in 32..39 -> CrossoverFamily.LINKWITZ_RILEY
                in 40..55 -> CrossoverFamily.BUTTERWORTH
                in 56..63 -> CrossoverFamily.BESSEL
                else -> null
            }

    /** true for low-pass crossover types, null for non-crossover. */
    val crossoverIsLowPass: Boolean? get() = if (isCrossover) raw % 2 == 0 else null

    /** Order (1..8) of a crossover type, 0 otherwise. */
    val crossoverOrder: Int
        get() =
            when (raw) {
                in 32..39 -> 2 * ((raw - 32) / 2 + 1)
                in 40..55 -> (raw - 40) / 2 + 1
                in 56..63 -> 2 * ((raw - 56) / 2 + 1)
                else -> 0
            }

    /** `gain` carries a user-editable dB gain (not the LT's fp). */
    val usesGain: Boolean get() = this == PEAKING || this == LOW_SHELF || this == HIGH_SHELF || this == LOW_SHELF1 || this == HIGH_SHELF1

    /** Q is user-editable (not for first-order, crossovers, LT, Off). */
    val usesQ: Boolean
        get() =
            when (this) {
                FLAT, LINKWITZ_TRANSFORM -> false
                else -> !(isCrossover || isFirstOrderPeq)
            }

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** The type for a wire byte; [FLAT] for an unknown value. */
        fun of(raw: Int): FilterType = byRaw[raw] ?: FLAT

        /** PEQ types (everything but the crossovers), in picker order. */
        val peqTypes: List<FilterType> = entries.filter { !it.isCrossover }

        /** The XO tab's picker: Off plus every crossover type. */
        val crossoverTypes: List<FilterType> = listOf(FLAT) + entries.filter { it.isCrossover }

        /**
         * `availableFilterTypes` (ContentView.swift). The core only talks to
         * firmware 1.1.6+, where every PEQ type exists, so the capability
         * gates are all true; [includeLinkwitz] is false for input channels.
         */
        fun availableFilterTypes(includeLinkwitz: Boolean = true): List<FilterType> =
            peqTypes.filter { includeLinkwitz || it != LINKWITZ_TRANSFORM }
    }
}

/** Crossover filter families. */
enum class CrossoverFamily(val displayName: String, val shortName: String, val availableOrders: List<Int>, val description: String) {
    LINKWITZ_RILEY("Linkwitz-Riley", "LR", listOf(2, 4, 6, 8), "Allpass sum at fc. The de-facto crossover standard."),
    BUTTERWORTH("Butterworth", "BW", (1..8).toList(), "Maximally flat passband. Standard analog filter."),
    BESSEL("Bessel", "BES", listOf(2, 4, 6, 8), "Approximately linear phase. Smooth group delay.");

    /** The concrete type for an order + direction, null when the family lacks that order. */
    fun filterType(order: Int, lowPass: Boolean): FilterType? {
        if (order !in availableOrders) return null
        val base =
            when (this) {
                LINKWITZ_RILEY -> 32 + (order / 2 - 1) * 2
                BUTTERWORTH -> 40 + (order - 1) * 2
                BESSEL -> 56 + (order / 2 - 1) * 2
            }
        return FilterType.of(base + if (lowPass) 0 else 1)
    }
}

/** A shape in the PEQ type menu and its order variants (gentler first). */
data class PeqMenuGroup(val name: String, val variants: List<FilterType>) {
    companion object {
        /** FilterRowView.peqMenuGroups. */
        val all: List<PeqMenuGroup> =
            listOf(
                PeqMenuGroup("Off", listOf(FilterType.FLAT)),
                PeqMenuGroup("Peaking", listOf(FilterType.PEAKING)),
                PeqMenuGroup("Low Shelf", listOf(FilterType.LOW_SHELF1, FilterType.LOW_SHELF)),
                PeqMenuGroup("High Shelf", listOf(FilterType.HIGH_SHELF1, FilterType.HIGH_SHELF)),
                PeqMenuGroup("High Cut", listOf(FilterType.LOW_PASS1, FilterType.LOW_PASS)),
                PeqMenuGroup("Low Cut", listOf(FilterType.HIGH_PASS1, FilterType.HIGH_PASS)),
                PeqMenuGroup("Notch", listOf(FilterType.NOTCH)),
                PeqMenuGroup("All Pass", listOf(FilterType.ALL_PASS1, FilterType.ALL_PASS)),
                PeqMenuGroup("Linkwitz Transform", listOf(FilterType.LINKWITZ_TRANSFORM))
            )
    }
}

/** The XO tab's TYPE option: a crossover family or Off (`CrossoverFamilyOption`). */
data class CrossoverFamilyOption(val family: CrossoverFamily?) {
    val label: String get() = family?.displayName ?: "Off"

    companion object {
        val OFF = CrossoverFamilyOption(null)

        fun from(type: FilterType): CrossoverFamilyOption = CrossoverFamilyOption(type.crossoverFamily)

        /** Off plus the families present in [availableTypes], in order of first appearance. */
        fun available(availableTypes: List<FilterType>): List<CrossoverFamilyOption> =
            (listOf(OFF) + availableTypes.map { from(it) }).distinct()
    }
}

/**
 * A band's editable fields, independent of the core's FilterParams so this
 * module stays free of the bridge (`gain` carries fp in Hz for the Linkwitz
 * Transform; [qp] is its target Q).
 */
data class BandFields(val type: FilterType, val freq: Float, val q: Float, val gain: Float, val qp: Float, val bypass: Boolean = false) {
    /**
     * `FilterParams.retyped(to:)`: change the shape, rewriting the shared
     * fields the new type would read in the wrong unit. Entering the Linkwitz
     * Transform seeds a neutral transform (fp = f0, Qp = Q0, f0/Q0 clamped);
     * leaving it drops fp back to 0 dB.
     */
    fun retyped(newType: FilterType): BandFields {
        var p = copy(type = newType)
        if (newType.isLinkwitzTransform && !type.isLinkwitzTransform) {
            val f = maxOf(freq, MIN_FREQ)
            val qq = q.coerceIn(Q_MIN, Q_MAX)
            p = p.copy(freq = f, q = qq, gain = f, qp = qq)
        } else if (!newType.isLinkwitzTransform && type.isLinkwitzTransform) {
            p = p.copy(gain = 0f)
        }
        return p
    }

    /** Implied DC boost of a Linkwitz Transform, `40 × log10(f0/fp)` dB (peq_filters.md §3.3). */
    val linkwitzDcBoostDb: Float get() = if (gain > 0f && freq > 0f) 40f * log10(freq / gain) else 0f

    /**
     * True when this LT draft differs from [applied] beyond what the round trip
     * quantises (gain/fp to 0.001, qp to 1/512).
     */
    fun linkwitzDiffers(applied: BandFields): Boolean = kotlin.math.abs(freq - applied.freq) >= 0.05f ||
        kotlin.math.abs(q - applied.q) >= 0.0005f ||
        kotlin.math.abs(gain - applied.gain) >= 0.05f ||
        kotlin.math.abs(qp - applied.qp) >= 1f / 512f

    /** XO row: switch family, keeping the order if valid (else the closest) and the direction. */
    fun withFamily(option: CrossoverFamilyOption): BandFields? {
        val family = option.family ?: return copy(type = FilterType.FLAT)
        val orders = family.availableOrders
        val current = type.crossoverOrder
        val order = if (current in orders) current else orders.minByOrNull { kotlin.math.abs(it - current) } ?: 2
        val newType = family.filterType(order, type.crossoverIsLowPass ?: true) ?: return null
        return copy(type = newType)
    }

    /** XO row: switch LP/HP within the family. */
    fun withLowPass(lowPass: Boolean): BandFields? {
        val family = type.crossoverFamily ?: return null
        return family.filterType(type.crossoverOrder, lowPass)?.let { copy(type = it) }
    }

    /** XO row: switch the order within the family. */
    fun withOrder(order: Int): BandFields? {
        val family = type.crossoverFamily ?: return null
        return family.filterType(order, type.crossoverIsLowPass ?: true)?.let { copy(type = it) }
    }

    companion object {
        /** Lowest frequency any band field accepts (`FilterParams.minFreq`). */
        const val MIN_FREQ = 10f
        const val Q_MIN = 0.1f
        const val Q_MAX = 20f
    }
}
