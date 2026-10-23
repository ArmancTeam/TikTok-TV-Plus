package com.armanc.team

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class TikTokNoteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 12f
    }

    private val paths = listOf(
        Path().apply { moveTo(56f, 20f); lineTo(56f, 68f) },
        Path().apply {
            moveTo(56f, 68f)
            arcTo(android.graphics.RectF(26f, 53f, 56f, 83f), 0f, 270f, false)
        },
        Path().apply {
            moveTo(56f, 20f)
            cubicTo(58f, 36f, 68f, 44f, 82f, 45f)
        }
    )

    private val lengths = paths.map { PathMeasure(it, false).length }
    private val colors = intArrayOf(Color.rgb(37,244,238), Color.rgb(254,44,85), Color.WHITE)
    private var progress = 0f
    private var pop = 1f
    private var shakeX = 0f
    private var shakeY = 0f
    private var animator: ValueAnimator? = null

    fun startLogoAnimation() {
        animator?.cancel()
        progress = 0f
        pop = 1f
        shakeX = 0f
        shakeY = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                progress = (t * 5000f / 1140f).coerceIn(0f, 1f)
                pop = when {
                    t < 0.19f -> 1f + 0.12f * (t / 0.19f)
                    t < 0.24f -> 1.12f - 0.12f * ((t - 0.19f) / 0.05f)
                    else -> 1f
                }
                val elapsed = t * 5000f
                val shake = listOf(0f to 0f, -6f to 2f, 5f to -3f, -3f to 0f, 4f to 2f, 0f to 0f)
                if (elapsed in 1100f..1580f) {
                    val index = (((elapsed - 1100f) / 80f).toInt()).coerceIn(0, shake.lastIndex)
                    shakeX = shake[index].first
                    shakeY = shake[index].second
                } else {
                    shakeX = 0f
                    shakeY = 0f
                }
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLogoAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val save = canvas.save()
        canvas.translate((width-side)/2f + shakeX, (height-side)/2f + shakeY)
        canvas.scale(side / 100f * pop, side / 100f * pop)

        colors.forEachIndexed { layer, color ->
            val delay = layer * 120f / 900f
            val p = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (p > 0f) {
                val layerSave = canvas.save()
                when (layer) {
                    0 -> canvas.translate(-1.5f, -1.5f)
                    1 -> canvas.translate(1.5f, 1.5f)
                }
                paint.color = color
                paths.forEachIndexed { index, path ->
                    val segment = Path()
                    PathMeasure(path, false).getSegment(0f, lengths[index] * p, segment, true)
                    canvas.drawPath(segment, paint)
                }
                canvas.restoreToCount(layerSave)
            }
        }
        canvas.restoreToCount(save)
    }
}
