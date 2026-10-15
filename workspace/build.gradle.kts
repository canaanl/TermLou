plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "me.rerere.workspace"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        release {
            // 5.9.63：true → false。**这是 AGP 8.6 带来的一处必须改的配置，不是为了让测试变绿。**
            //
            // AGP 8.3.2 不对 library 模块跑 R8，库的 classes.jar 原样交给 app；
            // AGP 8.6 开始真的跑（minifyReleaseWithR8）。而本模块的 proguard-rules.pro
            // 一个字都没有（全是注释），库又没有入口点可言 —— R8 的结论是"全都没用"，
            // 于是输出 shrunkClasses.jar 只剩 432 字节，**整个库被剃光**。
            // 证据：5.9.62（AGP 8.3.2）的 app 映射表里有 42 条 me.rerere.workspace.*
            // 被 app 自己的 R8 判为未使用而删除 —— 说明当时喂进去的 jar 是有内容的。
            //
            // 缩小本来就该由 app 做：:app 开着 R8，并会读取本模块的 consumer-rules.pro。
            // 库自己先缩一遍，只会让 app 拿到一个空壳 —— 一旦哪天 app 真的用上某个类，
            // 那就是运行时 NoClassDefFoundError 或者 R8 missing class。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
}
