plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.libraries"
}

dependencies {
    api(project(":engine:deps"))
    implementation(libs.androidx.activity.compose)
}
