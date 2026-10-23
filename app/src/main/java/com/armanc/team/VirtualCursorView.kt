package com.armanc.team

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.PathParser

/**
 * Ortak sanal imleç. Hem ana ekranda hem Login ekranında kullanılır.
 *
 * Şekiller: Ok, Kağıt uçak, Nokta ve Damla (eski NeonCursorView tasarımı).
 * - Şekiller 32x32'lik bir kutuda Path olarak çizilir (boyuta göre ölçeklenir).
 * - Kenar çizgisi renge göre seçilir: koyu renkte beyaz, açık renkte siyah.
 * - "Neon" renklerde parlama, çok geçişli yarı saydam çizgilerle yapılır (donanım hızlandırmalı).
 * - Her şeklin bir "hotspot" noktası vardır; tıklama/hover bu noktaya gider.
 */
class VirtualCursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Ayarlarda saklanan anahtarlar. Listede olmayan eski kayıtlar varsayılana düşer. */
        val COLOR_KEYS = listOf(
            "Neon Cyan", "Neon Pink", "Neon Green", "Neon Purple", "Neon Blue",
            "Neon Yellow", "Neon Orange", "Neon Red", "Neon White", "Neon Lime",
            "Cyan", "Pink", "Green", "Purple", "Blue", "Yellow", "Orange", "Red", "White", "Black"
        )
        val SHAPE_KEYS = listOf("Arrow", "Paper", "Dot", "Drop")

        const val DEFAULT_COLOR = "Neon Cyan"
        const val DEFAULT_SHAPE = "Arrow"
        const val DEFAULT_SIZE_DP = 32
        const val BOX = 32f

        fun colorFor(key: String): Int = when (key) {
            "Neon Cyan" -> Color.rgb(0, 243, 255)
            "Neon Pink" -> Color.rgb(255, 0, 170)
            "Neon Green" -> Color.rgb(57, 255, 20)
            "Neon Purple" -> Color.rgb(191, 0, 255)
            "Neon Blue" -> Color.rgb(0, 110, 255)
            "Neon Yellow" -> Color.rgb(255, 240, 0)
            "Neon Orange" -> Color.rgb(255, 95, 0)
            "Neon Red" -> Color.rgb(255, 20, 40)
            "Neon White" -> Color.WHITE
            "Neon Lime" -> Color.rgb(180, 255, 0)
            "Cyan" -> Color.rgb(0, 188, 212)
            "Pink" -> Color.rgb(233, 30, 99)
            "Green" -> Color.rgb(76, 175, 80)
            "Purple" -> Color.rgb(156, 39, 176)
            "Blue" -> Color.rgb(33, 150, 243)
            "Yellow" -> Color.rgb(255, 235, 59)
            "Orange" -> Color.rgb(255, 152, 0)
            "Red" -> Color.rgb(244, 67, 54)
            "White" -> Color.WHITE
            "Black" -> Color.BLACK
            else -> Color.rgb(0, 243, 255)
        }

        private fun luminance(c: Int): Float =
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    }

    /** body: ana gövde, hole: üstüne çizilen yarı saydam delik (varsa), hx/hy: hotspot (32'lik kutuda). */
    private class Shape(val body: Path, val hole: Path?, val hx: Float, val hy: Float)

    private fun p(data: String): Path = PathParser.createPathFromPathData(data) ?: Path()

    /** Eski NeonCursorView geometrisi: daire + sol üstte sivri uç (0,0), ortada delik. */
    private fun dropShape(): Shape {
        val c = BOX * 0.53f
        val r = BOX * 0.42f
        val body = Path().apply { addCircle(c, c, r, Path.Direction.CW) }
        val tip = Path().apply {
            moveTo(0f, 0f)
            lineTo(c - r * 0.28f, c - r * 0.88f)
            lineTo(c - r * 0.88f, c - r * 0.28f)
            close()
        }
        body.op(tip, Path.Op.UNION)
        val hole = Path().apply { addCircle(c, c, BOX * 0.15f, Path.Direction.CW) }
        return Shape(body, hole, 0f, 0f)
    }

    private val shapes: Map<String, Shape> by lazy {
        mapOf(
            "Arrow" to Shape(
                p("M4,2 L4,24 L9.5,18.8 L13.5,28.5 L17,27 L13,17.5 L21,17.5 Z"), null, 4f, 2f
            ),
            "Paper" to Shape(p("M2,3 L30,13 L18,17 L14,30 Z"), null, 2f, 3f),
            "Dot" to Shape(
                Path().apply { addCircle(16f, 16f, 5f, Path.Direction.CW) }, null, 16f, 16f
            ),
            "Drop" to dropShape()
        )
    }

    private var colorKey = DEFAULT_COLOR
    private var shapeKey = DEFAULT_SHAPE
    private var clickFeedback = false

    /** OK tuşu basılıyken görsel tıklama efektini gösterir. */
    fun setClickFeedback(pressed: Boolean) {
        if (clickFeedback == pressed) return
        clickFeedback = pressed
        invalidate()
    }

private var feedbackReset: Runnable? = null

    /** Kısa tıklama geri bildirimi: kırmızı renk ve küçülme. */
    fun showClickFeedback() {
        clickFeedback = true
        invalidate()

        feedbackReset?.let { removeCallbacks(it) }
        val reset = Runnable {
            clickFeedback = false
            feedbackReset = null
            invalidate()
        }
        feedbackReset = reset
        postDelayed(reset, 180L)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#40000000")
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private fun currentShape(): Shape = shapes[shapeKey] ?: shapes.getValue(DEFAULT_SHAPE)

    /** Hotspot'un görünüm içindeki x konumu (piksel). */
    fun hotspotX(): Float =
        currentShape().hx / BOX * minOf(width, height).toFloat().coerceAtLeast(1f)

    /** Hotspot'un görünüm içindeki y konumu (piksel). */
    fun hotspotY(): Float =
        currentShape().hy / BOX * minOf(width, height).toFloat().coerceAtLeast(1f)

    fun setStyle(colorKey: String, shapeKey: String) {
        this.colorKey = if (colorKey in COLOR_KEYS) colorKey else DEFAULT_COLOR
        this.shapeKey = if (shapeKey in SHAPE_KEYS) shapeKey else DEFAULT_SHAPE
        invalidate()
    }

    /**
     * Ayarlardan renk, şekil ve (isteğe bağlı) boyut uygular.
     * @param resize false ise boyuta dokunulmaz (ayar ekranı önizlemesi).
     */
    fun applyPrefs(prefs: SharedPreferences, resize: Boolean = true) {
        setStyle(
            prefs.getString(SettingsActivity.KEY_CURSOR_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR,
            prefs.getString(SettingsActivity.KEY_CURSOR_SHAPE, DEFAULT_SHAPE) ?: DEFAULT_SHAPE
        )
        if (resize) {
            val dp = prefs.getInt(SettingsActivity.KEY_CURSOR_SIZE, DEFAULT_SIZE_DP).coerceIn(16, 64)
            val px = (dp * resources.displayMetrics.density).toInt()
            val lp = layoutParams
            if (lp != null && (lp.width != px || lp.height != px)) {
                lp.width = px
                lp.height = px
                layoutParams = lp
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shape = currentShape()
        val color = if (clickFeedback) Color.RED else colorFor(colorKey)
        val neon = !clickFeedback && colorKey.startsWith("Neon")
        val outline = if (luminance(color) < 0.35f) Color.WHITE else Color.BLACK

        val scale = minOf(width, height).toFloat() / BOX
        if (scale <= 0f) return

        canvas.save()
        canvas.scale(scale, scale)
        if (clickFeedback) {
            canvas.scale(0.78f, 0.78f, BOX / 2f, BOX / 2f)
        }

        // Parlama: geniş, yarı saydam çizgiler (katman/software render gerektirmez).
        if (neon) {
            glowPaint.color = color
            for ((w, a) in listOf(7f to 45, 4.5f to 90)) {
                glowPaint.strokeWidth = w
                glowPaint.alpha = a
                canvas.drawPath(shape.body, glowPaint)
            }
        }

        fillPaint.color = color
        canvas.drawPath(shape.body, fillPaint)
        outlinePaint.color = outline
        outlinePaint.strokeWidth = 1.5f
        canvas.drawPath(shape.body, outlinePaint)

        shape.hole?.let { canvas.drawPath(it, holePaint) }

        canvas.restore()
    }
}
