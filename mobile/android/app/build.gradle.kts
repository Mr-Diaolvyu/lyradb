// Android 应用模块 Gradle 配置
// 移动端为 BS 封装客户端：原生外壳（AppCompat View 体系）+ WebView 加载远端 BS 前端。
// 不承载 Compose 业务屏幕与 Retrofit API 直调（旧瘦客户端方案已废弃）。
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
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // AppCompat + Material（原生外壳 UI，配置页）
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.12.0")

    // WebView 增强
    implementation("androidx.webkit:webkit:1.10.0")

    // 生物识别快速解锁
    implementation("androidx.biometric:biometric:1.1.0")

    // 协程（配置页异步校验服务端可达性）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
