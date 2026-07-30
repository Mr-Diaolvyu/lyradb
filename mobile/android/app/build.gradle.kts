// Android 应用模块 Gradle 配置
// 发布构建强制 HTTPS；debug 构建可显式连接 HTTP 开发服务。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lexaquila.lyradb.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.lexaquila.lyradb.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 3001000
        versionName = "3.1.0"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        getByName("release") {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
