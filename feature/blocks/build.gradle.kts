plugins {
    alias(libs.plugins.buildit.android.feature)
}

android {
    namespace = "dev.buildit.feature.blocks"
}

dependencies {
    api(project(":engine:blocks"))
    implementation(project(":engine:layout"))
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
