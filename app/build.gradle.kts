plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Correctly applied here for the 'app' module
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" // Use a version compatible with your Kotlin version
}



android {
    namespace = "ru.shustree.shustreeproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.shustree.shustreeproxy"
        minSdk = 24
        targetSdk = 36
        versionCode = 63
        versionName = "1.4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java") // CORRECT
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        // This tells the Android Gradle Plugin to enable Compose features
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false // полезно, если ошибка воспроизводится при собирании release
    }

    // The old composeOptions block is correctly removed.
    // The new kotlin.compose plugin handles everything automatically.
}



dependencies {
    // AndroidX & UI Core
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")

    // JSON Serialization (нужен для VpnInfoRepository)
    //implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Google Services
    implementation("com.google.android.play:integrity:1.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

}
