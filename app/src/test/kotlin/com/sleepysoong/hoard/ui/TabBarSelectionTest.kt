package com.sleepysoong.hoard.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The highlighted tab in the floating bar must always be the screen on show:
 * at launch, after tapping tabs, after system Back, and after leaving a chat.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TabBarSelectionTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() { HoardRepository.resetForTests(); com.sleepysoong.hoard.testing.TestData.seed(greeting = true); com.sleepysoong.hoard.testing.TestData.useMockEngine() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun selectedTab(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        .let { compose.onNodeWithTag("tab-$title").assert(it) }

    private fun notSelected(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        .let { compose.onNodeWithTag("tab-$title").assert(it) }

    private fun onlySelected(title: String) {
        compose.waitForIdle()
        listOf("세션", "도구", "설정").forEach { if (it == title) selectedTab(it) else notSelected(it) }
    }

    private fun back() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    @Test fun launchHighlightsSessions() = onlySelected("세션")

    @Test fun tappingTabsMovesHighlight() {
        compose.onNodeWithTag("tab-도구").performClick(); onlySelected("도구")
        compose.onNodeWithTag("tab-설정").performClick(); onlySelected("설정")
        compose.onNodeWithTag("tab-세션").performClick(); onlySelected("세션")
    }

    @Test fun systemBackKeepsHighlightInSync() {
        compose.onNodeWithTag("tab-도구").performClick()
        onlySelected("도구")
        back()
        onlySelected("세션")
    }

    @Test fun leavingChatHighlightsSessions() {
        compose.onNodeWithTag("tab-설정").performClick()
        compose.onNodeWithTag("tab-세션").performClick()
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("세션 목록으로").performClick()
        onlySelected("세션")
        back()
        compose.waitForIdle()
    }

    /** Background replies are always on (product intent): Settings offers no switch. */
    @Test fun settingsHasNoBackgroundReplyToggle() {
        compose.onNodeWithTag("tab-설정").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("기본 컨텍스트").assertExists()
        compose.onAllNodesWithText("백그라운드", substring = true).assertCountEquals(0)
    }
}

/** Liquid capsule motion in the real app: glides to the new tab, stretching while it travels. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TabCapsuleMotionTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() { HoardRepository.resetForTests(); com.sleepysoong.hoard.testing.TestData.seed(greeting = true); com.sleepysoong.hoard.testing.TestData.useMockEngine() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capsuleStretchesWhileMovingAndSettlesRound() {
        compose.waitForIdle()
        val capsule = { compose.onNodeWithTag("tab-capsule").fetchSemanticsNode().let { it.boundsInRoot.width / it.size.width } }
        val restW = capsule()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("tab-설정").performClick()
        val widths = (1..45).map { compose.mainClock.advanceTimeByFrame(); capsule() }
        println("capsule scaleX: " + widths.joinToString { "%.3f".format(it) })
        assertTrue("stretches in flight: max=${widths.max()}", widths.max() > restW + 0.05f)
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals("round again at rest", restW, capsule(), 0.005f)
    }
}

/** 세션 · 도구 · 설정 share one floating top bar: same component, same place, same size. */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TopBarConsistencyTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() { HoardRepository.resetForTests(); com.sleepysoong.hoard.testing.TestData.seed(greeting = true); com.sleepysoong.hoard.testing.TestData.useMockEngine() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun bar(tab: String): androidx.compose.ui.geometry.Rect {
        if (tab != "세션") compose.onNodeWithTag("tab-$tab").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "topbar").apply { mkdirs() }
            .let { java.io.File(it, "$tab.png") }.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val nodes = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("floating-bar")).fetchSemanticsNodes()
        assertEquals("$tab: exactly one floating bar", 1, nodes.size)
        return nodes.single().boundsInRoot
    }

    @Test fun sameBarOnEveryTab() {
        val sessions = bar("세션")
        val tools = bar("도구")
        compose.onAllNodes(androidx.compose.ui.test.hasText("모델이 호출하는 기기 도구")).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        val settings = bar("설정")
        for ((name, r) in listOf("도구" to tools, "설정" to settings)) {
            assertEquals("$name bar top", sessions.top, r.top, 1f)
            assertEquals("$name bar height", sessions.height, r.height, 1f)
            assertEquals("$name bar width", sessions.width, r.width, 1f)
        }
    }

    @Test fun settingsBarStaysWhileContentScrolls() {
        compose.onNodeWithTag("tab-설정").performClick()
        compose.waitForIdle()
        val before = compose.onNode(androidx.compose.ui.test.hasTestTag("floating-bar")).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("정보").performScrollTo()
        compose.waitForIdle()
        val after = compose.onNode(androidx.compose.ui.test.hasTestTag("floating-bar")).fetchSemanticsNode().boundsInRoot
        assertEquals("bar is fixed", before.top, after.top, 0.5f)
    }
}
