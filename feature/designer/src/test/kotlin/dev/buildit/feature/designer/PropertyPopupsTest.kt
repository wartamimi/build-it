package dev.buildit.feature.designer

import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.PropertyDef
import dev.buildit.engine.layout.PropertyGroup
import dev.buildit.engine.layout.PropertyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropertyPopupsTest {
    @Test fun dimensionsSplitIntoNumberAndUnit() {
        assertEquals("16" to "dp", splitDimension("16dp"))
        assertEquals("1.5" to "sp", splitDimension("1.5sp"))
        assertEquals("8" to "dp", splitDimension("8"))
        assertNull(splitDimension("@dimen/margin"))
        assertNull(splitDimension(null))
    }

    @Test fun cardsShowShortValues() {
        val id = PropertyDef("android:id", PropertyType.Id, PropertyGroup.Identity)
        val size = PropertyDef("android:layout_width", PropertyType.LayoutSize, PropertyGroup.Layout)
        assertEquals("title", cardValue(id, "@+id/title"))
        assertEquals("match", cardValue(size, "match_parent"))
        assertEquals("wrap", cardValue(size, "wrap_content"))
        assertEquals("—", cardValue(size, null))
    }

    @Test fun basicAndRecentTabs() {
        val all = ComponentCatalog.propertiesFor("TextView")
        val basic = propertiesForTab(all, PropertyTab.Basic, emptyList()).map { it.attr }
        assertEquals(listOf("android:id", "android:layout_width", "android:layout_height"), basic.take(3))
        assert("android:text" in basic)
        val recent = propertiesForTab(all, PropertyTab.Recent, listOf("android:textColor", "android:not_a_property", "android:id")).map { it.attr }
        assertEquals(listOf("android:textColor", "android:id"), recent)
        assertEquals(all, propertiesForTab(all, PropertyTab.All, emptyList()))
    }
}
