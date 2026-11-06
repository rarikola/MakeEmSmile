package com.makeemsmile.app

import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max

/** Picks the first emoji this phone can draw, so newer emojis fall back to older look-alikes. */
object Emoji {
    private val paint = Paint()
    private val cache = HashMap<List<String>, String>()

    fun pick(choices: List<String>): String =
        cache.getOrPut(choices) { choices.firstOrNull { paint.hasGlyph(it) } ?: choices.last() }

    val SMILE get() = pick(listOf("😄"))
    val LAUGH get() = pick(listOf("😂"))
    val TROPHY get() = pick(listOf("🏆"))
    val BANG get() = pick(listOf("💥"))
}

/** The reactions shown on the second bar, in display order. */
enum class Reaction(val word: String, private val choices: List<String>) {
    NOTHING("nothing", listOf("😐")),
    SKEPTICAL("skeptical", listOf("🫤", "😕")),
    REALLY("really?", listOf("🤨", "🤔", "😕")),
    YEAH_RIGHT("yeah, right", listOf("🙄", "😒")),
    BORED("booring", listOf("🥱", "😪", "😴")),
    OTHER("other", listOf("🥴", "😵"));

    val emoji: String get() = Emoji.pick(choices)
}

/**
 * Turns the player's 52 face values into a smile level, a position on the smile bar, and a reaction.
 * Everything is measured as the change from the player's relaxed face, recorded at round start.
 *
 * Smile levels follow how face researchers (FACS) tell smiles apart:
 *  - SLIGHT: lip corners pulled up a little. Still "boring" on the bar; only triggers "almost!".
 *  - SMILE:  lip corners clearly up AND smiling eyes (cheeks raised, eyes narrowed). This is a
 *            real smile, and it means cracked. A polite mouth-only smile doesn't count.
 *  - LAUGH:  a real smile plus an open mouth and squeezed eyes.
 * Showing teeth on its own isn't a smile: a stretched mouth (grimace, "eek") pulls the corners
 * sideways rather than up, so it is subtracted from the corner lift and shows up as a reaction instead.
 */
class ReactionDetector {
    companion object {
        const val BORING = 0
        const val SLIGHT = 1
        const val SMILE = 2
        const val LAUGH = 3

        /** Where each section starts on the bar: boring 45% | smile 35% | laugh 20%. */
        const val ZONE_SMILE = 0.45f
        const val ZONE_LAUGH = 0.80f

        private const val SLIGHT_CORNERS = 0.15f
        private const val SMILE_CORNERS = 0.35f
        private const val SMILE_EYES = 0.15f
        private const val LAUGH_CORNERS = 0.45f
        private const val LAUGH_EYES = 0.20f
        private const val LAUGH_JAW = 0.25f
        /** Once a level is reached, it's kept until the face drops below 85% of the entry values. */
        private const val STAY = 0.85f

        private const val SMOOTHING = 0.35f
        private const val DEBOUNCE_MS = 250L
        private const val DROOP_MS = 800L
    }

    private var base = FloatArray(Shapes.N)
    private val s = FloatArray(Shapes.N)
    private var primed = false
    private var candidate = Reaction.NOTHING
    private var candidateSince = 0L
    private var droopSince = -1L

    /** BORING, SLIGHT, SMILE or LAUGH. */
    var level = BORING
        private set
    /** 0..1 across the smile bar. */
    var barPos = 0f
        private set
    var reaction = Reaction.NOTHING
        private set

    fun setBaseline(b: FloatArray) {
        base = b.copyOf()
        reset()
    }

    fun reset() {
        primed = false
        level = BORING
        barPos = 0f
        reaction = Reaction.NOTHING
        candidate = Reaction.NOTHING
        candidateSince = 0L
        droopSince = -1L
    }

    private fun d(name: String): Float {
        val k = Shapes.INDEX[name] ?: return 0f
        return s[k] - base[k]
    }

    private fun both(name: String) = (d(name + "Left") + d(name + "Right")) / 2f
    private fun diff(name: String) = abs(d(name + "Left") - d(name + "Right"))
    private fun frac(v: Float, from: Float, to: Float) = ((v - from) / (to - from)).coerceIn(0f, 1f)

    fun update(face: FloatArray, now: Long) {
        // Smoothing so single noisy frames don't flicker the bars.
        if (!primed) {
            face.copyInto(s)
            primed = true
        } else {
            for (k in s.indices) s[k] += SMOOTHING * (face[k] - s[k])
        }

        // ---- Smile ----
        val stretch = both("mouthStretch")
        val corners = both("mouthSmile") - 0.5f * max(0f, stretch - 0.2f)
        val eyes = max(both("cheekSquint"), 0.8f * both("eyeSquint"))
        val jaw = d("jawOpen")

        // Hysteresis: entering a level needs the full values, staying needs 85% of them.
        fun at(lvl: Int) = if (level >= lvl) STAY else 1f
        val slight = corners >= SLIGHT_CORNERS * at(SLIGHT)
        val real = corners >= SMILE_CORNERS * at(SMILE) && eyes >= SMILE_EYES * at(SMILE)
        val laugh = real && corners >= LAUGH_CORNERS * at(LAUGH) && eyes >= LAUGH_EYES * at(LAUGH) && jaw >= LAUGH_JAW * at(LAUGH)
        level = when {
            laugh -> LAUGH
            real -> SMILE
            slight -> SLIGHT
            else -> BORING
        }
        barPos = when (level) {
            LAUGH -> ZONE_LAUGH + (1f - ZONE_LAUGH) * (0.1f + 0.85f * frac(jaw, LAUGH_JAW, 0.6f))
            SMILE -> ZONE_SMILE + (ZONE_LAUGH - ZONE_SMILE) *
                (0.1f + 0.85f * (0.6f * frac(corners, SMILE_CORNERS, 0.65f) + 0.4f * frac(eyes, SMILE_EYES, 0.45f)))
            // Boring zone: the needle creeps toward "smile" as the corners rise, but never crosses without smiling eyes.
            else -> ZONE_SMILE * 0.95f * frac(corners, 0f, SMILE_CORNERS)
        }

        // ---- Reactions ----
        val mouthSmile = both("mouthSmile")
        // Mouth pulled to one side / one-sided smile.
        val skeptical = diff("mouthSmile") + abs(d("mouthLeft") - d("mouthRight")) + 0.5f * diff("mouthPress")
        // One eyebrow up, the other not.
        val really = diff("browOuterUp") + diff("browDown")
        // Eyes rolled up.
        val roll = both("eyeLookUp")
        // Yawn: jaw wide open without smiling. Droopy eyes: eyelids half-closed for a while (not a blink).
        val yawn = if (mouthSmile < 0.15f) jaw else 0f
        val lids = both("eyeBlink")
        if (lids > 0.35f && yawn < 0.4f && level < SMILE) {
            if (droopSince < 0) droopSince = now
        } else {
            droopSince = -1L
        }
        val droopy = droopSince >= 0 && now - droopSince >= DROOP_MS
        // Anything else that moves the face noticeably, including a teeth-baring grimace.
        val other = maxOf(
            d("browInnerUp"), both("browOuterUp"), both("browDown"), both("eyeWide"), both("mouthFrown"),
            both("noseSneer"), d("mouthPucker"), d("mouthFunnel"), d("cheekPuff"), stretch,
        )

        // Each score divided by its trigger level: 1.0 or more means "showing". "Other" only wins when nothing specific does.
        val ratios = floatArrayOf(
            0f,
            skeptical / 0.22f,
            really / 0.25f,
            roll / 0.30f,
            max(yawn / 0.40f, if (droopy) 1.2f else 0f),
            other / 0.35f * 0.85f,
        )
        var best = 0
        var bestRatio = 1f
        for (k in 1 until ratios.size) {
            if (ratios[k] >= bestRatio) {
                bestRatio = ratios[k]
                best = k
            }
        }
        val next = Reaction.entries[best]

        // Only switch after the new reaction has held for a moment.
        if (next != candidate) {
            candidate = next
            candidateSince = now
        }
        if (candidate != reaction && now - candidateSince >= DEBOUNCE_MS) reaction = candidate
    }
}
