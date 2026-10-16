plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息放在 ~/.gradle/gradle.properties，不进仓库：
//   moSignKeyFile=E:/keys/mediaorganizer.jks
//   moSignKeyAlias=mediaorganizer
//   moSignKeyPwd=...
val signKeyFile = providers.gradleProperty("moSignKeyFile").orNull

android {
    namespace = "com.example.mediaorganizer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mediaorganizer"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (signKeyFile != null) {
            create("release") {
                storeFile = file(signKeyFile)
                storePassword = providers.gradleProperty("moSignKeyPwd").get()
                keyAlias = providers.gradleProperty("moSignKeyAlias").get()
                keyPassword = providers.gradleProperty("moSignKeyPwd").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // 网络 / 协程
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 设置保存
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
