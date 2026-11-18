import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Pure Kotlin/JVM module. Engines (project format, layout, blocks, build pipeline) live here so they
 * have no Android dependency, run on ART unchanged, and are unit-tested on the JVM.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = BuildItConfig.JAVA_VERSION
            targetCompatibility = BuildItConfig.JAVA_VERSION
        }
        configureKotlinCompile()
        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }
}
