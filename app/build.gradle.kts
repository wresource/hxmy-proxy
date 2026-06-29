plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mzstd.hxmyproxy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mzstd.hxmyproxy"
        minSdk = 29
        targetSdk = 37
        // 版本号:每次构建递增。三段式语义化 MAJOR.MINOR.PATCH —
        //   修复/诊断 +PATCH、新功能 +MINOR(PATCH 归 0)、重大变更 +MAJOR(其余归 0)。
        //   versionCode 单调 +1(Play 据此判断升级)。便于真机区分「装的是哪一版构建」。
        versionCode = 18
        versionName = "1.2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true       // R8 代码混淆+缩减
            isShrinkResources = true     // 资源缩减
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 临时用 debug 签名以便实测 minified 构建；正式发布请换成正式 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    lint {
        // release 不跑 lintVital（其与配置缓存/网络代理冲突，且不影响 R8 验证）
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests {
            // 让未 mock 的 android.* 调用返回默认值而非抛异常（官方「本地单元测试」推荐）。
            // 否则代理 accept 循环里的 android.util.Log.i 在 JVM 单测中抛异常，
            // 连接处理协程未响应即崩溃，导致 ProxyIntegrationTest 全部超时。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}