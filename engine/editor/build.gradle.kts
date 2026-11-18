plugins {
    alias(libs.plugins.buildit.jvm.library)
}

dependencies {
    api(project(":core:common"))
    implementation(project(":engine:layout")) // XML well-formedness checks
    implementation(libs.kotlinx.serialization.json) // JSON checks
}
