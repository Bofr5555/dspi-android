// Abridged port of DSPi-Console-Linux:core/src/presetdiff.rs @ 4683a2c `diff`
// for FakeDspiCore: the sections a UI test or demo touches, worded as the
// core words them. The real list comes from the core (preset_changes).
package se.bofr.dspi.fake

import java.util.Locale
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants.BANDS_PER_CHANNEL
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.MAX_CHANNELS
import se.bofr.dspi.core.DspiConstants.MAX_INPUTS
import se.bofr.dspi.core.DspiConstants.MAX_OUTPUTS
import se.bofr.dspi.core.DspiConstants.MAX_XOVER_BANDS
import se.bofr.dspi.core.FilterParams

object FakePresetDiff {
    private fun db(v: Float) = String.format(Locale.ROOT, "%.1f dB", v)

    private fun v(x: Float) = if (x == Math.rint(x.toDouble()).toFloat() && kotlin.math.abs(x) < 100000f) {
        String.format(Locale.ROOT, "%.0f", x)
    } else {
        String.format(Locale.ROOT, "%.1f", x)
    }

    private fun ms(x: Float) = String.format(Locale.ROOT, "%.4f", x).trimEnd('0').trimEnd('.')

    private fun on(b: Boolean) = if (b) "enabled" else "disabled"

    private fun label(s: DspState, w: Int): String {
        val name = s.channelNames.getOrElse(w) { "" }
        if (name.isNotEmpty()) return name
        val ins = s.numInputChannels
        if (w < ins) {
            return when (w) {
                0 -> "USB L"
                1 -> "USB R"
                else -> "Input ${w + 1}"
            }
        }
        return "Output ${w - ins + 1}"
    }

    private fun same(a: FilterParams, b: FilterParams): Boolean {
        fun qp(f: FilterParams) = if (f.filterType == FILTER_LINKWITZ_TRANSFORM) f.qp else 0f
        return a.filterType == b.filterType && a.bypass == b.bypass && a.freq == b.freq && a.q == b.q && a.gain == b.gain && qp(a) == qp(b)
    }

    fun diff(o: DspState, n: DspState): List<String> {
        val c = mutableListOf<String>()
        val ins = n.numInputChannels
        val outs = n.numOutputChannels
        val labels = listOf("L", "R", "FC", "LFE", "BL", "BR", "SL", "SR")
        for (i in 0 until minOf(ins, MAX_INPUTS)) {
            if (o.inputPreampDb[i] !=
                n.inputPreampDb[i]
            ) {
                c +=
                    "Preamp ${labels.getOrElse(i) { "In ${i + 1}" }}: ${db(o.inputPreampDb[i])} → ${db(n.inputPreampDb[i])}"
            }
        }
        if (n.masterVolumeMode == 1 && o.masterVolumeDb != n.masterVolumeDb) {
            fun mv(x: Float) = if (x <= -128f) "-∞ dB" else db(x)
            c += "Master Volume: ${mv(o.masterVolumeDb)} → ${mv(n.masterVolumeDb)}"
        }
        if (o.bypass != n.bypass) c += "Master EQ bypass: ${if (o.bypass) "on" else "off"} → ${if (n.bypass) "on" else "off"}"
        if (o.loudnessEnabled != n.loudnessEnabled) c += "Loudness: ${on(n.loudnessEnabled)}"
        if (o.loudnessRefSpl != n.loudnessRefSpl) c += "Loudness ref SPL: ${v(o.loudnessRefSpl)} → ${v(n.loudnessRefSpl)}"
        if (o.loudnessIntensity != n.loudnessIntensity) c += "Loudness intensity: ${v(o.loudnessIntensity)}% → ${v(n.loudnessIntensity)}%"
        if (o.crossfeedEnabled != n.crossfeedEnabled) c += "Crossfeed: ${on(n.crossfeedEnabled)}"
        if (o.crossfeedPreset != n.crossfeedPreset) c += "Crossfeed preset: ${o.crossfeedPreset} → ${n.crossfeedPreset}"
        if (o.crossfeedFreq != n.crossfeedFreq) c += "Crossfeed frequency: ${v(o.crossfeedFreq)} → ${v(n.crossfeedFreq)} Hz"
        if (o.crossfeedFeed != n.crossfeedFeed) c += "Crossfeed feed: ${v(o.crossfeedFeed)} → ${v(n.crossfeedFeed)}"
        for (p in 0 until 4) { // core patch 0002 presetdiff.rs
            val a = FakeDspiCore.crossfeedPair(o.crossfeedPairMap, p)
            val b = FakeDspiCore.crossfeedPair(n.crossfeedPairMap, p)
            if (a != b) c += "Crossfeed pair ${p + 1} outputs: ${a.first + 1}/${a.second + 1} → ${b.first + 1}/${b.second + 1}"
        }
        if (o.psybassEnabled != n.psybassEnabled) c += "Psychoacoustic Bass: ${on(n.psybassEnabled)}"
        if (o.levellerEnabled != n.levellerEnabled) c += "Volume Leveller: ${on(n.levellerEnabled)}"
        if (o.levellerAmount != n.levellerAmount) c += "Leveller amount: ${v(o.levellerAmount)}% → ${v(n.levellerAmount)}%"
        if (o.upmix != n.upmix) c += "Stereo Upmixer settings changed"
        if (o.subharm != n.subharm) c += "Subharmonic Synthesizer settings changed"
        if (o.tube != n.tube) c += "Tube Modeller settings changed"
        if (n.outputConfigMode == 1) {
            for (i in 0 until minOf(outs, MAX_OUTPUTS)) {
                if (o.limiterEnabled[i] != n.limiterEnabled[i]) c += "${label(n, ins + i)} limiter: ${on(n.limiterEnabled[i])}"
                if (o.limiterThresholdDb[i] != n.limiterThresholdDb[i]) {
                    c +=
                        String.format(
                            Locale.ROOT,
                            "%s limiter threshold: %.1f → %.1f dBFS",
                            label(n, ins + i),
                            o.limiterThresholdDb[i],
                            n.limiterThresholdDb[i]
                        )
                }
            }
        }
        for (w in 0 until minOf(ins, MAX_CHANNELS)) {
            if (o.channelDelays[w] !=
                n.channelDelays[w]
            ) {
                c += "${label(n, w)} delay: ${ms(o.channelDelays[w])} ms → ${ms(n.channelDelays[w])} ms"
            }
        }
        var points = 0
        for (i in 0 until minOf(ins, MAX_INPUTS)) {
            for (j in 0 until minOf(outs, MAX_OUTPUTS)) {
                if (o.matrixRouting[i][j] != n.matrixRouting[i][j] ||
                    o.matrixGain[i][j] != n.matrixGain[i][j] ||
                    o.matrixInvert[i][j] != n.matrixInvert[i][j]
                ) {
                    points++
                }
            }
        }
        if (points > 0) c += "$points crosspoint${if (points == 1) "" else "s"} changed"
        for (i in 0 until minOf(outs, MAX_OUTPUTS)) {
            val name = label(n, ins + i)
            if (o.outputEnabled[i] != n.outputEnabled[i]) c += "$name ${on(n.outputEnabled[i])}"
            if (o.outputMuted[i] != n.outputMuted[i]) c += "$name ${if (n.outputMuted[i]) "muted" else "unmuted"}"
            if (o.outputGainDb[i] != n.outputGainDb[i]) c += "$name gain: ${db(o.outputGainDb[i])} → ${db(n.outputGainDb[i])}"
            if (o.outputDelayMs[i] != n.outputDelayMs[i]) c += "$name delay: ${ms(o.outputDelayMs[i])} ms → ${ms(n.outputDelayMs[i])} ms"
        }
        for (w in 0 until minOf(ins + outs, MAX_CHANNELS)) {
            val changed = (0 until BANDS_PER_CHANNEL).count { !same(o.filters[w][it], n.filters[w][it]) }
            if (changed > 0) c += "$changed band${if (changed == 1) "" else "s"} changed on ${label(n, w)}"
        }
        for (w in ins until minOf(ins + outs, MAX_CHANNELS)) {
            val changed = (0 until MAX_XOVER_BANDS).count { !same(o.xover[w][it], n.xover[w][it]) }
            if (changed > 0) c += "$changed crossover band${if (changed == 1) "" else "s"} changed on ${label(n, w)}"
        }
        val typesChanged = o.outputTypes != n.outputTypes
        for (w in 0 until minOf(ins + outs, MAX_CHANNELS)) {
            if (o.channelNames[w] != n.channelNames[w] &&
                !(typesChanged && w >= ins)
            ) {
                c += "'${o.channelNames[w]}' → '${n.channelNames[w]}'"
            }
        }
        if (n.outputConfigMode == 1) {
            val pins = o.outputPins.indices.count { o.outputPins[it] != n.outputPins.getOrNull(it) }
            if (pins > 0) c += "$pins pin assignment${if (pins == 1) "" else "s"} changed"
            val types = (0 until 4).count { o.outputTypes[it] != n.outputTypes[it] }
            if (types > 0) c += "$types output type${if (types == 1) "" else "s"} changed"
        }
        return c
    }
}
