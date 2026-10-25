import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional and stays out of the repository: keystore.properties (gitignored) with
// storeFile, storePassword, keyAlias and keyPassword. Without it, assembleRelease gives an unsigned APK.
val signing = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }

android {
    namespace = "dev.lumina.atlas"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lumina.atlas"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.0"
        // Google TV boxes are ARM; keeps the FFmpeg audio decoders small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    // FFmpeg audio decoders (MP2, AC3, EAC3, DTS) that Android lacks; many live channels need them.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
