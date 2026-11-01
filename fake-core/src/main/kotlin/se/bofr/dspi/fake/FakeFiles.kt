// Stand-ins for the core's presetfile.rs / filterfile.rs (DSPi-Console-Linux
// @ 4683a2c) in FakeDspiCore: the same entry points and result JSON shapes,
// with a simplified document format. Good enough for UI tests and demo mode;
// the real formats come from the core.
package se.bofr.dspi.fake

import java.time.Instant
import java.util.Locale
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.FilterParams

internal object FakeFiles {
    private const val FORMAT = "dspipreset-fake"

    private fun platform(s: DspState) = if (s.platformId == 1) "RP2350" else "RP2040"

    private fun firmware(s: DspState) = "${s.fwMajor}.${s.fwMinor}.${s.fwPatch}" + if (s.fwBeta != 0) "-beta${s.fwBeta}" else ""

    fun exportConfig(s: DspState, name: String, appVersion: String, links: Int): String {
        val body = DspStateJson.toMap(s).toMutableMap()
        body.remove("bulkRaw")
        return MiniJson.write(
            linkedMapOf(
                "format" to FORMAT,
                "name" to name,
                "platform" to platform(s),
                "firmware" to firmware(s),
                "appVersion" to appVersion,
                "savedUtc" to Instant.ofEpochMilli(0).toString(),
                "links" to links,
                "state" to body
            )
        )
    }

    fun inspectConfig(text: String): String {
        val m = MiniJson.parseObject(text)
        if (m == null ||
            m.str("format") != FORMAT
        ) {
            return MiniJson.write(mapOf("ok" to false, "error" to "This file isn't a DSPi configuration."))
        }
        return MiniJson.write(
            linkedMapOf(
                "ok" to true,
                "error" to "",
                "name" to (m.str("name") ?: ""),
                "platform" to (m.str("platform") ?: ""),
                "firmware" to (m.str("firmware") ?: ""),
                "savedUtc" to (m.str("savedUtc") ?: "")
            )
        )
    }

    /** options: 1 = volumes, 2 = hardware I/O. */
    fun importConfig(s: DspState, text: String, options: Long): Pair<DspState, String> {
        val m = MiniJson.parseObject(text)
        val st = m?.obj("state")
        if (m == null || m.str("format") != FORMAT || st == null) {
            return s to MiniJson.write(mapOf("ok" to false, "error" to "This file isn't a DSPi configuration."))
        }
        val doc = DspStateJson.fromMap(st, s)
        val volumes = options and 1L != 0L
        val hardware = options and 2L != 0L
        var n =
            doc.copy(
                platformId = s.platformId, numChannels = s.numChannels, numInputChannels = s.numInputChannels,
                numOutputChannels = s.numOutputChannels, formatVersion = s.formatVersion, fwMajor = s.fwMajor, fwMinor = s.fwMinor,
                fwPatch = s.fwPatch, fwBeta = s.fwBeta, compat = s.compat, presetOccupied = s.presetOccupied,
                presetNames = s.presetNames, activePresetSlot = s.activePresetSlot, bulkValid = s.bulkValid, bulkRaw = s.bulkRaw
            )
        if (!volumes) n = n.copy(masterVolumeDb = s.masterVolumeDb, userVolumeDb = s.userVolumeDb, userMute = s.userMute)
        val hardwareWritten = hardware && (doc.outputPins != s.outputPins || doc.outputTypes != s.outputTypes)
        if (!hardware) n = n.copy(outputPins = s.outputPins, outputTypes = s.outputTypes, i2sBckPin = s.i2sBckPin, mckPin = s.mckPin)
        val lines = mutableListOf("Applied ${m.str("name") ?: "configuration"}.")
        if (m.str("platform") != platform(s)) lines += "Made on ${m.str("platform")}: channels this device lacks were skipped."
        return n to MiniJson.write(
            linkedMapOf(
                "ok" to true,
                "error" to "",
                "lines" to lines,
                "clean" to (lines.size == 1),
                "links" to m.long("links"),
                "hardwareWritten" to hardwareWritten
            )
        )
    }

    private val TYPES =
        mapOf(
            DspiConstants.FILTER_PEAKING to "PK",
            DspiConstants.FILTER_LOWSHELF to "LS",
            DspiConstants.FILTER_HIGHSHELF to "HS",
            DspiConstants.FILTER_LOWPASS to "LP",
            DspiConstants.FILTER_HIGHPASS to "HP",
            DspiConstants.FILTER_NOTCH to "NO",
            DspiConstants.FILTER_ALLPASS to "AP"
        )

    private fun label(s: DspState, w: Int) = s.channelNames.getOrNull(w)?.takeIf { it.isNotEmpty() }
        ?: if (w < s.numInputChannels) "Input ${w + 1}" else "Output ${w - s.numInputChannels + 1}"

    /** REW-style text, one `Channel:` section per wire channel with any band set. */
    fun exportFilters(s: DspState, inputs: Int): String {
        val sb = StringBuilder("Filter Settings file\n")
        val wires = (0 until minOf(inputs, s.numInputChannels)) + (s.numInputChannels until s.numChannels)
        for (w in wires) {
            sb.append("\nChannel: ").append(w).append(' ').append(label(s, w)).append('\n')
            s.filters[w].forEachIndexed { b, f ->
                val t = TYPES[f.filterType]
                if (t == null) {
                    sb.append("Filter ${b + 1}: OFF None\n")
                } else {
                    sb.append(
                        String.format(
                            Locale.ROOT,
                            "Filter %d: %s %s Fc %.1f Hz Gain %.1f dB Q %.3f\n",
                            b + 1,
                            if (f.bypass) "OFF" else "ON",
                            t,
                            f.freq,
                            f.gain,
                            f.q
                        )
                    )
                }
            }
        }
        return sb.toString()
    }

    private data class Section(val wire: Int?, val label: String, val bands: List<FilterParams>)

    private val LINE =
        Regex("""Filter\s+\d+:\s+(ON|OFF)\s+(\w+)(?:\s+Fc\s+([\d.]+)\s*Hz)?(?:\s+Gain\s+(-?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""")

    private fun parse(text: String): List<Section> {
        val out = mutableListOf<Section>()
        var wire: Int? = null
        var lbl = "Filters"
        var bands = mutableListOf<FilterParams>()
        fun flush() {
            if (bands.isNotEmpty()) out += Section(wire, lbl, bands)
            bands = mutableListOf()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("Channel:")) {
                flush()
                val rest = line.removePrefix("Channel:").trim()
                wire = rest.substringBefore(' ').toIntOrNull()
                lbl = rest.substringAfter(' ', rest)
                continue
            }
            val m = LINE.find(line) ?: continue
            val type = TYPES.entries.firstOrNull { it.value == m.groupValues[2] }?.key ?: DspiConstants.FILTER_FLAT
            bands +=
                FilterParams(
                    filterType = type,
                    bypass = m.groupValues[1] == "OFF" && type != DspiConstants.FILTER_FLAT,
                    freq = m.groupValues[3].toFloatOrNull() ?: 1000f,
                    q = m.groupValues[5].toFloatOrNull() ?: DspiConstants.DEFAULT_Q,
                    gain = m.groupValues[4].toFloatOrNull() ?: 0f,
                    qp = DspiConstants.DEFAULT_Q
                )
        }
        flush()
        return out
    }

    fun inspectFilters(s: DspState, text: String, inputs: Int): String {
        val sections = parse(text)
        val native = sections.any { it.wire != null }
        val channels =
            if (native) {
                sections.filter { it.wire != null && it.wire < s.numChannels }.map {
                    linkedMapOf(
                        "wire" to it.wire,
                        "label" to label(s, it.wire!!),
                        "bands" to it.bands.count { b -> b.filterType != 0 },
                        "checked" to true
                    )
                }
            } else if (sections.isNotEmpty()) {
                (0 until minOf(inputs, s.numInputChannels)).map {
                    linkedMapOf(
                        "wire" to it,
                        "label" to label(s, it),
                        "bands" to sections[0].bands.count { b -> b.filterType != 0 },
                        "checked" to (it < 2)
                    )
                }
            } else {
                emptyList()
            }
        return MiniJson.write(
            linkedMapOf(
                "kind" to if (native) "native" else "rew",
                "channels" to channels,
                "filters" to sections.sumOf { it.bands.size },
                "preamp" to 0
            )
        )
    }

    fun importFilters(s: DspState, text: String, wires: List<Int>): Pair<DspState, String> {
        val sections = parse(text)
        if (sections.isEmpty()) {
            return s to
                MiniJson.write(mapOf("ok" to false, "error" to "No filters found.", "lines" to emptyList<String>()))
        }
        var n = s
        val lines = mutableListOf<String>()
        for (w in wires) {
            if (w !in 0 until s.numChannels) continue
            val sec = sections.firstOrNull { it.wire == w } ?: sections.firstOrNull { it.wire == null } ?: continue
            val bands =
                List(DspiConstants.BANDS_PER_CHANNEL) { b ->
                    sec.bands.getOrNull(b)?.let { it.copy(gain = DspMath.quantizeGain(it.gain)) }
                        ?: defaultFilterParams()
                }
            n = n.copy(filters = n.filters.with(w, bands))
            lines += "${label(s, w)}: ${sec.bands.count { it.filterType != 0 }} filters"
        }
        return n to MiniJson.write(linkedMapOf("ok" to true, "error" to "", "lines" to lines))
    }
}
