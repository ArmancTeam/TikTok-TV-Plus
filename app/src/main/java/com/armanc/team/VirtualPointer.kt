package com.armanc.team

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Sanal fare olayları. Gerçek bir fare gibi (SOURCE_MOUSE / TOOL_TYPE_MOUSE) gönderilir:
 * hover, tıklama ve tekerlek kaydırma. Böylece TikTok masaüstü arayüzündeki
 * hover menüleri ve ses çubuğu da çalışır.
 */
object VirtualPointer {

    private fun props() = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
    )

    private fun coords(px: Float, py: Float, vscroll: Float) = arrayOf(
        MotionEvent.PointerCoords().apply {
            x = px
            y = py
            pressure = 1f
            size = 1f
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
    )

    private fun obtain(
        downTime: Long, time: Long, action: Int,
        px: Float, py: Float, buttons: Int, vscroll: Float = 0f
    ): MotionEvent = MotionEvent.obtain(
        downTime, time, action, 1, props(), coords(px, py, vscroll),
        0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
    )

    private fun clampX(target: View, x: Float) = x.coerceIn(0f, target.width.toFloat())
    private fun clampY(target: View, y: Float) = y.coerceIn(0f, target.height.toFloat())

    fun hover(target: View, x: Float, y: Float) {
        if (target.width <= 0 || target.height <= 0) return
        val t = SystemClock.uptimeMillis()
        val e = obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, clampX(target, x), clampY(target, y), 0)
        target.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    fun click(target: View, x: Float, y: Float) {
        if (target.width <= 0 || target.height <= 0) return

        val cx = clampX(target, x)
        val cy = clampY(target, y)

        hover(target, cx, cy)

        val downTime = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = cx
                this.y = cy
                pressure = 1f
                size = 1f
            }
        )

        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            1, properties, coordinates, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            target.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            1, properties, coordinates, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            target.dispatchTouchEvent(up)
        } finally {
            up.recycle()
        }
    }

    /** vscroll > 0: yukarı kaydır, vscroll < 0: aşağı kaydır (Android AXIS_VSCROLL kuralı). */
    fun scroll(target: View, x: Float, y: Float, vscroll: Float) {
        if (target.width <= 0 || target.height <= 0) return
        val t = SystemClock.uptimeMillis()
        val e = obtain(t, t, MotionEvent.ACTION_SCROLL, clampX(target, x), clampY(target, y), 0, vscroll)
        target.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    /** Tekrar eden tuşta hızlanma çarpanı: 1x → 4x. */
    fun accel(repeatCount: Int): Float = 1f + minOf(repeatCount, 20) * 0.15f

    /** Hız ayarından (1..10) dp cinsinden adım. */
    fun stepPx(speedSetting: Int, density: Float): Float = speedSetting.coerceIn(1, 10) * 2.2f * density
}
