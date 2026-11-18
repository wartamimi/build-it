package dev.buildit.studio

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import dev.buildit.feature.blocks.PALETTE_TAG
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The real app on a device: create a project, open every workspace tab, use the block editor. */
@RunWith(AndroidJUnit4::class)
class UiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everyWorkspaceTabOpensAndBlocksCanBeEdited() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("New project").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("New project").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("App name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val name = "Ui Check ${System.currentTimeMillis() % 100000}"
        compose.onAllNodes(hasText("App name") and hasSetTextAction()).onFirst().performTextInput(name)
        compose.onNodeWithText("Create").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("activity_main.xml ▾").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodes(hasText("Events") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).onFirst().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("when screen is created").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("when screen is created").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Show blocks", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show blocks", useUnmergedTree = true).performClick()
        compose.onNodeWithText("App").performScrollTo().performClick()
        compose.onNodeWithTag(PALETTE_TAG).performScrollToNode(hasText("close this screen"))
        compose.onNodeWithText("close this screen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Save").performClick()
        val dir = File(compose.activity.filesDir, "workspace").listFiles().orEmpty().first { File(it, "buildit.json").readText().contains(name) }
        compose.waitUntil(10_000) { File(dir, ".buildit/blocks/MainActivity.json").let { it.isFile && it.readText().contains("app_finish") } }
        compose.onNodeWithContentDescription("Back to events").performClick()

        for (tab in listOf("Components", "Design")) {
            compose.onAllNodes(hasText(tab) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).onFirst().performClick()
            compose.waitForIdle()
        }
        for (tool in listOf("Code", "Libraries", "Images", "Sounds", "Fonts", "Build", "Console", "AI", "Project", "Files")) {
            compose.onNodeWithContentDescription("Project tools").performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasText(tool) and hasClickAction()).onLast().performClick()
            compose.waitForIdle()
        }
        assertTrue(dir.isDirectory)
    }
}
