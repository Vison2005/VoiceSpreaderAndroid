package com.voicespreader.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadGestureTest {
    @Test
    fun exposesAllThreeAndFourFingerGestures() {
        assertEquals(12, TouchpadGesture.entries.size)
        assertEquals(6, TouchpadGesture.entries.count { it.fingerCount == 3 })
        assertEquals(6, TouchpadGesture.entries.count { it.fingerCount == 4 })
    }

    @Test
    fun actionCatalogContainsBuiltInsShortcutsAndApps() {
        val shortcut = ShortcutCommand("tab", "切换标签页", listOf("CTRL"), "TAB")
        val app = FeatureSessionRegistry.RemoteAppShortcut("browser", "浏览器")
        val options = TouchpadActionCatalog.options(listOf(shortcut), listOf(app))
        val ids = options.map(TouchpadActionOption::id)

        assertTrue(ids.contains(TouchpadActionCatalog.NONE))
        assertTrue(ids.contains(TouchpadActionCatalog.DOUBLE_LEFT_CLICK))
        assertTrue(ids.contains(TouchpadActionCatalog.shortcutAction("tab")))
        assertTrue(ids.contains(TouchpadActionCatalog.appAction("browser")))
    }

    @Test
    fun defaultsKeepThreeFingerTapUsefulAndOtherGesturesSafe() {
        val defaults = TouchpadActionCatalog.defaultBindings()

        assertEquals(
            TouchpadActionCatalog.MIDDLE_CLICK,
            defaults[TouchpadGesture.THREE_TAP.id],
        )
        assertEquals(
            TouchpadActionCatalog.DOUBLE_LEFT_CLICK,
            defaults[TouchpadGesture.FOUR_DOUBLE_TAP.id],
        )
        assertTrue(defaults[TouchpadGesture.THREE_SWIPE_LEFT.id].isNullOrBlank())
    }
}
