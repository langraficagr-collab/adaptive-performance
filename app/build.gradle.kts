plugins {
    id("com.android.application")
}

val productionAds = false
android {
    buildFeatures { buildConfig = true; resValues = true }
    namespace = "com.mauricio.adaptiveperformance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mauricio.adaptiveperformance"
        minSdk = 26
        targetSdk = 35
        buildConfigField("boolean", "ADS_TEST_MODE", "false")
        versionCode = 65
        versionName = "1.8.6-all-apps"
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
