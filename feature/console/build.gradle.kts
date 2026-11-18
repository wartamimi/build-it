plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.console"
}

dependencies {
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
