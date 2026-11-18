plugins {
    alias(libs.plugins.buildit.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
