plugins { id("com.android.application") }

android {
    namespace = "dev.lumina.atlas"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.lumina.atlas"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
