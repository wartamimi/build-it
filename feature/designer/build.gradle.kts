plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.designer"
}

dependencies {
    api(project(":engine:layout"))
    implementation(libs.androidx.activity.compose)
    // Runtime classes so the preview renders real views for these components.
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.drawerlayout)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
