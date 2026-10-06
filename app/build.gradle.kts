plugins {
    id("com.android.application")
}

android {
    namespace = "com.mauricio.adaptiveperformance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mauricio.adaptiveperformance"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "1.4.9-noads"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
