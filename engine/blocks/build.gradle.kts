plugins {
    alias(libs.plugins.buildit.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":engine:project"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.compiler.embeddable)
}
