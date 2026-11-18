plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.build"
}

dependencies {
    api(project(":engine:build"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.r8)
}
