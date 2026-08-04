plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.yulestudio.dvloop"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.yulestudio.dvloop"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    /**
     * 배포 빌드도 디버그 키로 서명한다.
     *
     * 마켓에 올리는 앱이 아니라 폰에 직접 넣어 쓰는 도구다. 키를 새로 만들면
     * 서명이 달라져 기존 앱을 지우고 깔아야 하고, 그러면 저장된 로그인 정보와
     * 루프 설정이 함께 날아간다. 같은 키를 써야 덮어쓰기가 된다.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    }
    // 도감 DB 는 대부분 압축된 PNG BLOB 이라 재압축해도 이득이 없다
    androidResources {
        noCompress += "db"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.security.crypto)
}
