package dev.buildit.studio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end flow through the real app shell: dashboard -> create project -> workspace opens
 * with the files actually written to the workspace folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createProjectFromDashboardOpensWorkspace() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Start your first app").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("New project").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("App name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("App name") and hasSetTextAction()).onFirst().performTextInput("Hello World")
        compose.onNodeWithText("com.example.helloworld").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Application ID").fetchSemanticsNodes().isNotEmpty() }

        val workspace = File(compose.activity.filesDir, "workspace/hello-world")
        assertTrue(File(workspace, "buildit.json").isFile)
        assertTrue(File(workspace, "app/src/main/java/com/example/helloworld/MainActivity.java").isFile)

        compose.onNodeWithText("Files").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("buildit.json").fetchSemanticsNodes().isNotEmpty() }
    }
}
