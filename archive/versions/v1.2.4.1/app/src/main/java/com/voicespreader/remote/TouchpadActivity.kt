package com.voicespreader.remote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import android.content.res.ColorStateList
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

/** 手机控制台：触摸板、快捷键和应用内文件共享区的第一版 UI 骨架。 */
class TouchpadActivity : AppCompatActivity() {
    private lateinit var touchpad: TouchpadView
    private lateinit var filePanel: LinearLayout
    private lateinit var fileItemsContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var shortcutStore: ShortcutStore
    private lateinit var themeStore: ThemeModeStore
    private lateinit var appShortcutBar: LinearLayout
    private val selectedUris = LinkedHashMap<String, Uri>()
    private var pendingScanRequestId: String? = null

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50),
    ) { uris ->
        uris.forEach(::rememberUri)
        renderFiles()
    }

    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        status.text = "已授权文件夹，可继续浏览或拖入共享区"
        toast("文件夹已添加")
    }

    private val cameraScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.getStringExtra(ScanActivity.EXTRA_PAYLOAD)?.let {
            sendScanResult(it, pendingScanRequestId ?: ProtocolV3.newRequestId())
            pendingScanRequestId = null
        }
    }

    private val galleryScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.getStringExtra(GalleryScanActivity.EXTRA_PAYLOAD)?.let {
            sendScanResult(it, pendingScanRequestId ?: ProtocolV3.newRequestId())
            pendingScanRequestId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shortcutStore = ShortcutStore(this)
        themeStore = ThemeModeStore(this)
        setContentView(createContent())
        applyInsets(findViewById(ROOT_ID))
        FeatureSessionRegistry.onAppCatalogChanged = { apps ->
            runOnUiThread { renderAppShortcuts(apps) }
        }
        renderAppShortcuts(FeatureSessionRegistry.appCatalog)
        handleFeatureIntent(intent)
        FeatureSessionRegistry.ensureConnected(this) {
            runOnUiThread { status.text = "protocol 3 控制连接不可用：$it" }
        }
    }

    override fun onDestroy() {
        if (::touchpad.isInitialized) {
            touchpad.setFileDragActive(false)
        }
        FeatureSessionRegistry.onAppCatalogChanged = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFeatureIntent(intent)
    }

    private fun createContent(): View {
        val mode = themeStore.get()
        val extreme = mode == ThemeMode.EXTREME_DARK
        val root = LinearLayout(this).apply {
            id = ROOT_ID
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor(mode))
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(if (extreme) Color.BLACK else surfaceColor(mode), 18)
        }
        toolbar.addView(TextView(this).apply {
            text = "触摸板控制台"
            textSize = 20f
            setTextColor(primaryTextColor(mode))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(actionButton("文件面板") { toggleFilePanel() })
        toolbar.addView(actionButton("编辑快捷键") { showShortcutEditor() })
        toolbar.addView(actionButton("主题") { cycleTheme() })
        toolbar.addView(actionButton("关闭") { finish() })
        root.addView(toolbar)

        status = TextView(this).apply {
            text = if (FeatureSessionRegistry.control == null) "未连接 protocol 3 控制通道" else "已连接，可使用触摸板"
            textSize = 12f
            setTextColor(secondaryTextColor(mode))
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(status)

        val compact = resources.configuration.screenWidthDp < 600
        val workspace = LinearLayout(this).apply {
            orientation = if (compact) {
                LinearLayout.VERTICAL
            } else {
                LinearLayout.HORIZONTAL
            }
            weightSum = 1f
        }
        touchpad = TouchpadView(this).apply {
            onPointer = { value -> sendPointer(value) }
            onButton = { value -> sendButton(value) }
            onScroll = { value -> sendScroll(value) }
            setOnDragListener { _, event -> handleFileDrag(event) }
        }
        // LinearLayout 的 weight 只会作用于主轴；同时把宽高设为 0 会让触摸板在
        // 竖屏或横屏布局中都拿不到可触摸区域，表现为光标完全无响应。
        val touchpadParams = if (compact) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        } else {
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            )
        }
        workspace.addView(touchpad, touchpadParams)
        filePanel = createFilePanel()
        workspace.addView(
            filePanel,
            if (compact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            } else {
                LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT)
            },
        )
        root.addView(workspace, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
            weight = 1f
        })

        val shortcutBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        shortcutStore.ensureDefaults().take(4).forEach { command ->
            shortcutBar.addView(actionButton(command.name) {
                sendShortcut(command)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        root.addView(shortcutBar)
        appShortcutBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(appShortcutBar)
        return root
    }

    private fun renderAppShortcuts(apps: List<FeatureSessionRegistry.RemoteAppShortcut>) {
        if (!::appShortcutBar.isInitialized) return
        appShortcutBar.removeAllViews()
        apps.take(6).forEach { app ->
            appShortcutBar.addView(actionButton(app.name) {
                sendAppLaunch(app)
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun createFilePanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(10), 0, 0, 0)
        background = rounded(if (themeStore.get() == ThemeMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF101B2C.toInt(), 18)
        addView(TextView(context).apply {
            text = "文件共享区"
            textSize = 16f
            setTextColor(primaryTextColor(themeStore.get()))
            setPadding(dp(10), dp(12), dp(10), dp(4))
        })
        addView(actionButton("选择相册") {
            galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        })
        addView(actionButton("授权文件夹") { directoryPicker.launch(null) })
        addView(actionButton("摄像头扫码") { launchScan("camera", ProtocolV3.newRequestId()) })
        addView(actionButton("相册扫码") { launchScan("gallery", ProtocolV3.newRequestId()) })
        addView(actionButton("拍照传电脑") {
            startActivity(
                Intent(this@TouchpadActivity, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_REQUEST_ID, ProtocolV3.newRequestId()),
            )
        })
        addView(TextView(context).apply {
            text = "长按文件后拖入左侧触摸板即可发送"
            textSize = 11f
            setTextColor(secondaryTextColor(themeStore.get()))
            setPadding(dp(10), dp(12), dp(10), dp(8))
        })
        fileItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(fileItemsContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
            weight = 1f
        })
    }

    private fun renderFiles() {
        if (!::fileItemsContainer.isInitialized) return
        fileItemsContainer.removeAllViews()
        selectedUris.values.forEach { uri ->
            val view = TextView(this).apply {
                text = displayName(uri)
                textSize = 12f
                setTextColor(primaryTextColor(themeStore.get()))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnLongClickListener {
                    val clip = ClipData(
                        "VoiceSpreader 文件",
                        arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST),
                        ClipData.Item(uri),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        startDragAndDrop(clip, View.DragShadowBuilder(this), uri, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        startDrag(clip, View.DragShadowBuilder(this), uri, 0)
                    }
                }
            }
            fileItemsContainer.addView(view)
        }
    }

    private fun handleFileDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val accepted = event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) == true
                touchpad.setFileDragActive(accepted)
                return accepted
            }

            DragEvent.ACTION_DROP -> {
                val uri = event.clipData?.getItemAt(0)?.uri
                if (uri != null) offerUri(uri)
                return uri != null
            }

            DragEvent.ACTION_DRAG_ENDED -> touchpad.setFileDragActive(false)
        }
        return true
    }

    private fun offerUri(uri: Uri) {
        touchpad.setFileDragActive(false)
        val pairing = FeatureSessionRegistry.pairing
        val deviceId = FeatureSessionRegistry.deviceId
        val session = featureSession(ProtocolV3.CAP_FILE_SEND)
        if (session == null || pairing == null || deviceId.isNullOrBlank()) {
            return
        }
        val source = sourceFromUri(uri) ?: run {
            toast("无法读取文件信息")
            return
        }
        val client = FileTransferClient(pairing, deviceId, session)
        runCatching {
            val transferId = client.offer(listOf(source))
            FileTransferCoordinator.register(transferId, client, listOf(source))
            status.text = "已提交文件，等待电脑确认"
        }.onFailure {
            client.close()
            handleFeatureFailure(session, it)
        }
    }

    private fun sourceFromUri(uri: Uri): FileTransferSource? {
        val itemId = UUID.randomUUID()
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
        if (size < 0L) return null
        return FileTransferSource(
            ProtocolV3.FileItem(
                itemId,
                displayName(uri),
                contentResolver.getType(uri) ?: "application/octet-stream",
                size,
                0L,
            ),
        ) { contentResolver.openInputStream(uri) ?: error("文件无法打开") }
    }

    private fun sendPointer(value: ProtocolV3.PointerPayload) {
        val session = featureSession(ProtocolV3.CAP_TOUCHPAD) ?: return
        runCatching { session.sendPointer(value) }
            .onFailure { handleFeatureFailure(session, it) }
    }

    private fun sendButton(value: ProtocolV3.ButtonPayload) {
        val session = featureSession(ProtocolV3.CAP_TOUCHPAD) ?: return
        runCatching { session.sendButton(value) }
            .onFailure { handleFeatureFailure(session, it) }
    }

    private fun sendScroll(value: ProtocolV3.ScrollPayload) {
        val session = featureSession(ProtocolV3.CAP_TOUCHPAD) ?: return
        runCatching { session.sendScroll(value) }
            .onFailure { handleFeatureFailure(session, it) }
    }

    private fun sendShortcut(command: ShortcutCommand) {
        val session = featureSession(ProtocolV3.CAP_SHORTCUT) ?: return
        runCatching {
            session.sendShortcut(
                ProtocolV3.shortcutJson(
                    ProtocolV3.newRequestId(),
                    command.name,
                    command.modifiers,
                    command.key,
                    command.repeat,
                ),
            )
        }.onFailure { handleFeatureFailure(session, it) }
    }

    private fun sendAppLaunch(app: FeatureSessionRegistry.RemoteAppShortcut) {
        val session = featureSession(ProtocolV3.CAP_APP_LAUNCH) ?: return
        runCatching {
            session.sendShortcut(
                ProtocolV3.appLaunchJson(ProtocolV3.newRequestId(), app.id),
            )
            status.text = "已请求启动 ${app.name}"
        }.onFailure { handleFeatureFailure(session, it) }
    }

    private fun sendScanResult(rawValue: String, requestId: String) {
        val session = featureSession() ?: return
        runCatching {
            session.sendJson(
                ProtocolV3.TYPE_SCAN_RESULT,
                JSONObject()
                    .put("requestId", requestId)
                    .put("rawValue", rawValue)
                    .put("format", "QR_CODE")
                    .put("valueType", if (rawValue.startsWith("http://") || rawValue.startsWith("https://")) "url" else "text"),
            )
        }.onFailure { handleFeatureFailure(session, it) }
    }

    private fun featureSession(requiredCapability: String? = null): ProtocolV3Client.Session? {
        val session = FeatureSessionRegistry.control
        if (session != null) {
            if (requiredCapability == null || requiredCapability in session.remoteCapabilities) {
                return session
            }
            toast("电脑端尚未启用 $requiredCapability 能力")
            return null
        }
        status.text = "正在连接 protocol 3 功能通道…"
        FeatureSessionRegistry.ensureConnected(this) {
            runOnUiThread { status.text = "功能连接失败：$it" }
        }
        toast("正在连接电脑，请稍后再操作")
        return null
    }

    private fun handleFeatureFailure(session: ProtocolV3Client.Session, failure: Throwable) {
        FeatureSessionRegistry.invalidate(session)
        runOnUiThread {
            status.text = "功能连接已断开，正在重连…"
            FeatureSessionRegistry.ensureConnected(this) {
                runOnUiThread { status.text = "功能连接失败：$it" }
            }
        }
    }

    private fun launchScan(mode: String, requestId: String) {
        pendingScanRequestId = requestId
        if (mode == "gallery") {
            galleryScanner.launch(Intent(this, GalleryScanActivity::class.java))
        } else {
            cameraScanner.launch(Intent(this, ScanActivity::class.java))
        }
    }

    private fun handleFeatureIntent(intent: Intent?) {
        if (intent?.action != ACTION_FEATURE_REQUEST) return
        val feature = intent.getStringExtra(EXTRA_FEATURE).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            ?.takeIf { it.isNotBlank() }
            ?: ProtocolV3.newRequestId()
        val mode = intent.getStringExtra(EXTRA_FEATURE_MODE).orEmpty()
        intent.action = null
        when (feature) {
            "scan" -> launchScan(mode, requestId)
            "capture" -> startActivity(
                Intent(this, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_REQUEST_ID, requestId)
                    .putExtra(CaptureActivity.EXTRA_MODE, mode),
            )
        }
    }

    private fun rememberUri(uri: Uri) {
        selectedUris[uri.toString()] = uri
    }

    private fun displayName(uri: Uri): String = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else uri.lastPathSegment.orEmpty()
    }.orEmpty().ifBlank { uri.lastPathSegment ?: "未命名文件" }

    private fun toggleFilePanel() {
        filePanel.visibility = if (filePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showShortcutEditor() {
        val nameInput = EditText(this).apply {
            hint = "名称，例如：切换标签页"
            isSingleLine = true
        }
        val keyInput = EditText(this).apply {
            hint = "主键，例如：TAB、C、F5"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val modifierInput = EditText(this).apply {
            hint = "修饰键（逗号分隔）：CTRL、ALT、SHIFT、META"
            isSingleLine = true
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(nameInput)
            addView(keyInput)
            addView(modifierInput)
        }
        AlertDialog.Builder(this)
            .setTitle("添加自定义快捷键")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val key = keyInput.text.toString().trim().uppercase()
                if (key.isBlank()) {
                    toast("请输入主键")
                    return@setPositiveButton
                }
                val name = nameInput.text.toString().trim().ifBlank { key }
                val modifiers = modifierInput.text.toString()
                    .split(',', '，', ' ', '\n', '\t')
                    .map { it.trim().uppercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(4)
                val updated = (listOf(
                    ShortcutCommand(
                        id = ProtocolV3.newRequestId(),
                        name = name,
                        modifiers = modifiers,
                        key = key,
                    ),
                ) + shortcutStore.list()).take(12)
                shortcutStore.save(updated)
                recreate()
            }
            .show()
    }

    private fun cycleTheme() {
        val next = when (themeStore.get()) {
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.EXTREME_DARK
            ThemeMode.EXTREME_DARK -> ThemeMode.LIGHT
        }
        themeStore.set(next)
        recreate()
    }

    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = safe.left + dp(14), top = safe.top + dp(10), right = safe.right + dp(14), bottom = safe.bottom + dp(14))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun actionButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setTextSize(12f)
        minHeight = dp(42)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(12)
        backgroundTintList = ColorStateList.valueOf(
            if (themeStore.get() == ThemeMode.LIGHT) 0xFFE7F0FA.toInt() else 0xFF1B3455.toInt(),
        )
        setTextColor(primaryTextColor(themeStore.get()))
        setOnClickListener { action() }
    }

    private fun pageColor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> ContextCompat.getColor(this, R.color.background)
        ThemeMode.DARK -> 0xFF08142A.toInt()
        ThemeMode.EXTREME_DARK -> Color.BLACK
    }

    private fun surfaceColor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> ContextCompat.getColor(this, R.color.surface)
        ThemeMode.DARK -> 0xFF10223D.toInt()
        ThemeMode.EXTREME_DARK -> Color.BLACK
    }

    private fun primaryTextColor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> ContextCompat.getColor(this, R.color.text_primary)
        ThemeMode.DARK, ThemeMode.EXTREME_DARK -> 0xFFF2F6FC.toInt()
    }

    private fun secondaryTextColor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> ContextCompat.getColor(this, R.color.text_secondary)
        ThemeMode.DARK, ThemeMode.EXTREME_DARK -> 0xFFAFC0D8.toInt()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0xFF315377.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val ROOT_ID = 0x56535040
        const val ACTION_FEATURE_REQUEST = "com.voicespreader.remote.action.FEATURE_REQUEST"
        const val EXTRA_FEATURE = "feature"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_FEATURE_MODE = "feature_mode"
    }
}
