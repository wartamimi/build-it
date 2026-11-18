plugins {
    alias(libs.plugins.buildit.android.library)
    alias(libs.plugins.buildit.android.compose)
}

android {
    namespace = "dev.buildit.core.designsystem"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui.tooling.preview)
    implementation(project(":core:model"))
}
