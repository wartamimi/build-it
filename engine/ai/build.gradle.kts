plugins {
    alias(libs.plugins.buildit.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":engine:project"))
    implementation(libs.anthropic.java)
    implementation(libs.kotlinx.serialization.json)
}
