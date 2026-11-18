package dev.buildit.engine.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectValidationTest {
    @Test fun validPackageNames() {
        listOf("com.example.app", "a.b", "id.co.my_app2").forEach {
            assertTrue(it, ProjectValidation.validatePackageName(it).isEmpty())
        }
    }

    @Test fun invalidPackageNames() {
        assertEquals("too_few_segments", ProjectValidation.validatePackageName("app").single().code)
        assertEquals("empty_segment", ProjectValidation.validatePackageName("com..app").single().code)
        assertEquals("invalid_segment", ProjectValidation.validatePackageName("com.1app").single().code)
        assertEquals("keyword", ProjectValidation.validatePackageName("com.new.app").single().code)
        assertEquals("empty", ProjectValidation.validatePackageName(" ").single().code)
    }

    @Test fun names() {
        assertTrue(ProjectValidation.validateName("Hello").isEmpty())
        assertEquals("empty", ProjectValidation.validateName("  ").single().code)
        assertEquals("invalid_chars", ProjectValidation.validateName("a/b").single().code)
        assertEquals("too_long", ProjectValidation.validateName("x".repeat(51)).single().code)
    }

    @Test fun sdkRules() {
        val ok = AppConfig(packageName = "a.b")
        assertTrue(ProjectValidation.validateSdk(ok).isEmpty())
        assertEquals("above_target", ProjectValidation.validateSdk(ok.copy(minSdk = 30, targetSdk = 29)).single().code)
        assertEquals("above_compile", ProjectValidation.validateSdk(ok.copy(targetSdk = 37)).single().code)
    }

    @Test fun suggestSegment() {
        assertEquals("mycoolapp", ProjectValidation.suggestPackageSegment("My Cool App!"))
        assertEquals("app", ProjectValidation.suggestPackageSegment("123"))
        assertEquals("newapp", ProjectValidation.suggestPackageSegment("New"))
    }
}
