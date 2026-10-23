package com.armanc.team

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class NeonOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pink = Color.parseColor("#FE2C55")
    private val cyan = Color.parseColor("#25F4EE")

    private val pinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pink
        style = Paint.Style.FILL
    }

    private val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan
        style = Paint.Style.FILL
    }

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (height * 0.23f).coerceAtLeast(4f)
        val amplitude = radius * 1.65f

        val t = phase

        val movement = when {
            t < 0.35f -> smooth(t / 0.35f)
            t < 0.50f -> 1f - smooth((t - 0.35f) / 0.15f)
            t < 0.85f -> -smooth((t - 0.50f) / 0.35f)
            else -> -(1f - smooth((t - 0.85f) / 0.15f))
        }

        val pinkX = cx + amplitude * movement
        val cyanX = cx - amplitude * movement

        // Turkuaz neon noktası
        cyanPaint.setShadowLayer(radius * 0.85f, 0f, 0f, cyan)
        canvas.drawCircle(cyanX, cy, radius, cyanPaint)
        cyanPaint.clearShadowLayer()

        // Pembe neon noktası üstte çizilir
        pinkPaint.setShadowLayer(radius * 0.85f, 0f, 0f, pink)
        canvas.drawCircle(pinkX, cy, radius, pinkPaint)
        pinkPaint.clearShadowLayer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
