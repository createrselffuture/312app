import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Адреса API приложения по порядку: основной + запасные.
// Приложение пробует их по очереди и запоминает рабочий.
val releaseApiUrls = "https://vpn312.store/api/app/v1"
// Для отладки на эмуляторе: в local.properties app.debugApiUrls=http://10.0.2.2:8090/api/app/v1
val debugApiUrls: String = localProps.getProperty("app.debugApiUrls", releaseApiUrls)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "store.vpn312.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "store.vpn312.app"
        minSdk = 26
        targetSdk = 34
        // номер версии: ./gradlew assembleRelease -Pvc=2 -Pvn=1.1 (сервер сравнивает с latest_version)
        versionCode = (project.findProperty("vc") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("vn") as String?) ?: "1.0"
        buildConfigField("String", "API_URLS", "\"$releaseApiUrls\"")
        buildConfigField("String", "SITE_URL", "\"https://vpn312.store\"")
        buildConfigField("String", "SUPPORT_URL", "\"https://t.me/vpn312support_bot\"")
    }

    signingConfigs {
        create("release") {
            if (signProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/" + signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_URLS", "\"$debugApiUrls\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // без keystore/keystore.properties сборка выходит неподписанной (app-release-unsigned.apk)
            if (signProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.google.zxing:core:3.5.3")  // QR-код конфига
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
