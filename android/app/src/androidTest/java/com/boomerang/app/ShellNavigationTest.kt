package com.boomerang.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fourDestinationsAreReachable() {
        listOf("LIBRARY" to "你的镖库", "AI" to "AI 助手", "PROFILE" to "我的空间", "HOME" to "回旋镖").forEach { (tag, title) ->
            compose.onNodeWithTag("nav_$tag").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test fun editorDraftSurvivesBackAndRecreation() {
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithTag("quote_input").performTextInput("年底读完十二本书")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("年底读完十二本书").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("你的镖库").assertIsDisplayed()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithText("年底读完十二本书").assertIsDisplayed()
    }

    @Test fun selectedDestinationSurvivesRecreation() {
        compose.onNodeWithTag("nav_AI").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("AI 助手").assertIsDisplayed()
    }

    @Test fun offlineCreateEditAndDelete() {
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithTag("quote_input").performTextInput("离线闭环 ${System.nanoTime()}")
        compose.onNodeWithTag("save_record").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("edit_record").performClick()
        compose.onNodeWithTag("quote_input").performTextClearance()
        compose.onNodeWithTag("quote_input").performTextInput("修改后的离线原话")
        compose.onNodeWithTag("save_record").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_original").assertTextEquals("修改后的离线原话")
        compose.onNodeWithTag("delete_record").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("search")).fetchSemanticsNodes().isNotEmpty() }
    }
}
