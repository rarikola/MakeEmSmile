package com.makeemsmile.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Bar 1: [ boring 45% | smile 35% | laugh 20% ]. The section the face is in lights up with a white
 * frame, the others fade, and a white needle glides to the exact position.
 */
class SmileBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val labels = arrayOf(
        context.getString(R.string.bar_boring),
        context.getString(R.string.bar_smile),
        context.getString(R.string.bar_laugh),
    )
    /** Section edges as fractions of the bar width. */
    private val edges = floatArrayOf(0f, ReactionDetector.ZONE_SMILE, ReactionDetector.ZONE_LAUGH, 1f)
    private val bg = intArrayOf(0xFF6B6A66.toInt(), 0xFFFFFF54.toInt(), 0xFFFFB020.toInt())
    private val fg = intArrayOf(Color.WHITE, 0xFF5A5000.toInt(), 0xFF5A3000.toInt())

    private var target = 0f
    private var shown = 0f
    private var section = 0

    private val barH = 44 * dp
    private val radius = 10 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val clip = Path()

    /** @param pos 0..1 across the bar; @param level ReactionDetector.BORING/SLIGHT/SMILE/LAUGH */
    fun setValue(pos: Float, level: Int) {
        target = pos.coerceIn(0f, 1f)
        section = when (level) {
            ReactionDetector.LAUGH -> 2
            ReactionDetector.SMILE -> 1
            else -> 0
        }
        invalidate()
    }

    fun reset() {
        target = 0f
        shown = 0f
        section = 0
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (barH + 8 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val y = 4 * dp

        canvas.save()
        rect.set(0f, y, w, y + barH)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        for (k in 0..2) {
            rect.set(edges[k] * w, y, edges[k + 1] * w, y + barH)
            val active = k == section
            fill.color = bg[k]
            fill.alpha = if (active) 255 else 85
            canvas.drawRect(rect, fill)

            // Label, shrunk if the section is narrow (the 20% "laugh" one on small phones).
            text.textSize = sp(19f)
            val maxW = rect.width() - 8 * dp
            val tw = text.measureText(labels[k])
            if (tw > maxW) text.textSize = text.textSize * maxW / tw
            text.color = fg[k]
            text.alpha = if (active) 255 else 150
            val fm = text.fontMetrics
            canvas.drawText(labels[k], rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, text)

            if (active) {
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3 * dp
                rect.inset(1.5f * dp, 1.5f * dp)
                canvas.drawRect(rect, stroke)
            }
        }
        canvas.restore()

        // The needle glides toward its target instead of jumping.
        shown += 0.35f * (target - shown)
        val nx = (shown.coerceIn(0.01f, 0.99f)) * w
        fill.color = Color.WHITE
        fill.alpha = 255
        rect.set(nx - 2 * dp, y - 4 * dp, nx + 2 * dp, y + barH + 4 * dp)
        canvas.drawRoundRect(rect, 2 * dp, 2 * dp, fill)
        if (kotlin.math.abs(target - shown) > 0.002f) postInvalidateOnAnimation()
    }
}
