package dev.buildit.engine.deps

import org.junit.Assert.assertTrue
import org.junit.Test

/** Searches the real Google Maven index (network). */
class LibrarySearchTest {
    @Test fun findsAndroidXAndMaterialInGoogleMaven() {
        val search = LibrarySearch()
        val errors = mutableListOf<String>()
        val appcompat = search.search("appcompat", errors = errors)
        assertTrue("$errors $appcompat", appcompat.any { it.module == "androidx.appcompat:appcompat" && it.latest != null })
        val material = search.search("material", errors = errors)
        assertTrue("$errors $material", material.any { it.module == "com.google.android.material:material" })
        val exact = search.search("com.squareup.okhttp3:okhttp:4.12.0")
        assertTrue(exact.first().module == "com.squareup.okhttp3:okhttp")
        assertTrue(search.googleGroup("androidx.core")["core"].orEmpty().contains("1.13.0"))
    }
}
