plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: VS_KEYSTORE (a .jks path) and VS_KEYSTORE_PASSWORD, set by CI from repository secrets or by
// hand. Without them the release build is signed with the debug key, so it still installs.
val releaseKeystore = System.getenv("VS_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    buildFeatures { aidl = true }
    namespace = "com.voicestudio.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicestudio.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.3"
        ndk { abiFilters += listOf("arm64-v8a") }  // the S23+; keeps the APK small
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("VS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("VS_KEY_ALIAS") ?: "voicestudio"
            keyPassword = System.getenv("VS_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        // libvsdecoder: the decoder on the GPU through ncnn (src/main/cpp)
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        // QNN's NPU programs (libQnnHtpV*Skel.so) are loaded by the DSP from disk, so native libraries
        // must be extracted at install instead of mapped from inside the APK
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }  // runs on the installed JDK 20
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.22.0")
    testImplementation("junit:junit:4.13.2")
}
