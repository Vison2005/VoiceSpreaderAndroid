package com.voicespreader.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** 将手机触摸转换为 protocol 3 指针、鼠标键和滚动事件。 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onPointer: (ProtocolV3.PointerPayload) -> Unit = {}
    var onButton: (ProtocolV3.ButtonPayload) -> Unit = {}
    var onScroll: (ProtocolV3.ScrollPayload) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sequence = 0
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var twoFingerMode = false
    private var fileDragActive = false
    private var themeMode = ThemeMode.DARK

    init {
        isClickable = true
        isFocusable = true
        applyTheme(ThemeModeStore(context).get())
        paint.strokeWidth = 1f
    }

    fun applyTheme(mode: ThemeMode) {
        themeMode = mode
        setBackgroundColor(
            when (mode) {
                ThemeMode.LIGHT -> ContextCompat.getColor(context, R.color.background)
                ThemeMode.DARK -> 0xFF0B172B.toInt()
                ThemeMode.EXTREME_DARK -> 0xFF000000.toInt()
            },
        )
        paint.color = when (mode) {
            ThemeMode.LIGHT -> 0xFFD7E4EE.toInt()
            ThemeMode.DARK -> 0xFF294365.toInt()
            ThemeMode.EXTREME_DARK -> 0xFF182237.toInt()
        }
        invalidate()
    }

    fun setFileDragActive(active: Boolean) {
        if (fileDragActive == active) return
        fileDragActive = active
        if (active) cancelPointer()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (themeMode != ThemeMode.EXTREME_DARK) {
            val gridSize = 48f * resources.displayMetrics.density
            var x = gridSize
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                x += gridSize
            }
            var y = gridSize
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, paint)
                y += gridSize
            }
        }
        paint.color = if (fileDragActive) 0xFF77A9EE.toInt() else when (themeMode) {
            ThemeMode.LIGHT -> 0xFF526B84.toInt()
            ThemeMode.DARK -> 0xFFB7C8DF.toInt()
            ThemeMode.EXTREME_DARK -> 0xFF63738E.toInt()
        }
        paint.textSize = 13f * resources.displayMetrics.density
        val hint = if (fileDragActive) "松开以发送文件" else "触摸板 · 双指滚动 · 文件拖入共享区"
        canvas.drawText(hint, 18f, height - 18f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fileDragActive) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downAt = SystemClock.elapsedRealtime()
                twoFingerMode = false
                emitPointer(ProtocolV3.PointerAction.DOWN, activePointerId, 0f, 0f)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    twoFingerMode = true
                    emitPointer(ProtocolV3.PointerAction.CANCEL, activePointerId, 0f, 0f)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingerMode && event.pointerCount >= 2) {
                    val x = (event.getX(0) + event.getX(1)) / 2f
                    val y = (event.getY(0) + event.getY(1)) / 2f
                    emitScroll((x - lastX) / 40f, (y - lastY) / 40f)
                    lastX = x
                    lastY = y
                } else {
                    val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                    val x = event.getX(index)
                    val y = event.getY(index)
                    emitPointer(ProtocolV3.PointerAction.MOVE, activePointerId, x - lastX, y - lastY)
                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) twoFingerMode = false
            }

            MotionEvent.ACTION_UP -> {
                if (!twoFingerMode) {
                    emitPointer(ProtocolV3.PointerAction.UP, activePointerId, 0f, 0f)
                    if (SystemClock.elapsedRealtime() - downAt < 240L
                        && abs(event.x - downX) < clickSlop()
                        && abs(event.y - downY) < clickSlop()
                    ) {
                        emitButton(1, true)
                        emitButton(1, false)
                    }
                }
                resetPointer()
            }

            MotionEvent.ACTION_CANCEL -> cancelPointer()
        }
        return true
    }

    private fun emitPointer(
        action: ProtocolV3.PointerAction,
        pointerId: Int,
        deltaX: Float,
        deltaY: Float,
    ) {
        onPointer(
            ProtocolV3.PointerPayload(
                nextSequence(),
                SystemClock.elapsedRealtimeNanos() / 1_000L,
                action,
                pointerId,
                deltaX,
                deltaY,
            ),
        )
    }

    private fun emitButton(button: Int, down: Boolean) {
        onButton(
            ProtocolV3.ButtonPayload(
                nextSequence(),
                SystemClock.elapsedRealtimeNanos() / 1_000L,
                button,
                down,
            ),
        )
    }

    private fun emitScroll(deltaX: Float, deltaY: Float) {
        onScroll(
            ProtocolV3.ScrollPayload(
                nextSequence(),
                SystemClock.elapsedRealtimeNanos() / 1_000L,
                deltaX,
                deltaY,
            ),
        )
    }

    private fun nextSequence(): Int {
        sequence = if (sequence == Int.MAX_VALUE) 0 else sequence + 1
        return sequence
    }

    private fun cancelPointer() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            emitPointer(ProtocolV3.PointerAction.CANCEL, activePointerId, 0f, 0f)
        }
        resetPointer()
    }

    private fun resetPointer() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        twoFingerMode = false
    }

    private fun clickSlop(): Float = 18f * resources.displayMetrics.density
}
