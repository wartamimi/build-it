plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.workspace"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
