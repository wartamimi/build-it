package dev.buildit.engine.deps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Real repositories (or a mirror given by BUILDIT_TEST_MAVEN). */
    private fun repos() = System.getenv("BUILDIT_TEST_MAVEN")?.let { listOf(Repository("Test mirror", it.trimEnd('/') + "/")) } ?: Repository.defaults

    @Test fun versionOrdering() {
        val sorted = listOf("1.10.0", "1.2.0", "1.2.0-rc01", "1.2.0-alpha02", "1.2.0-beta01", "2.0.0-alpha01").sortedWith(Versions)
        assertEquals(listOf("1.2.0-alpha02", "1.2.0-beta01", "1.2.0-rc01", "1.2.0", "1.10.0", "2.0.0-alpha01"), sorted)
        assertEquals(0, Versions.compare("1.2", "1.2.0"))
        assertEquals("1.0", Versions.select("[1.0,2.0)") { emptyList() })
        assertEquals("1.4.2", Versions.select("1.4.+") { listOf("1.3.0", "1.4.0", "1.4.2", "1.5.0", "1.4.3-alpha01") })
        assertEquals(Coordinate("a.b", "c", "1"), Coordinate.parse("a.b:c:1@aar"))
        assertEquals(null, Coordinate.parse("a.b:c"))
    }

    @Test fun pomInheritanceAndBoms() {
        val parent = """<project><groupId>g</groupId><artifactId>parent</artifactId><version>1</version>
            <properties><lib.version>2.5</lib.version></properties>
            <dependencyManagement><dependencies>
              <dependency><groupId>x</groupId><artifactId>managed</artifactId><version>${'$'}{lib.version}</version></dependency>
              <dependency><groupId>g</groupId><artifactId>bom</artifactId><version>3</version><type>pom</type><scope>import</scope></dependency>
            </dependencies></dependencyManagement></project>"""
        val bom = """<project><groupId>g</groupId><artifactId>bom</artifactId><version>3</version>
            <dependencyManagement><dependencies><dependency><groupId>y</groupId><artifactId>fromBom</artifactId><version>9</version></dependency></dependencies></dependencyManagement></project>"""
        val child = """<project><parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>
            <artifactId>child</artifactId><packaging>aar</packaging><dependencies>
              <dependency><groupId>x</groupId><artifactId>managed</artifactId></dependency>
              <dependency><groupId>y</groupId><artifactId>fromBom</artifactId></dependency>
              <dependency><groupId>z</groupId><artifactId>tests</artifactId><version>1</version><scope>test</scope></dependency>
              <dependency><groupId>z</groupId><artifactId>opt</artifactId><version>1</version><optional>true</optional></dependency>
            </dependencies></project>"""
        val raws = mapOf("g:parent:1" to parent, "g:bom:3" to bom).mapValues { Pom.Raw.parse(it.value) }
        val pom = Pom.parse(child) { raws[it.toString()] }!!
        assertEquals(Coordinate("g", "child", "1"), pom.coordinate)
        assertEquals("aar", pom.packaging)
        assertEquals(listOf("x:managed:2.5", "y:fromBom:9", "z:opt:1"), pom.dependencies.map { "${it.group}:${it.artifact}:${it.version}" })
        assertTrue(pom.dependencies.last().optional)
    }

    @Test fun resolvesAppCompatAndMaterialFromTheRealRepositories() {
        val cache = MavenCache(tmp.newFolder("m2"), repos())
        val r = Resolver(cache).resolve(listOf(Coordinate("androidx.appcompat", "appcompat", "1.7.1"), Coordinate("com.google.android.material", "material", "1.13.0")))
        assertTrue(r.problems.joinToString("\n"), r.ok)
        val modules = r.artifacts.associate { it.coordinate.module to it }
        for (m in listOf("androidx.appcompat:appcompat", "androidx.core:core", "androidx.fragment:fragment", "androidx.lifecycle:lifecycle-runtime",
                "androidx.collection:collection-jvm", "androidx.annotation:annotation-jvm", "org.jetbrains.kotlin:kotlin-stdlib", "com.google.android.material:material")) {
            assertTrue("$m missing from ${modules.keys.sorted()}", m in modules)
        }
        assertTrue(modules.getValue("androidx.appcompat:appcompat").isAar)
        assertTrue(!modules.getValue("androidx.collection:collection-jvm").isAar)
        assertTrue(r.artifacts.all { it.file.length() > 0 })
        // One version per module.
        assertEquals(r.artifacts.size, r.artifacts.map { it.coordinate.module }.distinct().size)
    }
}
