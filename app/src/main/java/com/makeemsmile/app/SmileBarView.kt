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
 * Bar 1: [ boring | smile | laugh ]. The section the face is in lights up with a white frame,
 * the others fade, and a white needle shows the exact position.
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
    private val bg = intArrayOf(0xFF6B6A66.toInt(), 0xFFFFFF54.toInt(), 0xFFFFB020.toInt())
    private val fg = intArrayOf(Color.WHITE, 0xFF5A5000.toInt(), 0xFF5A3000.toInt())

    private var value = 0f

    private val barH = 44 * dp
    private val radius = 10 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(19f)
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val clip = Path()

    fun setValue(v: Float) {
        value = v
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (barH + 8 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val x0 = 0f
        val x1 = width.toFloat()
        val y = 4 * dp
        val third = (x1 - x0) / 3f
        val level = when {
            value < ReactionDetector.SMILE_LEVEL -> 0
            value < ReactionDetector.LAUGH_LEVEL -> 1
            else -> 2
        }

        canvas.save()
        rect.set(x0, y, x1, y + barH)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        for (k in 0..2) {
            val sx = x0 + k * third
            rect.set(sx, y, sx + third, y + barH)
            val active = k == level
            fill.color = bg[k]
            fill.alpha = if (active) 255 else 85
            canvas.drawRect(rect, fill)
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

        val nx = x0 + position(value) * (x1 - x0)
        fill.color = Color.WHITE
        fill.alpha = 255
        rect.set(nx - 2 * dp, y - 4 * dp, nx + 2 * dp, y + barH + 4 * dp)
        canvas.drawRoundRect(rect, 2 * dp, 2 * dp, fill)
    }

    /** Smile value → 0..1 across the bar, each section taking a third. */
    private fun position(v: Float): Float {
        val s = ReactionDetector.SMILE_LEVEL
        val l = ReactionDetector.LAUGH_LEVEL
        val p = when {
            v < s -> v / s / 3f
            v < l -> 1f / 3f + (v - s) / (l - s) / 3f
            else -> 2f / 3f + minOf(1f, (v - l) / 0.35f) / 3f
        }
        return p.coerceIn(0.01f, 0.99f)
    }
}
