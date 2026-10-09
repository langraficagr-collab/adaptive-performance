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
        buildConfigField("boolean", "LEAN_MODE", "false")
        buildConfigField("boolean", "CONSERVATIVE_MODE", "false")
        resValue("string", "app_name", "Adaptive Performance")
        versionCode = 84
        versionName = "1.11.2-power-save"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("lean") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionCode = 171
            versionName = "1.1.0-lite"
            buildConfigField("boolean", "LEAN_MODE", "true")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "LEAN_MODE", "false")
            buildConfigField("boolean", "CONSERVATIVE_MODE", "false")
        }
        create("conservative") {
            dimension = "edition"
            applicationIdSuffix = ".conservative"
            versionCode = 275
            versionName = "1.0.4-conservative"
            buildConfigField("boolean", "LEAN_MODE", "false")
            buildConfigField("boolean", "CONSERVATIVE_MODE", "true")
            resValue("string", "app_name", "Adaptive Performance Conservador")
        }
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
