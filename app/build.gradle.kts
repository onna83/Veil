plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.veilfilter"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.veilfilter"
        minSdk = 34
        targetSdk = 35
        versionCode = 8
        versionName = "1.6.0"
        ndk {
            // Android 14+ phones are arm64. Add "x86_64" here to run on an emulator.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Two detector runtimes, selectable at build time (their native libraries
    // export the same JNI symbols, so they cannot share one APK):
    //   classic : LiteRT 1.4 Interpreter + GPU delegate (what v1 used)
    //   litert2 : LiteRT 2.3 CompiledModel API (new GPU engine)
    flavorDimensions += "runtime"
    productFlavors {
        create("classic") {
            dimension = "runtime"
            resValue("string", "app_name", "Veil")
        }
        create("litert2") {
            dimension = "runtime"
            applicationIdSuffix = ".lrt2"
            versionNameSuffix = "-litert2"
            resValue("string", "app_name", "Veil (LiteRT 2)")
        }
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

    androidResources {
        // The model is memory-mapped straight from the APK.
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// LiteRT 2.3 drags in Play "AI pack" delivery (WorkManager, boot receiver,
// network-state permission) for downloading models. Veil bundles its model and
// is offline-only; CompiledModel/Environment/TensorBuffer do not use it.
configurations.matching { it.name.startsWith("litert2") }.configureEach {
    exclude(group = "com.google.android.play")
    exclude(group = "com.google.android.gms")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    "classicImplementation"("com.google.ai.edge.litert:litert:1.4.2")
    "classicImplementation"("com.google.ai.edge.litert:litert-gpu:1.4.2")
    "classicImplementation"("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    "litert2Implementation"("com.google.ai.edge.litert:litert:2.3.0")
    "litert2Implementation"("com.google.ai.edge.litert:litert-gpu:2.3.0")
    // ONNX Runtime: runs the official NudeNet .onnx files (downloaded on demand)
    // classic only: +12.9 MB compressed would push the litert2 zip past 25 MB
    "classicImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    testImplementation("junit:junit:4.13.2")
}
