// The control-surfaces model the Control pages render: the core's cs_snapshot
// JSON (DSPi-Console-Linux core/src/cs.rs @ 4683a2c) parsed into records, with
// the macOS Console's names (DSPi-Console-b5 DSPi_ConsoleApp.swift
// ControlSurfacesSettingsTab, as collected in Linux qt/qml/settings/cs/CsHelper.qml).
package se.bofr.dspi.feature.settings

import se.bofr.dspi.ui.json.Json
import se.bofr.dspi.ui.json.bool
import se.bofr.dspi.ui.json.int
import se.bofr.dspi.ui.json.list
import se.bofr.dspi.ui.json.long
import se.bofr.dspi.ui.json.obj
import se.bofr.dspi.ui.json.str

/** One binding slot (24-byte firmware record). */
data class CsBinding(
    val type: Int = 0,
    val noun: Int = 0,
    val action: Int = 0,
    val flags: Int = 0,
    val gpio0: Int = 0,
    val gpio1: Int = 0xFF,
    val event: Int = 0,
    val target: Int = 0,
    val index: Int = 0,
    val baseBright: Int = 0,
    val value: Int = 0,
    val step: Int = 0,
    val rangeMin: Int = 0,
    val rangeMax: Int = 0,
    val onDelay: Int = 0,
    val offDelay: Int = 0,
    val extras: Int = 0
) {
    val configured: Boolean get() = type != CsModel.TYPE_NONE

    fun json(): Map<String, Any?> = linkedMapOf(
        "type" to type, "noun" to noun, "action" to action, "flags" to flags, "gpio0" to gpio0, "gpio1" to gpio1,
        "event" to event, "target" to target, "index" to index, "baseBright" to baseBright, "value" to value, "step" to step,
        "rangeMin" to rangeMin, "rangeMax" to rangeMax, "onDelay" to onDelay, "offDelay" to offDelay, "extras" to extras
    )

    companion object {
        fun parse(m: Map<String, Any?>) = CsBinding(
            type = m.int("type") ?: 0, noun = m.int("noun") ?: 0, action = m.int("action") ?: 0, flags = m.int("flags") ?: 0,
            gpio0 = m.int("gpio0") ?: 0, gpio1 = m.int("gpio1") ?: 0xFF, event = m.int("event") ?: 0, target = m.int("target") ?: 0,
            index = m.int("index") ?: 0, baseBright = m.int("baseBright") ?: 0, value = m.int("value") ?: 0, step = m.int("step") ?: 0,
            rangeMin = m.int("rangeMin") ?: 0, rangeMax = m.int("rangeMax") ?: 0, onDelay = m.int("onDelay") ?: 0,
            offDelay = m.int("offDelay") ?: 0, extras = m.int("extras") ?: 0
        )
    }
}

data class CsGroup(val targetKind: Int, val memberMask: Long, val name: String) {
    val configured: Boolean get() = targetKind != 0 && memberMask != 0L
}

data class CsMacroStep(val noun: Int, val action: Int, val target: Int, val index: Int, val value: Int, val preDelay: Int)

data class CsMacro(val name: String, val steps: List<CsMacroStep>) {
    val configured: Boolean get() = steps.isNotEmpty() || name.isNotEmpty()
}

data class CsIrCommand(val noun: Int, val action: Int, val protocol: Int, val code: Long) {
    val configured: Boolean get() = protocol != 0
}

/** The parsed `cs_snapshot` (unsupported or not yet loaded: [supported] false). */
data class CsModel(
    val supported: Boolean = false,
    val capsVersion: Int = 0,
    val typeCount: Int = 0,
    val bindings: List<CsBinding> = emptyList(),
    val names: List<String> = emptyList(),
    val ir: List<CsIrCommand> = emptyList(),
    val groups: List<CsGroup> = emptyList(),
    val macros: List<CsMacro> = emptyList(),
    val dirty: Boolean = false,
    val activeMask: Int = 0,
    val slotStatus: List<Int> = emptyList(),
    val macroRunning: Int = 0xFF,
    val auxState: List<Int> = emptyList(),
    val auxLevel: List<Int> = emptyList()
) {
    val groupsSupported: Boolean get() = supported && groups.isNotEmpty()
    val macrosSupported: Boolean get() = supported && macros.isNotEmpty()
    val auxSupported: Boolean get() = supported && typeCount > TYPE_AUX_PWM
    val displaySupported: Boolean get() = supported && typeCount > TYPE_DISPLAY

    fun isSlotActive(slot: Int): Boolean = (activeMask shr slot) and 1 == 1

    fun slotName(slot: Int): String = names.getOrNull(slot)?.takeIf { it.isNotEmpty() } ?: ""

    fun auxName(slot: Int): String = slotName(slot).ifEmpty { "Aux ${slot + 1}" }

    fun groupName(g: Int): String = groups.getOrNull(g)?.name?.takeIf { it.isNotEmpty() } ?: "Group ${g + 1}"

    fun macroName(i: Int): String = macros.getOrNull(i)?.name?.takeIf { it.isNotEmpty() } ?: "Macro ${i + 1}"

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_BUTTON = 1
        const val TYPE_SWITCH = 2
        const val TYPE_POT = 3
        const val TYPE_ENCODER = 4
        const val TYPE_LED = 5
        const val TYPE_LED_PWM = 6
        const val TYPE_IR = 7
        const val TYPE_DISPLAY = 8
        const val TYPE_AUX_OUT = 9
        const val TYPE_AUX_PWM = 10

        const val NOUN_CLIP = 8
        const val NOUN_DAC_MUTE_TEST = 26
        const val NOUN_PRESET_RELOAD = 48
        const val NOUN_MACRO = 52
        const val NOUN_PAGE_VALUE = 56

        fun isAuxType(t: Int) = t == TYPE_AUX_OUT || t == TYPE_AUX_PWM

        fun isIndicatorType(t: Int) = t == TYPE_LED || t == TYPE_LED_PWM

        /** Parse the controller's model JSON; "" or garbage gives an unsupported model. */
        fun parse(json: String): CsModel {
            if (json.isBlank()) return CsModel()
            val m = Json.parseObject(json) ?: return CsModel()
            if (m.bool("supported") != true) return CsModel()
            val status = m.obj("status") ?: emptyMap()
            val ext = m.obj("ext") ?: emptyMap()
            fun objs(k: String) = m.list(k).orEmpty().map {
                (it as? Map<*, *>)?.let { x ->
                    @Suppress("UNCHECKED_CAST")
                    (x as Map<String, Any?>)
                }
                    ?: emptyMap()
            }
            fun ints(l: List<Any?>?) = l.orEmpty().map { (it as? Number)?.toInt() ?: 0 }
            return CsModel(
                supported = true,
                capsVersion = m.int("capsVersion") ?: 0,
                typeCount = m.list("types")?.size ?: 0,
                bindings = objs("bindings").map { CsBinding.parse(it) },
                names = m.list("names").orEmpty().map { it as? String ?: "" },
                ir = objs("ir").map {
                    CsIrCommand(it.int("noun") ?: 0, it.int("action") ?: 0, it.int("protocol") ?: 0, it.long("code") ?: 0)
                },
                groups = objs("groups").map { CsGroup(it.int("targetKind") ?: 0, it.long("memberMask") ?: 0, it.str("name") ?: "") },
                macros = objs("macros").map { mc ->
                    @Suppress("UNCHECKED_CAST")
                    val steps = mc.list("steps").orEmpty().mapNotNull { it as? Map<String, Any?> }.map {
                        CsMacroStep(
                            it.int("noun") ?: 0,
                            it.int("action") ?: 0,
                            it.int("target") ?: 0,
                            it.int("index") ?: 0,
                            it.int("value") ?: 0,
                            it.int("preDelay") ?: 0
                        )
                    }
                    CsMacro(mc.str("name") ?: "", steps)
                },
                dirty = status.bool("dirty") ?: false,
                activeMask = status.int("activeMask") ?: 0,
                slotStatus = ints(status.list("slotStatus")),
                macroRunning = ext.int("macroRunning") ?: 0xFF,
                auxState = ints(m.list("auxState")),
                auxLevel = ints(m.list("auxLevel"))
            )
        }

        fun typeName(t: Int): String = when (t) {
            TYPE_NONE -> "None"
            TYPE_BUTTON -> "Push Button"
            TYPE_SWITCH -> "Toggle Switch"
            TYPE_POT -> "Potentiometer / Fader"
            TYPE_ENCODER -> "Rotary Encoder"
            TYPE_LED -> "Indicator LED"
            TYPE_LED_PWM -> "Dimmable LED"
            TYPE_IR -> "IR Remote"
            TYPE_DISPLAY -> "Display"
            TYPE_AUX_OUT -> "On/Off Output"
            TYPE_AUX_PWM -> "Dimmable Output"
            else -> "Type $t"
        }

        private val NOUN_NAMES = mapOf(
            0 to "Volume", 1 to "Master Volume", 2 to "Mute", 3 to "Loudness", 4 to "Crossfeed", 5 to "Volume Leveller",
            6 to "Preset", 7 to "Input Source", 8 to "Clipping", 9 to "EQ Bypass", 10 to "LG Sound Sync",
            11 to "Crossfeed Preset", 12 to "Crossfeed ITD", 13 to "Leveller Amount", 14 to "Leveller Speed",
            15 to "Leveller Lookahead", 16 to "Input Preamp", 17 to "Output Gain", 18 to "Output Mute", 19 to "Output Enable",
            20 to "Filter Frequency", 21 to "Filter Gain", 22 to "Filter Q", 23 to "Filter Type", 24 to "Filter Bypass",
            25 to "Signal Generator", 26 to "DAC Mute Test", 27 to "Channel Clipping", 28 to "Channel Level",
            29 to "S/PDIF Lock", 30 to "Sample Rate", 31 to "USB Streaming", 32 to "ADAT Active", 33 to "LG Source Present",
            34 to "LG Muted", 35 to "Upmixer", 36 to "Upmixer Centre Mode", 37 to "Upmixer Surround Mode",
            38 to "Upmixer Strength", 39 to "Upmixer Width", 40 to "Upmixer Presence", 41 to "Psychoacoustic Bass",
            42 to "Psych Bass Cutoff Frequency", 43 to "Psych Bass Harmonics", 44 to "Psych Bass Drive",
            45 to "Psych Bass Character", 46 to "Psych Bass Original Level", 47 to "Output Delay", 48 to "Preset Reload",
            49 to "Loudness Reference SPL", 50 to "Loudness Intensity", 51 to "Input Signal Level", 52 to "Macro",
            53 to "CPU Load", 54 to "Show Page", 55 to "Allow Editing", 56 to "Browse/Adjust",
            57 to "Subharmonic Synthesizer", 58 to "Subharm 24-36 Hz Level", 59 to "Subharm 36-56 Hz Level",
            60 to "Subharm LF Boost", 61 to "Subharm 56-80 Hz Level", 62 to "Subharm Selectivity",
            63 to "Subharm Selectivity Depth", 64 to "Subharm Selectivity Hold", 65 to "Subharm Sub Ceiling",
            66 to "Subharm Pair Link", 67 to "Subharm Solo", 68 to "Aux Switch", 69 to "Aux Level",
            70 to "Tube Modeller", 71 to "Tube Drive", 72 to "Tube Type", 73 to "Tube Mix",
            74 to "Output Limiter", 75 to "Limiter Threshold", 76 to "Limiter Release", 77 to "Limiter Link Group",
            78 to "Limiter Gain Reduction"
        )

        /** A few read differently on a control than on an LED. */
        fun nounName(n: Int, type: Int): String {
            if (!isIndicatorType(type)) {
                when (n) {
                    NOUN_CLIP -> return "Clear Clipping"
                    NOUN_DAC_MUTE_TEST -> return "Test DAC Mute"
                    NOUN_PRESET_RELOAD -> return "Reload Preset"
                }
            }
            if (n == NOUN_MACRO) return if (isIndicatorType(type)) "Running Macro" else "Macro"
            return NOUN_NAMES[n] ?: "Parameter $n"
        }

        fun actionName(a: Int, noun: Int): String {
            if (noun == NOUN_PAGE_VALUE) {
                if (a == 2) return "Up"
                if (a == 3) return "Down"
            }
            return when (a) {
                0 -> "Adjust"
                1 -> "Step"
                2 -> "Increase"
                3 -> "Decrease"
                4 -> "Toggle"
                5 -> "Set value"
                6 -> "Follow position"
                7 -> "Trigger"
                8 -> "Indicate"
                9 -> "Hold"
                10 -> "Indicate above"
                11 -> "Show level"
                else -> "Action $a"
            }
        }

        // ── Operations (CsController.apply / sendOnly JSON) ──

        fun nameOp(slot: Int, name: String) = Json.write(linkedMapOf("op" to "name", "index" to slot, "name" to name))

        /** An all-zero binding of type None frees the slot. */
        fun clearBindingOp(slot: Int) = Json.write(linkedMapOf("op" to "binding", "index" to slot, "binding" to CsBinding().json()))

        fun auxStateOp(slot: Int, on: Boolean) = Json.write(linkedMapOf("op" to "auxState", "index" to slot, "on" to if (on) 1 else 0))

        fun macroFireOp(index: Int) = Json.write(linkedMapOf("op" to "macroFire", "index" to index))
    }
}
