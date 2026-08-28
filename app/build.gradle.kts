plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.four.toolboxmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.four.toolboxmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    // Release 签名：密钥库在仓库根目录（git 忽略），密码在 ~/.gradle/gradle.properties
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("toolbox.keystore")
            storePassword = findProperty("TOOLBOX_STORE_PASSWORD") as String?
            keyAlias = "toolbox"
            keyPassword = findProperty("TOOLBOX_KEY_PASSWORD") as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG 控制 WebView 远程调试开关
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0") // DayNight 强制夜间模式 → WebView 上报 prefers-color-scheme: dark
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // 局域网 HTTP 客户端（/api/auth、/api/status 等，协议见 docs §5.3）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 底栏毛玻璃：Android 12+ 真模糊，低版本自动降级半透明
    implementation("dev.chrisbanes.haze:haze:1.2.2")

    // DSH 远程工具：文档起始脚本（强制 prefers-color-scheme: dark，让 DSH GUI 切原生深色主题）
    implementation("androidx.webkit:webkit:1.10.0")

    // DSH 扫码绑定：CameraX 预览 + ML Kit 二维码识别（模型内置，离线可用）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
}
