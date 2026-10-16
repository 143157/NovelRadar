package com.novelradar.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsPrimaryNavigation() {
        val labels = listOf("书架", "搜书", "下载", "书源")
        val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

        labels.forEach { label ->
            compose.onNode(hasText(label) and selectable)
                .assertExists()
                .assertHasClickAction()
                .performClick()
                .assertIsSelected()

            labels.filterNot { it == label }.forEach { otherLabel ->
                compose.onNode(hasText(otherLabel) and selectable).assertIsNotSelected()
            }
        }
    }
}
