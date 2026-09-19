package com.voicespreader.remote

/** 可配置的三指、四指手势。顺序也决定设置页中的展示顺序。 */
enum class TouchpadGesture(
    val id: String,
    val label: String,
    val fingerCount: Int,
) {
    THREE_TAP("three_tap", "三指点按", 3),
    THREE_DOUBLE_TAP("three_double_tap", "三指双击", 3),
    THREE_SWIPE_UP("three_swipe_up", "三指上滑", 3),
    THREE_SWIPE_DOWN("three_swipe_down", "三指下滑", 3),
    THREE_SWIPE_LEFT("three_swipe_left", "三指左滑", 3),
    THREE_SWIPE_RIGHT("three_swipe_right", "三指右滑", 3),
    FOUR_TAP("four_tap", "四指点按", 4),
    FOUR_DOUBLE_TAP("four_double_tap", "四指双击", 4),
    FOUR_SWIPE_UP("four_swipe_up", "四指上滑", 4),
    FOUR_SWIPE_DOWN("four_swipe_down", "四指下滑", 4),
    FOUR_SWIPE_LEFT("four_swipe_left", "四指左滑", 4),
    FOUR_SWIPE_RIGHT("four_swipe_right", "四指右滑", 4),
}

/** 手势可触发的动作目录；动作 ID 是本地设置和协议之间的稳定值。 */
data class TouchpadActionOption(
    val id: String,
    val label: String,
)

object TouchpadActionCatalog {
    const val NONE = "none"
    const val LEFT_CLICK = "mouse.left_click"
    const val DOUBLE_LEFT_CLICK = "mouse.double_left_click"
    const val RIGHT_CLICK = "mouse.right_click"
    const val MIDDLE_CLICK = "mouse.middle_click"
    const val SCROLL_UP = "scroll.up"
    const val SCROLL_DOWN = "scroll.down"
    const val SCROLL_LEFT = "scroll.left"
    const val SCROLL_RIGHT = "scroll.right"
    const val ZOOM_IN = "zoom.in"
    const val ZOOM_OUT = "zoom.out"

    private const val SHORTCUT_PREFIX = "shortcut:"
    private const val APP_PREFIX = "app:"

    fun shortcutAction(id: String): String = SHORTCUT_PREFIX + id

    fun appAction(id: String): String = APP_PREFIX + id

    fun shortcutId(actionId: String): String? =
        actionId.takeIf { it.startsWith(SHORTCUT_PREFIX) }
            ?.removePrefix(SHORTCUT_PREFIX)
            ?.takeIf { it.isNotBlank() }

    fun appId(actionId: String): String? =
        actionId.takeIf { it.startsWith(APP_PREFIX) }
            ?.removePrefix(APP_PREFIX)
            ?.takeIf { it.isNotBlank() }

    fun defaultBindings(): Map<String, String> = mapOf(
        TouchpadGesture.THREE_TAP.id to MIDDLE_CLICK,
        TouchpadGesture.FOUR_DOUBLE_TAP.id to DOUBLE_LEFT_CLICK,
    )

    fun options(
        shortcuts: Collection<ShortcutCommand>,
        apps: Collection<FeatureSessionRegistry.RemoteAppShortcut>,
    ): List<TouchpadActionOption> = buildList {
        add(TouchpadActionOption(NONE, "不执行操作"))
        add(TouchpadActionOption(LEFT_CLICK, "鼠标左键单击"))
        add(TouchpadActionOption(DOUBLE_LEFT_CLICK, "鼠标左键双击"))
        add(TouchpadActionOption(RIGHT_CLICK, "鼠标右键单击"))
        add(TouchpadActionOption(MIDDLE_CLICK, "鼠标中键单击"))
        add(TouchpadActionOption(SCROLL_UP, "向上滚动"))
        add(TouchpadActionOption(SCROLL_DOWN, "向下滚动"))
        add(TouchpadActionOption(SCROLL_LEFT, "向左滚动"))
        add(TouchpadActionOption(SCROLL_RIGHT, "向右滚动"))
        add(TouchpadActionOption(ZOOM_IN, "放大"))
        add(TouchpadActionOption(ZOOM_OUT, "缩小"))
        shortcuts
            .filter { it.id.isNotBlank() && it.key.isNotBlank() }
            .distinctBy { it.id }
            .forEach { command ->
                add(
                    TouchpadActionOption(
                        shortcutAction(command.id),
                        "快捷键：${command.name}（${command.displayText()}）",
                    ),
                )
            }
        apps
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .forEach { app ->
                add(TouchpadActionOption(appAction(app.id), "启动应用：${app.name}"))
            }
    }

    fun labelFor(
        actionId: String,
        shortcuts: Collection<ShortcutCommand> = emptyList(),
        apps: Collection<FeatureSessionRegistry.RemoteAppShortcut> = emptyList(),
    ): String = options(shortcuts, apps).firstOrNull { it.id == actionId }?.label
        ?: if (actionId.isBlank()) "不执行操作" else "未知动作（$actionId）"
}

fun ShortcutCommand.displayText(): String = buildList {
    addAll(modifiers.map { it.uppercase() })
    add(key.uppercase())
}.joinToString("+")
