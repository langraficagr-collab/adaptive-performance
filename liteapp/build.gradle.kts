plugins {
    id("com.android.application")
}

android {
    namespace = "com.mauricio.adaptiveperformance"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mauricio.adaptiveperformance.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 171
        versionName = "1.1.0-lite"
    }
    sourceSets.getByName("main").java.srcDir("../app/src/lean/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// No Shizuku, ADB, VPN, system tuning or third-party libraries.
