plugins {
    alias(libs.plugins.buildit.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":engine:project"))
    implementation(project(":engine:layout"))
    implementation(libs.ecj)
    implementation(libs.r8)
    implementation(libs.apksig)
    implementation(libs.kotlinx.serialization.json)
}
