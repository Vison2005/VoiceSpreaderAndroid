package com.voicespreader.remote

import android.content.Context
import androidx.core.content.edit

/** 保存触摸板手势偏好；设置只影响当前手机端的手势解释方式。 */
data class TouchpadSettings(
    val sensitivity: Float = 1f,
    val singleTapEnabled: Boolean = true,
    val longPressDragEnabled: Boolean = true,
    val twoFingerTapEnabled: Boolean = true,
    val twoFingerScrollEnabled: Boolean = true,
    val pinchZoomEnabled: Boolean = true,
)

class TouchpadSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("touchpad", Context.MODE_PRIVATE)

    fun get(): TouchpadSettings = TouchpadSettings(
        sensitivity = preferences.getFloat(KEY_SENSITIVITY, 1f).coerceIn(0.5f, 2f),
        singleTapEnabled = preferences.getBoolean(KEY_SINGLE_TAP, true),
        longPressDragEnabled = preferences.getBoolean(KEY_LONG_PRESS, true),
        twoFingerTapEnabled = preferences.getBoolean(KEY_TWO_FINGER_TAP, true),
        twoFingerScrollEnabled = preferences.getBoolean(KEY_TWO_FINGER_SCROLL, true),
        pinchZoomEnabled = preferences.getBoolean(KEY_PINCH_ZOOM, true),
    )

    fun set(settings: TouchpadSettings) {
        preferences.edit {
            putFloat(KEY_SENSITIVITY, settings.sensitivity.coerceIn(0.5f, 2f))
            putBoolean(KEY_SINGLE_TAP, settings.singleTapEnabled)
            putBoolean(KEY_LONG_PRESS, settings.longPressDragEnabled)
            putBoolean(KEY_TWO_FINGER_TAP, settings.twoFingerTapEnabled)
            putBoolean(KEY_TWO_FINGER_SCROLL, settings.twoFingerScrollEnabled)
            putBoolean(KEY_PINCH_ZOOM, settings.pinchZoomEnabled)
        }
    }

    private companion object {
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_SINGLE_TAP = "single_tap"
        const val KEY_LONG_PRESS = "long_press"
        const val KEY_TWO_FINGER_TAP = "two_finger_tap"
        const val KEY_TWO_FINGER_SCROLL = "two_finger_scroll"
        const val KEY_PINCH_ZOOM = "pinch_zoom"
    }
}
