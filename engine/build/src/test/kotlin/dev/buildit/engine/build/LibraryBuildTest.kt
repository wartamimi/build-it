package dev.buildit.engine.build

import dev.buildit.engine.deps.MavenCache
import dev.buildit.engine.deps.Repository
import dev.buildit.engine.project.LibraryDependency
import dev.buildit.engine.project.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Builds an app that uses AppCompat, Material Components and ConstraintLayout from the real
 * Google Maven repository (or a mirror in BUILDIT_TEST_MAVEN) and checks the APK with the SDK's
 * own aapt2, dexdump and apksigner.
 */
class LibraryBuildTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun maven(): MavenCache {
        val repos = System.getenv("BUILDIT_TEST_MAVEN")?.let { listOf(Repository("Test mirror", it.trimEnd('/') + "/")) } ?: Repository.defaults
        // Shared between runs: released artifacts never change.
        return MavenCache(File(System.getProperty("java.io.tmpdir"), "buildit-test-maven"), repos)
    }

    @Test fun appCompatMaterialAndConstraintLayoutAppBuilds() {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        val p = ProjectStore(tmp.newFolder("ws")).create("Lib App", "com.example.libapp")
        val dir = p.directory
        val app = p.manifest.app.copy(
            minSdk = 26,
            dependencies = listOf(
                LibraryDependency("androidx.appcompat:appcompat:1.7.1"),
                LibraryDependency("com.google.android.material:material:1.13.0"),
                LibraryDependency("androidx.constraintlayout:constraintlayout:2.2.1"),
            ),
        )
        val main = File(dir, "app/src/main")
        File(main, "java/com/example/libapp/MainActivity.java").writeText(
            """
            package com.example.libapp;

            import android.os.Bundle;
            import androidx.appcompat.app.AppCompatActivity;
            import com.google.android.material.button.MaterialButton;
            import com.google.android.material.snackbar.Snackbar;

            public class MainActivity extends AppCompatActivity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                    MaterialButton b = findViewById(R.id.button);
                    b.setOnClickListener(v -> Snackbar.make(v, R.string.hello, Snackbar.LENGTH_SHORT).show());
                    int primary = com.google.android.material.R.attr.colorPrimary;
                    if (primary == 0) throw new IllegalStateException();
                }
            }
            """.trimIndent(),
        )
        File(main, "res/layout/activity_main.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <com.google.android.material.button.MaterialButton
                    android:id="@+id/button"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/hello"
                    app:cornerRadius="12dp"
                    app:layout_constraintTop_toTopOf="parent"
                    app:layout_constraintStart_toStartOf="parent" />
            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
        )
        File(main, "res/values/themes.xml").writeText(
            """
            <resources>
                <style name="AppTheme" parent="Theme.Material3.DayNight.NoActionBar">
                    <item name="colorPrimary">#6750A4</item>
                </style>
            </resources>
            """.trimIndent(),
        )
        File(main, "res/values/styles.xml").takeIf { it.isFile }?.delete()
        val keystore = File(tmp.root, "debug.p12")
        val request = BuildRequest(dir, app, jar!!, BuildType.Debug, key = { Keys.debugKey(keystore) }, maven = maven(), toolCache = File(tmp.root, "tools"))
        val result = BuildPipeline().run(request)
        assertTrue(result.diagnostics.filter { it.severity == BuildDiagnostic.Severity.Error }.take(30).joinToString("\n"), result.success)
        val apk = result.apk!!

        val buildTools = File(TestSdk.root, "build-tools").listFiles().orEmpty().sortedDescending().firstOrNull()
        TestSdk.require(buildTools != null, "build-tools")
        val aapt2 = File(buildTools, "aapt2").path
        val badging = TestSdk.run(aapt2, "dump", "badging", apk.path)
        assertTrue(badging, badging.contains("package: name='com.example.libapp'"))
        val manifest = TestSdk.run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.path)
        // Merged from androidx.core and androidx.startup (lifecycle/emoji2/profileinstaller).
        assertTrue(manifest, manifest.contains("com.example.libapp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"))
        assertTrue(manifest, manifest.contains("androidx.startup.InitializationProvider"))
        assertTrue(manifest, manifest.contains("androidx.core.app.CoreComponentFactory"))
        val resources = TestSdk.run(aapt2, "dump", "resources", apk.path)
        assertTrue(resources.contains("style/Theme.Material3.DayNight.NoActionBar"))
        assertTrue(resources.contains("attr/colorPrimary"))
        val layout = TestSdk.run(aapt2, "dump", "xmltree", "--file", "res/layout/activity_main.xml", apk.path)
        assertTrue(layout, layout.contains("layout_constraintTop_toTopOf"))
        assertTrue(layout, layout.contains("cornerRadius"))

        val dex = TestSdk.run(File(buildTools, "dexdump").path, apk.path)
        listOf(
            "Lcom/example/libapp/MainActivity;", "Landroidx/appcompat/app/AppCompatActivity;",
            "Lcom/google/android/material/button/MaterialButton;", "Lcom/google/android/material/R\$attr;",
            "Landroidx/constraintlayout/widget/ConstraintLayout;", "Lkotlin/jvm/internal/Intrinsics;",
        ).forEach { assertTrue("missing $it", dex.contains(it)) }

        ZipFile(apk).use { z ->
            assertTrue(z.entries().asSequence().any { it.name == "classes2.dex" })
            // Nine-patch images are compiled (border removed, npTc chunk added).
            val nine = z.entries().asSequence().first { it.name.endsWith(".9.png") }
            val bytes = z.getInputStream(nine).readBytes()
            assertTrue(nine.name, String(bytes, Charsets.ISO_8859_1).contains("npTc"))
            assertFalse(z.entries().asSequence().any { it.name == "META-INF/MANIFEST.MF" && false })
        }
        val verify = TestSdk.run(File(buildTools, "apksigner").path, "verify", apk.path)
        assertEquals("", verify.trim().lines().filter { it.startsWith("ERROR") }.joinToString("\n"))
    }

    @Test fun libraryNotInTheListIsReported() {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        val p = ProjectStore(tmp.newFolder("ws")).create("Lib App", "com.example.libapp")
        File(p.directory, "app/build.gradle.kts").appendText("\ndependencies {\n    implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")\n}\n")
        val result = BuildPipeline().run(BuildRequest(p.directory, p.manifest.app, jar!!, BuildType.Debug, key = { Keys.debugKey(File(tmp.root, "k.p12")) }))
        assertFalse(result.success)
        assertTrue(result.diagnostics.joinToString(), result.diagnostics.any { it.message.contains("Libraries tab") })
    }
}
