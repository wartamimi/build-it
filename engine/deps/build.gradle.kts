plugins {
    alias(libs.plugins.buildit.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":engine:layout"))
    implementation(libs.kotlinx.serialization.json)
}
