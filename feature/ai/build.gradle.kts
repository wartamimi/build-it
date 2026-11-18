plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.ai"
}

dependencies {
    api(project(":engine:ai"))
    implementation(libs.androidx.activity.compose)
}
