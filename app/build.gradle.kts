// ============================================================
//  app 模块构建脚本
//  项目：加油站夜班值守车辆报警 APP
//  阶段：M1（相机预览）
//
//  本文件基于 Android Studio 生成的原始内容修改，工具链：
//    AGP 9.4.1 / Gradle 9.6.0 / 内置 Kotlin 支持
//  M1 只改了三处（都在下面标了「M1 改动」）：
//    1) targetSdk 由 37 改为 36（与真机 Android 16 = API 36 对齐）
//    2) 在 android { } 里新增 buildFeatures { viewBinding = true }
//    3) 在 dependencies { } 里新增 4 个 CameraX 依赖
// ============================================================

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gasstation.guard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gasstation.guard"
        minSdk = 26
        // M1 改动 ①：Android Studio 默认给的是 37，我们改成 36。
        // 原因：真机是 Android 16 = API 36，项目章程要求 targetSdk 与真机对齐。
        // 好处：不会提前"预定"未来 Android 17 的新限制（本项目稳定性优先）。
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // M1 改动 ②：开启「视图绑定」。
    // 用 binding.previewView 代替 findViewById(R.id.previewView)，
    // 控件 id 写错时编译期就报错，而不是运行时崩溃。
    buildFeatures {
        viewBinding = true
    }

    // M2 改动：assets 里的 .tflite 必须【不压缩】。
    // AGP 默认会压缩 assets，被压缩的文件无法用 AssetManager.openFd() 映射成
    // 文件描述符，TFLite 加载时会直接抛异常。
    // 加了这一行，模型才能用零拷贝的方式映射进内存。
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // M1 改动 ③：CameraX（版本号统一定义在 gradle/libs.versions.toml 的 camerax 里）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // M2 改动 ④：LiteRT（TFLite）推理引擎
    implementation(libs.litert)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
