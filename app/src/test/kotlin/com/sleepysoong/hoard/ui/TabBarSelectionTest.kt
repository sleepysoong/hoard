package com.sleepysoong.hoard.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
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
        override fun before() = HoardRepository.resetForTests()
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
