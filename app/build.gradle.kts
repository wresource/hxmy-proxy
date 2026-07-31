import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
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
        versionCode = 124
        versionName = "1.24.4"

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

/**
 * 覆盖率口径：**分母只留逻辑层**。
 *
 * ui/ 有约 7000 行 Compose 屏幕代码。把它算进分母，core 层从 80% 掉到 60% 时总数字可能只动
 * 两个百分点——退化被稀释成噪音，那个数字就既不能反映安全程度、也不能驱动任何行动。
 * 而且界面的错一眼能看见，逻辑的错是静默的（规则表变空但开关仍显示「已启用」、准入停在上个会话），
 * 后者才是「改完之后用不了却不知道」的来源。
 *
 * **例外必须保留**：ui/ 根目录的 4 个 ViewModel 与 Format.kt 虽在 ui 包下却是纯逻辑
 * （规则归一化、block/allow 互斥、编辑保留 addedAt、字节进制换算），错了没人看得出来，
 * 所以只排除具体的界面子包与 AppRoot/NavTab，不能图省事写成 `ui.**`。
 */
kover {
    reports {
        filters {
            excludes {
                classes(
                    // —— 界面：Compose 屏幕与组件 ——
                    "com.mzstd.hxmyproxy.ui.AppRootKt*",   // 带 * 才能连内部 lambda 类一起排除
                    "com.mzstd.hxmyproxy.ui.NavTab*",
                    "com.mzstd.hxmyproxy.ui.components.*",
                    "com.mzstd.hxmyproxy.ui.dashboard.*",
                    "com.mzstd.hxmyproxy.ui.help.*",
                    "com.mzstd.hxmyproxy.ui.locale.*",
                    "com.mzstd.hxmyproxy.ui.monitor.*",
                    "com.mzstd.hxmyproxy.ui.onboarding.*",
                    "com.mzstd.hxmyproxy.ui.protection.*",
                    "com.mzstd.hxmyproxy.ui.rules.*",
                    "com.mzstd.hxmyproxy.ui.settings.*",
                    "com.mzstd.hxmyproxy.ui.theme.*",
                    // —— 框架入口与 DI 装配：没有可测的分支 ——
                    "com.mzstd.hxmyproxy.MainActivity*",
                    "com.mzstd.hxmyproxy.HxmyProxyApp*",
                    "com.mzstd.hxmyproxy.di.*",
                    // —— 生成代码 ——
                    // 注意：模式匹配的是**全限定名**，所以 "Hilt_*" 只能命中默认包下的类。
                    // 要排掉 com.mzstd.hxmyproxy.Hilt_MainActivity 必须写成 "*Hilt_*"。
                    // 同理内部类（如 X_Factory$InstanceHolder）结尾不是 _Factory，得补尾部 *。
                    "*.BuildConfig",
                    "*ComposableSingletons*",
                    "*Hilt_*", "*_Factory*", "*_HiltModules*", "*_MembersInjector*",
                    "*Dagger*", "*_Impl*",
                    // Hilt/Dagger 把聚合元数据生成到自己的顶层包里，不排掉会以 0% 混进分母
                    "dagger.hilt.*", "hilt_aggregated_deps.*",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
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
    // ViewModel 的 5 个依赖都是要 Context/DataStore 的具体类（Kotlin 默认 final），JVM 里造不出来，
    // 只能 mock —— mockk 能 mock final 类，mockito 需要额外的 inline mock maker。
    testImplementation(libs.mockk)
    // viewModelScope 默认跑在 Dispatchers.Main，JVM 单测里没有主循环，必须用 setMain 换成测试调度器。
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}