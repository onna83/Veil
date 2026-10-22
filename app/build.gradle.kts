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
        versionCode = 12
        versionName = "1.8.1"
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
        debug {
            // v1.7: shrink only (no obfuscation, see proguard-rules.pro). Material/AppCompat
            // would otherwise add ~7.7 MB of dex and push the classic zip past 25 MB.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    lint {
        // lifecycle's bundled NonNullableMutableLiveDataDetector crashes this lint version
        // (IncompatibleClassChangeError); Veil uses no LiveData
        disable += "NullSafeMutableLiveData"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric screenshot tests (ScreensTest) render the real layouts/theme
        unitTests.isIncludeAndroidResources = true
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
    // v1.7 UI: Material 3 components (MaterialSwitch, Slider, cards, toolbar)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    // screenshot tests of the settings screens (native graphics, JVM only)
    testImplementation("org.robolectric:robolectric:4.14.1")
}
