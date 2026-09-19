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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import android.widget.AdapterView
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
    private lateinit var shortcutStore: ShortcutStore
    private var shortcutCommands: List<ShortcutCommand> = emptyList()
    private var notReadyToastShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = TouchpadSettingsStore(this)
        shortcutStore = ShortcutStore(this)
        shortcutCommands = shortcutStore.ensureDefaults()
        touchpad = TouchpadView(this).apply {
            onPointer = ::sendPointer
            onButton = ::sendButton
            onScroll = ::sendScroll
            onZoom = ::sendZoom
            onGestureAction = ::performGestureAction
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
        shortcutCommands = shortcutStore.ensureDefaults()
        val workingActions = settings.gestureActions.toMutableMap()
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

        val gestureTitle = TextView(this).apply {
            text = "三指/四指手势"
            textSize = 17f
            setPadding(0, 20, 0, 4)
        }
        val gestureHint = TextView(this).apply {
            text = "滑动、点按和双击都可以绑定鼠标动作、快捷键或电脑端应用。"
            setTextColor(0xFF777777.toInt())
            setPadding(0, 0, 0, 8)
        }
        container.addView(gestureTitle)
        container.addView(gestureHint)

        val gestureSpinners = linkedMapOf<TouchpadGesture, Spinner>()
        var actionOptions = emptyList<TouchpadActionOption>()
        val refreshActionOptions = {
            actionOptions = TouchpadActionCatalog.options(
                shortcutCommands,
                FeatureSessionRegistry.appCatalog,
            )
            val labels = actionOptions.map(TouchpadActionOption::label)
            gestureSpinners.forEach { (gesture, spinner) ->
                val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    labels,
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                spinner.adapter = adapter
                val selectedId = workingActions[gesture.id] ?: TouchpadActionCatalog.NONE
                val selectedIndex = actionOptions.indexOfFirst { it.id == selectedId }
                spinner.setSelection(selectedIndex.coerceAtLeast(0))
            }
        }

        TouchpadGesture.entries.forEach { gesture ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 2, 0, 2)
            }
            val label = TextView(this).apply {
                text = gesture.label
                setTextColor(0xFF444444.toInt())
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(
                    (92 * resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            val spinner = Spinner(this)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    actionOptions.getOrNull(position)?.let { option ->
                        workingActions[gesture.id] = option.id
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            gestureSpinners[gesture] = spinner
            row.addView(
                spinner,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            container.addView(row)
        }
        refreshActionOptions()

        val addShortcutButton = Button(this).apply {
            text = "新增自定义组合键"
            setOnClickListener {
                showCustomShortcutEditor { command ->
                    shortcutCommands = shortcutStore.add(command)
                    refreshActionOptions()
                    Toast.makeText(this@TouchpadActivity, "组合键已加入备选列表", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(addShortcutButton)

        AlertDialog.Builder(this)
            .setTitle("触摸板设置")
            .setView(container)
            .setPositiveButton("完成") { _, _ ->
                val updated = touchpad.currentSettings().copy(gestureActions = workingActions.toMap())
                touchpad.applySettings(updated)
                settingsStore.set(updated)
            }
            .setOnCancelListener { settingsStore.set(touchpad.currentSettings()) }
            .show()
    }

    private fun showCustomShortcutEditor(onSaved: (ShortcutCommand) -> Unit) {
        val nameInput = EditText(this).apply {
            hint = "名称，例如：切换标签页"
            setSingleLine(true)
        }
        val keySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TouchpadActivity,
                android.R.layout.simple_spinner_item,
                ShortcutStore.keys,
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        container.addView(nameInput)
        val modifierHint = TextView(this).apply {
            text = "修饰键（可多选）"
            setPadding(0, 16, 0, 4)
        }
        container.addView(modifierHint)
        val modifierChecks = ShortcutStore.modifiers.map { modifier ->
            CheckBox(this).apply {
                text = modifier
                tag = modifier
            }.also(container::addView)
        }
        val keyHint = TextView(this).apply {
            text = "主键"
            setPadding(0, 12, 0, 4)
        }
        container.addView(keyHint)
        container.addView(keySpinner)

        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义组合键")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    nameInput.error = "请输入名称"
                    return@setOnClickListener
                }
                val command = ShortcutCommand(
                    id = ProtocolV3.newRequestId(),
                    name = name,
                    modifiers = modifierChecks
                        .filter(CheckBox::isChecked)
                        .mapNotNull { it.tag as? String },
                    key = keySpinner.selectedItem?.toString().orEmpty(),
                )
                onSaved(command)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun performGestureAction(actionId: String) {
        when (actionId) {
            TouchpadActionCatalog.NONE -> Unit
            TouchpadActionCatalog.LEFT_CLICK -> touchpad.triggerButtonClick(1)
            TouchpadActionCatalog.DOUBLE_LEFT_CLICK -> touchpad.triggerButtonClick(1, 2)
            TouchpadActionCatalog.RIGHT_CLICK -> touchpad.triggerButtonClick(2)
            TouchpadActionCatalog.MIDDLE_CLICK -> touchpad.triggerButtonClick(3)
            TouchpadActionCatalog.SCROLL_UP -> touchpad.triggerScroll(0f, -1000f)
            TouchpadActionCatalog.SCROLL_DOWN -> touchpad.triggerScroll(0f, 1000f)
            TouchpadActionCatalog.SCROLL_LEFT -> touchpad.triggerScroll(-1000f, 0f)
            TouchpadActionCatalog.SCROLL_RIGHT -> touchpad.triggerScroll(1000f, 0f)
            TouchpadActionCatalog.ZOOM_IN -> touchpad.triggerZoom(1f)
            TouchpadActionCatalog.ZOOM_OUT -> touchpad.triggerZoom(-1f)
            else -> {
                val shortcutId = TouchpadActionCatalog.shortcutId(actionId)
                val shortcut = shortcutId?.let { id -> shortcutCommands.firstOrNull { it.id == id } }
                if (shortcut != null) {
                    sendWhenSessionReady { session ->
                        session.sendShortcut(
                            ProtocolV3.shortcutJson(
                                ProtocolV3.newRequestId(),
                                shortcut.name,
                                shortcut.modifiers,
                                shortcut.key,
                                shortcut.repeat,
                            ),
                        )
                    }
                    return
                }

                val appId = TouchpadActionCatalog.appId(actionId)
                if (appId != null) {
                    sendWhenSessionReady { session ->
                        session.sendShortcut(ProtocolV3.appLaunchJson(ProtocolV3.newRequestId(), appId))
                    }
                }
            }
        }
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
