package dev.buildit.studio

import dev.buildit.feature.blocks.PALETTE_TAG
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onLast
import dev.buildit.feature.designer.LIBRARY_TAG
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end flow through the real app shell: dashboard -> create project -> designer opens,
 * a component is added and saved to the real layout file -> project settings and files tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(name: String) {
        compose.onAllNodes(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).onFirst().performClick()
        compose.waitForIdle()
    }

    /** Opens a project tool from the drawer. */
    private fun tool(name: String) {
        compose.onNodeWithContentDescription("Project tools").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(name) and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
    }

    @Test fun createProjectFromDashboardOpensWorkspace() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Start your first app").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("New project").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("App name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("App name") and hasSetTextAction()).onFirst().performTextInput("Hello World")
        compose.onNodeWithText("com.example.helloworld").assertIsDisplayed()
        compose.onNodeWithText("Create").performScrollTo().performClick()
        // The workspace opens on the Design tab with the launcher activity's layout.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("activity_main.xml ▾").fetchSemanticsNodes().isNotEmpty() }

        val workspace = File(compose.activity.filesDir, "workspace/hello-world")
        assertTrue(File(workspace, "buildit.json").isFile)
        assertTrue(File(workspace, "app/src/main/java/com/example/helloworld/MainActivity.java").isFile)

        // Add a component from the library and save: the layout file on disk must change.
        val layout = File(workspace, "app/src/main/res/layout/activity_main.xml")
        // Phone layout: the widget palette sits beside the canvas.
        compose.onNodeWithTag(LIBRARY_TAG).performScrollToNode(hasText("Button"))
        compose.onAllNodesWithText("Button").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(10_000) { layout.readText().contains("<Button") }
        assertTrue(layout.readText(), layout.readText().contains("android:id=\"@+id/button"))

        // Events tab: open the "screen is created" event in the block editor, add a block and save.
        tab("Events")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("when screen is created").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("when screen is created").performClick()
        // Extended buttons merge their label away, so look in the unmerged tree.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Show blocks", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show blocks", useUnmergedTree = true).performClick()
        compose.onNodeWithText("App").performScrollTo().performClick()
        compose.onNodeWithTag(PALETTE_TAG).performScrollToNode(hasText("close this screen"))
        compose.onNodeWithText("close this screen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(10_000) { File(workspace, ".buildit/blocks/MainActivity.json").let { it.isFile && it.readText().contains("app_finish") } }
        compose.onNodeWithContentDescription("Back to events").performClick()

        // Components tab: add a timer; it is saved with the screen's blocks.
        tab("Components")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No components yet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Add component").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Timer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Timer").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("timer1").fetchSemanticsNodes().isNotEmpty() }
        // Leaving the tab saves (autosave is on by default).
        tab("Design")
        compose.waitUntil(10_000) { File(workspace, ".buildit/blocks/MainActivity.json").readText().contains("\"timer1\"") }

        // The run bar shows the screen being edited.
        compose.onNodeWithText("Run").assertExists()

        tool("Project")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Application ID").fetchSemanticsNodes().isNotEmpty() }
        tool("Images")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Import", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tool("Files")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("buildit.json").fetchSemanticsNodes().isNotEmpty() }

        // A text file opens in the Code tab with its own editor tab.
        compose.onNodeWithText("settings.gradle.kts").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close settings.gradle.kts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kotlin").assertExists()
        compose.onNodeWithText("Go to line").assertExists()

        // Build tab: no platform is installed in a fresh app, so it offers the official download.
        tool("Build")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Download platform").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Build debug APK").assertIsNotEnabled()

        // Console tab: nothing has run yet, so it explains how to connect a debug build.
        tool("Console")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Install and open a debug build", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // AI tab: no key yet, so it opens the provider setup with Claude selected and Save disabled.
        tool("AI")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Enter an API key.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText("claude-opus-5-5").fetchSemanticsNodes().isNotEmpty())
    }
}
