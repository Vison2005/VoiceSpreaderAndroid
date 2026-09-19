package com.voicespreader.remote

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot

/** 将触摸手势转换为 protocol 3 的鼠标、滚动和缩放事件。 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onPointer: (ProtocolV3.PointerPayload) -> Unit = {}
    var onButton: (ProtocolV3.ButtonPayload) -> Unit = {}
    var onScroll: (ProtocolV3.ScrollPayload) -> Unit = {}
    var onZoom: (ProtocolV3.ZoomPayload) -> Unit = {}
    var onGestureAction: (String) -> Unit = {}

    var sensitivity: Float = 1f
    var singleTapEnabled: Boolean = true
    var longPressDragEnabled: Boolean = true
    var twoFingerTapEnabled: Boolean = true
    var twoFingerScrollEnabled: Boolean = true
    var pinchZoomEnabled: Boolean = true
    private var gestureActions: Map<String, String> = TouchpadActionCatalog.defaultBindings()

    private var sequence = 0
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var movedBeyondSlop = false
    private var leftButtonDown = false
    private var longPressTriggered = false
    private var twoFingerMode = false
    private var twoFingerTapCandidate = false
    private var twoFingerMoved = false
    private var twoFingerDownAt = 0L
    private var twoFingerDownX = 0f
    private var twoFingerDownY = 0f
    private var initialPinchDistance = 0f
    private var lastPinchDistance = 0f
    private var pinchActive = false
    private var twoFingerGestureMode = TWO_FINGER_GESTURE_UNDECIDED
    private var multiFingerCount = 0
    private var multiFingerDownAt = 0L
    private var multiFingerDownX = 0f
    private var multiFingerDownY = 0f
    private var multiFingerLastX = 0f
    private var multiFingerLastY = 0f
    private var multiFingerMoved = false
    private var multiFingerDirection = MULTI_DIRECTION_UNDECIDED
    private var pendingMultiTap: PendingMultiTap? = null
    private var pendingMultiTapRunnable: Runnable? = null
    private var fileDragActive = false
    private var themeMode = ThemeMode.DARK

    private val longPressRunnable = Runnable {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID
            && !twoFingerMode
            && !movedBeyondSlop
            && !leftButtonDown
            && longPressDragEnabled
        ) {
            leftButtonDown = true
            longPressTriggered = true
            emitButton(1, true)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        applyTheme(ThemeModeStore(context).get())
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
        // 纯色背景：不绘制网格和底部提示，给全屏手势留出完整空间。
        invalidate()
    }

    fun applySettings(settings: TouchpadSettings) {
        sensitivity = settings.sensitivity
        singleTapEnabled = settings.singleTapEnabled
        longPressDragEnabled = settings.longPressDragEnabled
        twoFingerTapEnabled = settings.twoFingerTapEnabled
        twoFingerScrollEnabled = settings.twoFingerScrollEnabled
        pinchZoomEnabled = settings.pinchZoomEnabled
        gestureActions = settings.gestureActions
    }

    fun currentSettings(): TouchpadSettings = TouchpadSettings(
        sensitivity,
        singleTapEnabled,
        longPressDragEnabled,
        twoFingerTapEnabled,
        twoFingerScrollEnabled,
        pinchZoomEnabled,
        gestureActions,
    )

    fun triggerButtonClick(button: Int, count: Int = 1) {
        repeat(count.coerceIn(1, 3)) {
            emitButton(button, true)
            emitButton(button, false)
        }
    }

    fun triggerScroll(deltaX: Float, deltaY: Float) {
        emitScroll(deltaX, deltaY)
    }

    fun triggerZoom(delta: Float) {
        emitZoom(delta)
    }

    fun setFileDragActive(active: Boolean) {
        if (fileDragActive == active) return
        fileDragActive = active
        if (active) cancelPointer()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 当前版本只保留纯色全屏触控区域；文件共享区域留给后续版本。
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (fileDragActive) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginPointer(event)
            MotionEvent.ACTION_POINTER_DOWN -> beginAdditionalPointer(event)
            MotionEvent.ACTION_MOVE -> movePointer(event)
            MotionEvent.ACTION_POINTER_UP -> Unit
            MotionEvent.ACTION_UP -> endPointer(event)
            MotionEvent.ACTION_CANCEL -> cancelPointer()
        }
        return true
    }

    private fun beginPointer(event: MotionEvent) {
        activePointerId = event.getPointerId(0)
        lastX = event.x
        lastY = event.y
        downX = event.x
        downY = event.y
        downAt = SystemClock.elapsedRealtime()
        movedBeyondSlop = false
        leftButtonDown = false
        longPressTriggered = false
        twoFingerMode = false
        twoFingerTapCandidate = false
        twoFingerMoved = false
        twoFingerDownAt = 0L
        lastPinchDistance = 0f
        pinchActive = false
        removeCallbacks(longPressRunnable)
        postDelayed(longPressRunnable, LONG_PRESS_MILLISECONDS)
        emitPointer(ProtocolV3.PointerAction.DOWN, activePointerId, 0f, 0f)
    }

    private fun beginTwoFinger(event: MotionEvent) {
        if (event.pointerCount < 2 || twoFingerMode || multiFingerCount > 0) return
        twoFingerMode = true
        twoFingerTapCandidate = true
        twoFingerMoved = false
        twoFingerDownAt = SystemClock.elapsedRealtime()
        twoFingerDownX = (event.getX(0) + event.getX(1)) / 2f
        twoFingerDownY = (event.getY(0) + event.getY(1)) / 2f
        initialPinchDistance = pointerDistance(event)
        lastPinchDistance = initialPinchDistance
        pinchActive = false
        twoFingerGestureMode = TWO_FINGER_GESTURE_UNDECIDED
        removeCallbacks(longPressRunnable)
        if (leftButtonDown) {
            emitButton(1, false)
            leftButtonDown = false
        }
        emitPointer(ProtocolV3.PointerAction.CANCEL, activePointerId, 0f, 0f)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        lastX = twoFingerDownX
        lastY = twoFingerDownY
    }

    private fun beginAdditionalPointer(event: MotionEvent) {
        when {
            event.pointerCount == 2 -> beginTwoFinger(event)
            event.pointerCount >= 3 -> beginMultiFinger(event)
        }
    }

    private fun beginMultiFinger(event: MotionEvent) {
        if (event.pointerCount > 4) {
            multiFingerCount = MULTI_FINGER_UNSUPPORTED
            return
        }

        val now = SystemClock.elapsedRealtime()
        val (centerX, centerY) = centroid(event)
        if (multiFingerCount == 0) {
            multiFingerCount = event.pointerCount
            multiFingerDownAt = now
            multiFingerDownX = centerX
            multiFingerDownY = centerY
            multiFingerMoved = false
            multiFingerDirection = MULTI_DIRECTION_UNDECIDED
            multiFingerLastX = centerX
            multiFingerLastY = centerY
            removeCallbacks(longPressRunnable)
            if (leftButtonDown) {
                emitButton(1, false)
                leftButtonDown = false
            }
            if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                emitPointer(ProtocolV3.PointerAction.CANCEL, activePointerId, 0f, 0f)
            }
            activePointerId = MotionEvent.INVALID_POINTER_ID
            twoFingerMode = false
            return
        }

        // 在三指尚未移动时允许第四根手指加入，并以四指作为本次手势的目标数量。
        if (multiFingerCount == 3 && event.pointerCount == 4 && !multiFingerMoved) {
            multiFingerCount = 4
            multiFingerDownAt = now
            multiFingerDownX = centerX
            multiFingerDownY = centerY
            multiFingerLastX = centerX
            multiFingerLastY = centerY
        }
    }

    private fun movePointer(event: MotionEvent) {
        if (multiFingerCount != 0) {
            if (multiFingerCount > 0) moveMultiFinger(event)
            return
        }
        if (twoFingerMode) {
            if (event.pointerCount < 2) return
            val x = (event.getX(0) + event.getX(1)) / 2f
            val y = (event.getY(0) + event.getY(1)) / 2f
            val dx = x - lastX
            val dy = y - lastY
            val translationFromStart = hypot(x - twoFingerDownX, y - twoFingerDownY)
            if (translationFromStart > clickSlop()) {
                twoFingerMoved = true
                twoFingerTapCandidate = false
            }
            val distance = pointerDistance(event)
            val distanceDelta = distance - lastPinchDistance
            val distanceFromStart = abs(distance - initialPinchDistance)
            val density = resources.displayMetrics.density
            val pinchThreshold = maxOf(16f * density, initialPinchDistance * 0.10f)

            // 先锁定手势方向：平行移动优先进入滚动，只有明显改变两指间距才进入捏合。
            if (twoFingerGestureMode == TWO_FINGER_GESTURE_UNDECIDED) {
                if (pinchZoomEnabled && distanceFromStart >= pinchThreshold
                    && distanceFromStart > translationFromStart * 0.75f
                ) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_PINCH
                    pinchActive = true
                    twoFingerMoved = true
                    twoFingerTapCandidate = false
                } else if (translationFromStart >= clickSlop()
                    && translationFromStart > distanceFromStart * 1.5f
                ) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_SCROLL
                }
            }

            when (twoFingerGestureMode) {
                TWO_FINGER_GESTURE_PINCH -> {
                    if (pinchZoomEnabled && abs(distanceDelta) > 0.5f * density) {
                        emitZoom((distanceDelta / density / 16f).coerceIn(-1.5f, 1.5f))
                    }
                }
                TWO_FINGER_GESTURE_SCROLL -> {
                    if (twoFingerScrollEnabled && (abs(dx) > 0.1f || abs(dy) > 0.1f)) {
                        emitScroll(dx / 40f * 1000f, dy / 40f * 1000f)
                    }
                }
            }
            lastX = x
            lastY = y
            lastPinchDistance = distance
            return
        }

        val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
        // Android 触摸采样可能把多个轨迹点合并到一个 MotionEvent；补发最近的历史点，
        // 避免只取最后坐标造成光标跳动或看起来发卡。最多补发少量点，避免积压旧轨迹。
        val firstHistory = (event.historySize - MAX_HISTORY_SAMPLES).coerceAtLeast(0)
        for (historyIndex in firstHistory until event.historySize) {
            processSinglePointerPosition(
                event.getHistoricalX(index, historyIndex),
                event.getHistoricalY(index, historyIndex),
            )
        }
        processSinglePointerPosition(event.getX(index), event.getY(index))
    }

    private fun moveMultiFinger(event: MotionEvent) {
        if (event.pointerCount < 3 || event.pointerCount > 4 || multiFingerCount == 0) return
        val (centerX, centerY) = centroid(event)
        val totalX = centerX - multiFingerDownX
        val totalY = centerY - multiFingerDownY
        val distance = hypot(totalX, totalY)
        if (distance >= multiFingerSwipeSlop()) {
            multiFingerMoved = true
            if (multiFingerDirection == MULTI_DIRECTION_UNDECIDED) {
                multiFingerDirection = if (abs(totalX) >= abs(totalY)) {
                    if (totalX >= 0f) MULTI_DIRECTION_RIGHT else MULTI_DIRECTION_LEFT
                } else {
                    if (totalY >= 0f) MULTI_DIRECTION_DOWN else MULTI_DIRECTION_UP
                }
            }
        }
        multiFingerLastX = centerX
        multiFingerLastY = centerY
    }

    private fun processSinglePointerPosition(x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY
        if (abs(x - downX) > clickSlop() || abs(y - downY) > clickSlop()) {
            movedBeyondSlop = true
            removeCallbacks(longPressRunnable)
        }
        emitPointer(ProtocolV3.PointerAction.MOVE, activePointerId, dx * sensitivity, dy * sensitivity)
        lastX = x
        lastY = y
    }

    private fun endPointer(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        if (multiFingerCount != 0) {
            if (multiFingerCount > 0) endMultiFinger()
            resetPointer()
            return
        }
        if (twoFingerMode) {
            if (twoFingerTapEnabled
                && twoFingerTapCandidate
                && !twoFingerMoved
                && SystemClock.elapsedRealtime() - twoFingerDownAt < TWO_FINGER_TAP_MILLISECONDS
            ) {
                emitButton(2, true)
                emitButton(2, false)
            }
            resetPointer()
            return
        }
        if (leftButtonDown) emitButton(1, false)
        emitPointer(ProtocolV3.PointerAction.UP, activePointerId, 0f, 0f)
        if (singleTapEnabled
            && !longPressTriggered
            && !movedBeyondSlop
            && SystemClock.elapsedRealtime() - downAt < SHORT_TAP_MILLISECONDS
            && abs(event.x - downX) < clickSlop()
            && abs(event.y - downY) < clickSlop()
        ) {
            emitButton(1, true)
            emitButton(1, false)
        }
        resetPointer()
    }

    private fun endMultiFinger() {
        val fingerCount = multiFingerCount
        if (multiFingerMoved) {
            val gesture = gestureForSwipe(fingerCount, multiFingerDirection)
            emitGestureAction(gesture)
            return
        }

        if (SystemClock.elapsedRealtime() - multiFingerDownAt >= MULTI_FINGER_TAP_MILLISECONDS) {
            return
        }
        handleMultiFingerTap(fingerCount)
    }

    private fun handleMultiFingerTap(fingerCount: Int) {
        val now = SystemClock.elapsedRealtime()
        val doubleGesture = gestureForTap(fingerCount, doubleTap = true)
        val singleGesture = gestureForTap(fingerCount, doubleTap = false)
        val doubleAction = doubleGesture?.let { gestureActions[it.id] }
            .orEmpty()
            .ifBlank { TouchpadActionCatalog.NONE }

        val previous = pendingMultiTap
        if (doubleAction != TouchpadActionCatalog.NONE
            && previous != null
            && previous.fingerCount == fingerCount
            && now - previous.at <= MULTI_FINGER_DOUBLE_TAP_MILLISECONDS
        ) {
            pendingMultiTapRunnable?.let(::removeCallbacks)
            pendingMultiTapRunnable = null
            pendingMultiTap = null
            emitGestureAction(doubleGesture)
            return
        }

        if (previous != null && previous.fingerCount != fingerCount) {
            pendingMultiTapRunnable?.let(::removeCallbacks)
            pendingMultiTapRunnable = null
            pendingMultiTap = null
        }

        if (doubleAction == TouchpadActionCatalog.NONE) {
            emitGestureAction(singleGesture)
            return
        }

        val pending = PendingMultiTap(fingerCount, now)
        pendingMultiTap = pending
        val runnable = Runnable {
            if (pendingMultiTap == pending) {
                pendingMultiTap = null
                pendingMultiTapRunnable = null
                emitGestureAction(singleGesture)
            }
        }
        pendingMultiTapRunnable = runnable
        postDelayed(runnable, MULTI_FINGER_DOUBLE_TAP_MILLISECONDS)
    }

    private fun emitGestureAction(gesture: TouchpadGesture?) {
        val actionId = gesture?.let { gestureActions[it.id] }
            .orEmpty()
            .ifBlank { TouchpadActionCatalog.NONE }
        if (actionId != TouchpadActionCatalog.NONE) onGestureAction(actionId)
    }

    private fun gestureForTap(fingerCount: Int, doubleTap: Boolean): TouchpadGesture? =
        when {
            fingerCount == 3 && doubleTap -> TouchpadGesture.THREE_DOUBLE_TAP
            fingerCount == 3 -> TouchpadGesture.THREE_TAP
            fingerCount == 4 && doubleTap -> TouchpadGesture.FOUR_DOUBLE_TAP
            fingerCount == 4 -> TouchpadGesture.FOUR_TAP
            else -> null
        }

    private fun gestureForSwipe(fingerCount: Int, direction: Int): TouchpadGesture? = when {
        fingerCount == 3 && direction == MULTI_DIRECTION_UP -> TouchpadGesture.THREE_SWIPE_UP
        fingerCount == 3 && direction == MULTI_DIRECTION_DOWN -> TouchpadGesture.THREE_SWIPE_DOWN
        fingerCount == 3 && direction == MULTI_DIRECTION_LEFT -> TouchpadGesture.THREE_SWIPE_LEFT
        fingerCount == 3 && direction == MULTI_DIRECTION_RIGHT -> TouchpadGesture.THREE_SWIPE_RIGHT
        fingerCount == 4 && direction == MULTI_DIRECTION_UP -> TouchpadGesture.FOUR_SWIPE_UP
        fingerCount == 4 && direction == MULTI_DIRECTION_DOWN -> TouchpadGesture.FOUR_SWIPE_DOWN
        fingerCount == 4 && direction == MULTI_DIRECTION_LEFT -> TouchpadGesture.FOUR_SWIPE_LEFT
        fingerCount == 4 && direction == MULTI_DIRECTION_RIGHT -> TouchpadGesture.FOUR_SWIPE_RIGHT
        else -> null
    }

    private fun emitPointer(action: ProtocolV3.PointerAction, pointerId: Int, deltaX: Float, deltaY: Float) {
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

    private fun emitZoom(delta: Float) {
        onZoom(
            ProtocolV3.ZoomPayload(
                nextSequence(),
                SystemClock.elapsedRealtimeNanos() / 1_000L,
                delta,
            ),
        )
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun centroid(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (index in 0 until event.pointerCount) {
            x += event.getX(index)
            y += event.getY(index)
        }
        return x / event.pointerCount to y / event.pointerCount
    }

    private fun nextSequence(): Int {
        sequence = if (sequence == Int.MAX_VALUE) 0 else sequence + 1
        return sequence
    }

    private fun cancelPointer() {
        removeCallbacks(longPressRunnable)
        pendingMultiTapRunnable?.let(::removeCallbacks)
        pendingMultiTapRunnable = null
        pendingMultiTap = null
        if (leftButtonDown) {
            emitButton(1, false)
            leftButtonDown = false
        }
        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            emitPointer(ProtocolV3.PointerAction.CANCEL, activePointerId, 0f, 0f)
        }
        resetPointer()
    }

    private fun resetPointer() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        movedBeyondSlop = false
        leftButtonDown = false
        longPressTriggered = false
        twoFingerMode = false
        twoFingerTapCandidate = false
        twoFingerMoved = false
        twoFingerDownAt = 0L
        initialPinchDistance = 0f
        lastPinchDistance = 0f
        pinchActive = false
        twoFingerGestureMode = TWO_FINGER_GESTURE_UNDECIDED
        multiFingerCount = 0
        multiFingerDownAt = 0L
        multiFingerMoved = false
        multiFingerDirection = MULTI_DIRECTION_UNDECIDED
    }

    private fun clickSlop(): Float = 18f * resources.displayMetrics.density

    private fun multiFingerSwipeSlop(): Float = 32f * resources.displayMetrics.density

    private data class PendingMultiTap(val fingerCount: Int, val at: Long)

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        pendingMultiTapRunnable?.let(::removeCallbacks)
        pendingMultiTapRunnable = null
        pendingMultiTap = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LONG_PRESS_MILLISECONDS = 480L
        private const val SHORT_TAP_MILLISECONDS = 240L
        private const val TWO_FINGER_TAP_MILLISECONDS = 320L
        private const val TWO_FINGER_GESTURE_UNDECIDED = 0
        private const val TWO_FINGER_GESTURE_SCROLL = 1
        private const val TWO_FINGER_GESTURE_PINCH = 2
        private const val MULTI_FINGER_UNSUPPORTED = -1
        private const val MULTI_DIRECTION_UNDECIDED = 0
        private const val MULTI_DIRECTION_UP = 1
        private const val MULTI_DIRECTION_DOWN = 2
        private const val MULTI_DIRECTION_LEFT = 3
        private const val MULTI_DIRECTION_RIGHT = 4
        private const val MAX_HISTORY_SAMPLES = 4
        private const val MULTI_FINGER_TAP_MILLISECONDS = 300L
        private const val MULTI_FINGER_DOUBLE_TAP_MILLISECONDS = 300L
    }
}
