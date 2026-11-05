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
 * Turns the player's 52 face values into:
 *  - [smile]: 0 = straight face, about 0.3 = a clear smile, 0.65+ = laughing
 *  - [reaction]: what else the face is doing (skeptical, eye-roll, yawn…)
 * Everything is measured as the change from the player's relaxed face, recorded at round start.
 */
class ReactionDetector {
    companion object {
        const val SMILE_LEVEL = 0.30f
        const val LAUGH_LEVEL = 0.65f
        private const val DEBOUNCE_MS = 250L
        private const val DROOP_MS = 800L
    }

    private var base = FloatArray(Shapes.N)
    private val s = FloatArray(Shapes.N)
    private var primed = false
    private var candidate = Reaction.NOTHING
    private var candidateSince = 0L
    private var droopSince = -1L

    var smile = 0f
        private set
    var reaction = Reaction.NOTHING
        private set

    fun setBaseline(b: FloatArray) {
        base = b.copyOf()
        reset()
    }

    fun reset() {
        primed = false
        smile = 0f
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

    fun update(face: FloatArray, now: Long) {
        // Light smoothing so single noisy frames don't flicker the bars.
        if (!primed) {
            face.copyInto(s)
            primed = true
        } else {
            for (k in s.indices) s[k] += 0.5f * (face[k] - s[k])
        }

        val mouthSmile = both("mouthSmile")
        val jaw = d("jawOpen")
        // An open jaw on top of a smile is laughing.
        smile = max(0f, mouthSmile + if (mouthSmile > 0.2f) 0.5f * max(0f, jaw) else 0f)

        // Mouth pulled to one side / one-sided smile.
        val skeptical = diff("mouthSmile") + abs(d("mouthLeft") - d("mouthRight")) + 0.5f * diff("mouthPress")
        // One eyebrow up, the other not.
        val really = diff("browOuterUp") + diff("browDown")
        // Eyes rolled up.
        val roll = both("eyeLookUp")
        // Yawn: jaw wide open without smiling. Droopy eyes: eyelids half-closed for a while (not a blink).
        val yawn = if (mouthSmile < 0.15f) jaw else 0f
        val lids = both("eyeBlink")
        if (lids > 0.35f && yawn < 0.4f) {
            if (droopSince < 0) droopSince = now
        } else {
            droopSince = -1L
        }
        val droopy = droopSince >= 0 && now - droopSince >= DROOP_MS
        // Anything else that moves the face noticeably.
        val other = maxOf(
            d("browInnerUp"), both("browOuterUp"), both("browDown"), both("eyeWide"), both("mouthFrown"), both("noseSneer"),
            d("mouthPucker"), d("mouthFunnel"), d("cheekPuff"), both("mouthStretch"),
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
