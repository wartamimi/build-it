plugins {
    alias(libs.plugins.buildit.android.library)
}

android {
    namespace = "dev.buildit.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":engine:project"))
    implementation(project(":core:database"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
