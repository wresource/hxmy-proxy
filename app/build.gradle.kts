import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// 正式签名凭据从 keystore.properties 读（该文件不入库，格式见 keystore.properties.example）。
// 文件缺失时 release **不配置 signingConfig**，产出未签名包 —— 这是有意的失败方式：
// 此前这里写死 debug 签名，而 debug 签名的包既传不上 Play（与 upload key 不匹配），
// 装到真机上也会因签名不同要求先卸载正式版（连带丢掉设置与日志）。
// 宁可拿到一个装不上的未签名包，也不要拿到一个「看起来能用」的错签名包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
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
        versionCode = 121
        versionName = "1.24.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true       // R8 代码混淆+缩减
            isShrinkResources = true     // 资源缩减
            // 把 .so 的调试符号打进 aab（Play 会据此还原 native 崩溃栈，否则上传时报警告）。
            // 本项目自己没有 NDK 代码，产物里的 .so 全部来自 AndroidX
            // （libandroidx.graphics.path / libdatastore_shared_counter），它们已 strip，
            // 能提取的符号有限；配这行主要是让「万一这两个库崩了」的栈不是一堆地址。
            ndk { debugSymbolLevel = "FULL" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有 keystore.properties 才签名，否则为 null＝不签名（见文件顶部注释）。
            signingConfig = signingConfigs.findByName("release")
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