plugins {
    alias(libs.plugins.buildit.android.application)
    alias(libs.plugins.buildit.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.buildit.studio"

    defaultConfig {
        applicationId = "dev.buildit.studio"
        versionCode = 20
        versionName = "0.19.0"
    }

    androidResources {
        generateLocaleConfig = true
    }

    // Release signing comes from the environment (CI secrets); without it the release APK is unsigned.
    val keystore = providers.environmentVariable("BUILDIT_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("BUILDIT_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("BUILDIT_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("BUILDIT_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":engine:project"))
    implementation(project(":feature:home"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:designer"))
    implementation(project(":feature:blocks"))
    implementation(project(":feature:code"))
    implementation(project(":feature:build"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:console"))
    implementation(project(":feature:libraries"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":engine:build"))
    androidTestImplementation(project(":engine:ai"))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
