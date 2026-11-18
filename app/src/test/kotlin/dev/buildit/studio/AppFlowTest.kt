package dev.buildit.studio

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

    @Test fun createProjectFromDashboardOpensWorkspace() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Start your first app").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("New project").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("App name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("App name") and hasSetTextAction()).onFirst().performTextInput("Hello World")
        compose.onNodeWithText("com.example.helloworld").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        // The workspace opens on the Design tab with the launcher activity's layout.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("activity_main.xml ▾").fetchSemanticsNodes().isNotEmpty() }

        val workspace = File(compose.activity.filesDir, "workspace/hello-world")
        assertTrue(File(workspace, "buildit.json").isFile)
        assertTrue(File(workspace, "app/src/main/java/com/example/helloworld/MainActivity.java").isFile)

        // Add a component from the library and save: the layout file on disk must change.
        val layout = File(workspace, "app/src/main/res/layout/activity_main.xml")
        compose.onAllNodes(hasText("Search components") and hasSetTextAction()).onFirst().performTextInput("Butt")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(10_000) { layout.readText().contains("<Button") }
        assertTrue(layout.readText(), layout.readText().contains("android:id=\"@+id/button"))

        compose.onNodeWithText("Project").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Application ID").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Files").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("buildit.json").fetchSemanticsNodes().isNotEmpty() }
    }
}
