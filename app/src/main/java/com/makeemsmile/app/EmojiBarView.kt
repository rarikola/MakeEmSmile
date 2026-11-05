package com.makeemsmile.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bar 2: six reaction emojis in a row, with the current one's name above.
 * The current one gets a white frame and a short wiggle when it changes (no size change).
 */
class EmojiBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val reactions = Reaction.entries
    private var current = Reaction.NOTHING
    private var wiggle = 1f   // animation progress; 1 = finished (still)

    private val captionH = 34 * dp
    private val cellH = 54 * dp
    private val gap = 6 * dp
    private val radius = 10 * dp

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
        color = 0xAAFFFFFF.toInt()
    }
    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(21f)
        isFakeBoldText = true
        color = Color.WHITE
        textAlign = Paint.Align.RIGHT
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(28f)
    }
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 500L
        addUpdateListener {
            wiggle = it.animatedValue as Float
            invalidate()
        }
    }

    fun setReaction(r: Reaction) {
        if (r == current) return
        current = r
        animator.cancel()
        if (r != Reaction.NOTHING) animator.start() else wiggle = 1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (captionH + cellH + 4 * dp).toInt())
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val base = captionH - 10 * dp
        canvas.drawText(context.getString(R.string.bar_reaction), 0f, base, small)
        canvas.drawText(current.word, w, base, word)

        val cellW = (w - gap * (reactions.size - 1)) / reactions.size
        val top = captionH
        for ((i, r) in reactions.withIndex()) {
            val x = i * (cellW + gap)
            rect.set(x, top, x + cellW, top + cellH)
            val active = r == current
            fill.color = if (active) 0x40FFFFFF else 0x1AFFFFFF
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (active) {
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3 * dp
                rect.inset(1.5f * dp, 1.5f * dp)
                canvas.drawRoundRect(rect, radius, radius, stroke)
                rect.set(x, top, x + cellW, top + cellH)
            }

            emojiPaint.alpha = if (active) 255 else 110
            val fm = emojiPaint.fontMetrics
            val cx = rect.centerX()
            val cy = rect.centerY()
            val by = cy - (fm.ascent + fm.descent) / 2
            if (active && wiggle < 1f) {
                // Two quick tilts left and right that die out.
                val angle = (sin(wiggle * 4 * PI) * 14 * (1 - wiggle)).toFloat()
                canvas.save()
                canvas.rotate(angle, cx, cy)
                canvas.drawText(r.emoji, cx, by, emojiPaint)
                canvas.restore()
            } else {
                canvas.drawText(r.emoji, cx, by, emojiPaint)
            }
        }
    }
}
