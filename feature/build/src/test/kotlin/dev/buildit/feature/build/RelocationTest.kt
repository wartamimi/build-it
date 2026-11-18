package dev.buildit.feature.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelocationTest {
    @Test fun renamesKeepLengthAndPointAtTheStandIns() {
        assertEquals(4, Relocation.renames.size)
        // A class constant-pool fragment that names JDK classes Android lacks.
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) +
            "Ljava/lang/management/ThreadMXBean;java/beans/Introspector;javax/xml/stream/XMLStreamReader;java/beans/PropertyChangeListener".toByteArray()
        val text = String(Relocation.patch(bytes), Charsets.ISO_8859_1)
        assertEquals(bytes.size, text.length)
        assertTrue(text.contains("Ljvbuildit/management/ThreadMXBean;"))
        assertTrue(text.contains("jvbuilditb/Introspector;"))
        assertTrue(text.contains("jvbuildit/xmlstr/XMLStreamReader"))
        assertTrue("Android has the rest of java.beans", text.contains("java/beans/PropertyChangeListener"))
        assertFalse(text.contains("java/lang/management"))
        assertEquals("jvbuildit/xmlstr/Location.class", Relocation.name("javax/xml/stream/Location.class"))
        // Each stand-in referenced by a rename exists in Build It.
        listOf("jvbuildit.management.ManagementFactory", "jvbuildit.jxmgmt.NotificationEmitter", "jvbuilditb.Introspector").forEach { Class.forName(it) }
    }
}
