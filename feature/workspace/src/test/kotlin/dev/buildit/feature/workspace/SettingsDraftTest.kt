package dev.buildit.feature.workspace

import dev.buildit.engine.project.AppConfig
import dev.buildit.engine.project.LibraryDependency
import dev.buildit.engine.project.ProjectManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {
    private val manifest = ProjectManifest(
        id = "p", name = "App", createdAt = 0, updatedAt = 0,
        app = AppConfig(
            "com.example.app",
            dependencies = listOf(LibraryDependency("com.squareup.okhttp3:okhttp:4.12.0")),
            permissions = listOf("android.permission.INTERNET"),
            appCompat = true,
        ),
    )

    @Test fun savingSettingsKeepsLibrariesAndAppCompat() {
        val draft = SettingsDraft.from(manifest).copy(versionName = "2.0", permissions = listOf("android.permission.CAMERA", "android.permission.INTERNET", "android.permission.CAMERA"))
        val app = draft.toManifest(manifest)!!.app
        assertEquals("2.0", app.versionName)
        assertEquals(manifest.app.dependencies, app.dependencies)
        assertTrue(app.appCompat)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.INTERNET"), app.permissions)
    }

    @Test fun permissionNames() {
        assertTrue(SettingsDraft.isPermissionName("android.permission.NFC"))
        assertTrue(SettingsDraft.isPermissionName("com.example.permission.C2D"))
        assertFalse(SettingsDraft.isPermissionName("NFC"))
        assertFalse(SettingsDraft.isPermissionName("android.permission.NFC\"/><x"))
    }
}
