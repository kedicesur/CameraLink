plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.cameralink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cameralink"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"  // Only include arm64-v8a
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            merges += "AndroidxCamera.xml"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            java.srcDirs("src/main/cpp")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material) // Matches TOML key "material"
    implementation(libs.androidx.material3)
    implementation(libs.constraintlayout) // Matches TOML key "constraintlayout"
    implementation(libs.lifecycle.runtime.ktx) // Matches TOML key "lifecycle-runtime-ktx"
    implementation(libs.lifecycle.viewmodel.ktx)

    // CameraX
    implementation(libs.camera.core) // Matches TOML key "camera-core"
    implementation(libs.camera.camera2) // Matches TOML key "camera-camera2"
    implementation(libs.camera.lifecycle) // Matches TOML key "camera-lifecycle"
    implementation(libs.camera.view)
    implementation(libs.androidx.monitor)
    implementation(libs.androidx.junit.ktx) // Matches TOML key "camera-view"
}