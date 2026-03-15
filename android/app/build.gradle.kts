import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    // TODO: Firebase 설정 후 주석 해제
    // id("com.google.gms.google-services")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.navblind"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.navblind"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["arcoreApiKey"] = localProperties.getProperty("ARCORE_API_KEY", "")

        // API Base URL
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/v1\"")

        // TFLite: 에뮬레이터(x86_64) + 실기기(arm64-v8a) 모두 지원
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://api.navblind.com/v1\"")
            // 실제 ESP32-CAM IP (배포 시 local.properties 에서 읽거나 런타임 설정)
            buildConfigField("String", "GLASS_STREAM_URL", "\"http://192.168.4.1/stream\"")
            // 릴리즈: ESP32-CAM 사용
            buildConfigField("Boolean", "USE_LOCAL_CAMERA", "false")
        }
        debug {
            // SERVER_HOST: local.properties 에서 읽음
            //   실기기(야외) → SERVER_HOST=100.122.72.41
            //   에뮬레이터   → SERVER_HOST=10.0.2.2
            val serverHost = localProperties.getProperty("SERVER_HOST", "10.0.2.2")
            buildConfigField("String", "API_BASE_URL", "\"http://$serverHost:8080/v1\"")
            buildConfigField("String", "GLASS_STREAM_URL", "\"http://$serverHost:8081/stream\"")
            // USE_LOCAL_CAMERA: 빌드 명령 -P 플래그 > local.properties > 기본값(true)
            //   true  → LocalCameraSource  (스마트폰 내장 카메라)
            //   false → MjpegCameraSource  → GLASS_STREAM_URL 에 연결
            //             에뮬레이터: http://10.0.2.2:8081/stream → mock_stream.py
            //             실기기:     http://<SERVER_HOST>:8081/stream → ESP32-CAM
            //
            // 사용 예:
            //   ./gradlew assembleDebug                          → 폰 카메라 (기본)
            //   ./gradlew assembleDebug -PUSE_LOCAL_CAMERA=false → MJPEG (mock / ESP32)
            val useLocalCamera = (project.findProperty("USE_LOCAL_CAMERA") as? String)
                ?: localProperties.getProperty("USE_LOCAL_CAMERA", "true")
            buildConfigField("Boolean", "USE_LOCAL_CAMERA", useLocalCamera)
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.6"
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-compiler:2.48.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Retrofit (API Client)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Room (Local Database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Google Play Services - Location
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // CameraX (스마트폰 내장 카메라 — ESP32-CAM 대체 테스트용)
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")

    // ProcessLifecycleOwner (앱 전체 생명주기 — 서비스에서 CameraX 사용 시 필요)
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")

    // ARCore Geospatial API
    implementation("com.google.ar:core:1.40.0")

    // TensorFlow Lite (YOLO 객체 검출)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Firebase - TODO: google-services.json 추가 후 주석 해제
    // implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    // implementation("com.google.firebase:firebase-auth-ktx")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
