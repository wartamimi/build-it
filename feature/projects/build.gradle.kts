plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.projects"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
