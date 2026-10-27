plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
}

import java.io.FileInputStream
import java.util.Properties

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
    buildUponDefaultConfig = true
    basePath = rootProject.projectDir.absolutePath
    parallel = true
}

// detekt 1.23.1 在 JDK 21 上默认把 --jvm-target 传成 21，但只接受到 20。
// 工程本身编译目标是 17，这里显式对齐，避免本机 JDK 版本影响 check。
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
}

tasks.named("check") {
    dependsOn(tasks.named("detekt"))
}

android {
    namespace = "com.workspace.proot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.workspace.proot"
        minSdk = 26
        targetSdk = 35
    versionCode = 5977
    versionName = "5.9.77"
        ndk { abiFilters.add("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val p = keystoreProps.getProperty("storeFile")
            if (!p.isNullOrEmpty()) {
                storeFile = rootProject.file(p)
                storePassword = keystoreProps.getProperty("storePassword", "")
                keyAlias = keystoreProps.getProperty("keyAlias", "")
                keyPassword = keystoreProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("lib/**/libtermux.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":workspace"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.sqlite:sqlite-bundled:2.5.1")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 5.9.53锛氶〉绛炬í婊戞敼涓?ViewPager2 鈥斺€?鎷栧姩瀹炴椂璺熸墜 + 鏉炬墜鎸夐€熷害/璺濈缁撶畻銆?
    // 5.9.52 涔嬪墠鏄嚜宸卞疄鐜扮殑"50dp 姝诲尯 + 鏉炬墜鎾浐瀹氬姩鐢?锛岄〉闈㈠湪鎷栧姩闃舵瀹屽叏涓嶈窡鎵嬨€?
    // ViewPager2 鏄?RecyclerView 鐨勮杽灏佽锛宺ecyclerview 鏈潵灏卞湪渚濊禆閲屻€?
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
