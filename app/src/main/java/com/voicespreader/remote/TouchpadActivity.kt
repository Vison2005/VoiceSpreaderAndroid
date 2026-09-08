package com.voicespreader.remote

import android.graphics.Color
import android.os.Bundle
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 全屏触摸板页面；控制连接由前台服务和功能会话独立维护。 */
class TouchpadActivity : AppCompatActivity() {
    private lateinit var touchpad: TouchpadView
    private lateinit var settingsButton: TextView
    private lateinit var settingsStore: TouchpadSettingsStore
    private var notReadyToastShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = TouchpadSettingsStore(this)
        touchpad = TouchpadView(this).apply {
            onPointer = ::sendPointer
            onButton = ::sendButton
            onScroll = ::sendScroll
            onZoom = ::sendZoom
            // 手势保持默认开启；设置页只暴露光标速度，避免旧版本关闭手势后无法恢复。
            applySettings(settingsStore.get().copy(
                singleTapEnabled = true,
                longPressDragEnabled = true,
                twoFingerTapEnabled = true,
                twoFingerScrollEnabled = true,
                pinchZoomEnabled = true,
            ))
        }
        settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            contentDescription = "触摸板设置"
            gravity = Gravity.CENTER
            alpha = 0.72f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            background = null
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSettings() }
        }

        val root = FrameLayout(this)
        root.addView(touchpad, FrameLayout.LayoutParams(-1, -1))
        val buttonSize = (52 * resources.displayMetrics.density).toInt()
        root.addView(
            settingsButton,
            FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.END),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val params = settingsButton.layoutParams as FrameLayout.LayoutParams
            val density = resources.displayMetrics.density
            val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            // 竖屏隐藏系统栏后仍可能报告较大的状态栏 inset；在不越过刘海安全区的前提下收紧间距。
            val topInset = if (portrait) {
                maxOf(
                    insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
                    safe.top - (20 * density).toInt(),
                )
            } else {
                safe.top
            }
            val margin = (if (portrait) 4 else 12) * density
            params.setMargins(0, topInset + margin.toInt(), safe.right + margin.toInt(), 0)
            settingsButton.layoutParams = params
            insets
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        makeFullscreen()
    }

    override fun onStart() {
        super.onStart()
        FeatureSessionRegistry.ensureConnected(
            this,
            onConnected = { _ -> notReadyToastShown = false },
            onError = { message -> runOnUiThread { showNotReadyToast(message) } },
        )
    }

    override fun onStop() {
        // 页面退出不关闭功能会话，避免旋转或切回桌面时音频连接被误断开。
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) makeFullscreen()
    }

    private fun makeFullscreen() {
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun showSettings() {
        val settings = touchpad.currentSettings()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val sensitivityLabel = TextView(this).apply {
            text = "光标速度：${(settings.sensitivity * 100).toInt()}%"
            setPadding(0, 12, 0, 0)
        }
        val sensitivityBar = SeekBar(this).apply {
            max = 150
            progress = ((settings.sensitivity - 0.5f) * 100).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val speed = (0.5f + value / 100f).coerceIn(0.5f, 2f)
                    sensitivityLabel.text = "光标速度：${(speed * 100).toInt()}%"
                    touchpad.sensitivity = speed
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        container.addView(sensitivityLabel)
        container.addView(sensitivityBar)
        AlertDialog.Builder(this)
            .setTitle("光标设置")
            .setView(container)
            .setPositiveButton("完成") { _, _ -> settingsStore.set(touchpad.currentSettings()) }
            .setOnCancelListener { settingsStore.set(touchpad.currentSettings()) }
            .show()
    }

    private fun sendPointer(value: ProtocolV3.PointerPayload) {
        // 移动事件频率最高；连接正常时直接入队，避免每个 MOVE 都创建 lambda 并增加主线程 GC。
        val session = FeatureSessionRegistry.control
        if (session != null) {
            runCatching { session.sendPointer(value) }
                .onFailure { FeatureSessionRegistry.invalidate(session) }
            return
        }
        sendWhenSessionReady { it.sendPointer(value) }
    }

    private fun sendButton(value: ProtocolV3.ButtonPayload) = sendWhenSessionReady { it.sendButton(value) }

    private fun sendScroll(value: ProtocolV3.ScrollPayload) = sendWhenSessionReady { it.sendScroll(value) }

    private fun sendZoom(value: ProtocolV3.ZoomPayload) = sendWhenSessionReady { it.sendZoom(value) }

    private fun sendWhenSessionReady(action: (ProtocolV3Client.Session) -> Unit) {
        val session = FeatureSessionRegistry.control
        if (session != null) {
            if (runCatching { action(session) }.isFailure) FeatureSessionRegistry.invalidate(session)
            return
        }
        FeatureSessionRegistry.ensureConnected(
            this,
            onConnected = { notReadyToastShown = false },
            onError = { message -> runOnUiThread { showNotReadyToast(message) } },
        )
        showNotReadyToast("正在连接电脑，请稍后再操作")
    }

    private fun showNotReadyToast(message: String) {
        if (notReadyToastShown) return
        notReadyToastShown = true
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_FEATURE_REQUEST = "com.voicespreader.remote.action.FEATURE_REQUEST"
        const val EXTRA_FEATURE = "feature"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_FEATURE_MODE = "feature_mode"
    }
}
