plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.workspace.probe"
    compileSdk = 34

    defaultConfig {
        // 独立包名：能和 TermLou 并排装，不冲突
        applicationId = "com.workspace.probe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 判定逻辑用 JSONObject 装观测值；单元测试里 org.json 是桩，必须给真实实现
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
