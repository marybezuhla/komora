import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version people see (major.minor.patch), set by hand before each release in version.txt.
val appVersion = rootProject.file("version.txt").readText().trim()
// versionCode comes from the same file, so anyone can rebuild the same APK from source: 1.2.4 -> 10204
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) ->
    require(b < 100 && c < 100) { "version.txt: minor and patch must be below 100" }
    a * 10000 + b * 100 + c
}

android {
    namespace = "ua.komora"
    compileSdk = 36

    defaultConfig {
        applicationId = "ua.komora"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    // No Google-readable list of libraries inside the APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Left unsigned here; signed later in a separate step that runs no build code
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (Android provides it on the phone)
    testImplementation("org.json:json:20240303")
}
