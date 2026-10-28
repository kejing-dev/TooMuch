package io.embodit.toomuch.domain.model

/**
 * The six sounds of the experience (design `audio.sources`): gains per level L0..L4 (dB, file level
 * = 0 dB), the cap, whether the source is a pulse (single shots on a schedule) or a loop, the pulse
 * intervals at L0 and L4 (seconds, linear in between), and the unloading options with their effects.
 */
enum class SourceId(val tag: String) {
    FRIDGE("fridge"),
    CLOCK("clock"),
    BAG("bag"),
    TAP("tap"),
    LAMP("lamp"),
    PHONE("phone");

    companion object {
        fun fromTag(tag: String?): SourceId? = entries.firstOrNull { it.tag == tag?.lowercase() }
    }
}

/** What an unloading option does to its source (design `audio.sources[].unloadOptions[].effect`). */
enum class UnloadEffect {
    /** Gain down to L0. */
    TO_L0,

    /** Silent. */
    MUTE,

    /** Gain to L0 and the pulse interval back to its L0 value. */
    TO_L0_SLOW_PULSES,

    /** Lamp: silent, blobs and haze fade out. */
    MUTE_OVERLAY_OFF,

    /** Lamp: gain to L0, no blobs, haze 0.02. */
    TO_L0_OVERLAY_SOFT,
}

data class SourceSpec(
    val id: SourceId,
    val gainDbByLevel: FloatArray,
    val capDb: Float,
    val pulse: Boolean,
    /** Pulse interval range at L0 and at L4, seconds (pulse sources only). */
    val intervalL0: ClosedFloatingPointRange<Float> = 0f..0f,
    val intervalL4: ClosedFloatingPointRange<Float> = 0f..0f,
    /** The unloading options in card order. */
    val options: List<UnloadEffect>,
) {
    fun gainDb(level: Int): Float = gainDbByLevel[level.coerceIn(0, 4)]

    /** Pulse interval bounds at [level] (linear between L0 and L4). */
    fun interval(level: Int): ClosedFloatingPointRange<Float> {
        val f = level.coerceIn(0, 4) / 4f
        val lo = intervalL0.start + (intervalL4.start - intervalL0.start) * f
        val hi = intervalL0.endInclusive + (intervalL4.endInclusive - intervalL0.endInclusive) * f
        return lo..hi
    }

    override fun equals(other: Any?): Boolean = other is SourceSpec && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

object Sources {
    val FRIDGE = SourceSpec(SourceId.FRIDGE, floatArrayOf(-27f, -21f, -15f, -9f, -4f), -4f, pulse = false, options = listOf(UnloadEffect.TO_L0, UnloadEffect.MUTE))
    val CLOCK = SourceSpec(SourceId.CLOCK, floatArrayOf(-26f, -20f, -14f, -9f, -5f), -5f, pulse = false, options = listOf(UnloadEffect.MUTE, UnloadEffect.TO_L0))
    val BAG =
        SourceSpec(
            SourceId.BAG, floatArrayOf(-24f, -18f, -12f, -7f, -3f), -3f, pulse = true,
            intervalL0 = 4f..6f, intervalL4 = 1.5f..3f,
            options = listOf(UnloadEffect.MUTE, UnloadEffect.TO_L0_SLOW_PULSES),
        )
    val TAP = SourceSpec(SourceId.TAP, floatArrayOf(-27f, -21f, -15f, -10f, -6f), -6f, pulse = false, options = listOf(UnloadEffect.MUTE))
    val LAMP = SourceSpec(SourceId.LAMP, floatArrayOf(-31f, -25f, -19f, -13f, -8f), -8f, pulse = false, options = listOf(UnloadEffect.MUTE_OVERLAY_OFF, UnloadEffect.TO_L0_OVERLAY_SOFT))
    val PHONE =
        SourceSpec(
            SourceId.PHONE, floatArrayOf(-23f, -17f, -12f, -8f, -4f), -4f, pulse = true,
            intervalL0 = 10f..15f, intervalL4 = 5f..8f,
            options = listOf(UnloadEffect.MUTE, UnloadEffect.TO_L0_SLOW_PULSES),
        )

    val all: List<SourceSpec> = listOf(FRIDGE, CLOCK, BAG, TAP, LAMP, PHONE)

    operator fun get(id: SourceId): SourceSpec = all.first { it.id == id }

    /** Minimum gap between two shots of one pulse source (safety A3). */
    const val MIN_PULSE_GAP_S = 1.5f

    /** Fridge compressor hiss layer from L3: -18 dB at L3, -12 dB at L4 (design `cue`). */
    fun fridgeHissDb(level: Int): Float? =
        when {
            level >= 4 -> -12f
            level == 3 -> -18f
            else -> null
        }

    /** The clock's transients turn hard from L2; the tap's basin resonance joins from L3. */
    const val CLOCK_HARD_FROM = 2
    const val TAP_BASS_FROM = 3
}

/** Strength of the whole mix (design A2): gentle presses the group 8 dB down (default), standard 0 dB. */
enum class Intensity(val tag: String, val groupDb: Float) {
    GENTLE("gentle", -8f),
    STANDARD("standard", 0f);

    companion object {
        fun fromTag(tag: String?): Intensity = entries.firstOrNull { it.tag == tag?.lowercase() } ?: GENTLE
    }
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

enum class InputMode { CONTROLLER, HANDS }

/** Why the Stage cannot run the experience (S14). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }
