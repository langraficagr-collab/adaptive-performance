plugins {
    id("com.android.application")
}

val productionAds = providers.gradleProperty("productionAds").orElse("true").get() == "true"
val admobAppId = if (productionAds) providers.gradleProperty("admobAppId").orElse("ca-app-pub-6594966604456519~9008375832").get() else "ca-app-pub-3940256099942544~3347511713"
val admobBannerId = if (productionAds) providers.gradleProperty("admobBannerId").orElse("ca-app-pub-6594966604456519/1610986134").get() else "ca-app-pub-3940256099942544/9214589741"
require(admobAppId.matches(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}")))
require(admobBannerId.matches(Regex("ca-app-pub-[0-9]{16}/[0-9]{10}")))
if (productionAds) {
    require(!admobAppId.contains("3940256099942544") && !admobBannerId.contains("3940256099942544"))
}

android {
    buildFeatures { buildConfig = true; resValues = true }
    namespace = "com.mauricio.adaptiveperformance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mauricio.adaptiveperformance"
        minSdk = 26
        targetSdk = 35
        manifestPlaceholders["admobAppId"] = admobAppId
        resValue("string", "admob_banner_id", admobBannerId)
        buildConfigField("boolean", "ADS_TEST_MODE", (!productionAds).toString())
        versionCode = 32
        versionName = "1.4.8"
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
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
