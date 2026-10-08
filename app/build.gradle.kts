plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.halo.floatingbutton"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.halo.floatingbutton"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "0.5.9"
    }

    // ---- Sideload (debug) signing: stable key cached in CI ----
    val ksFile = rootProject.file(".signing/debug.jks")
    val ksPw = System.getenv("KS_PW")
    val useStable = ksFile.exists() && ksPw != null

    // ---- Play Store (release) signing: upload key from CI secrets ----
    val upStoreFile = System.getenv("UPLOAD_STORE_FILE")
    val upStorePw = System.getenv("UPLOAD_STORE_PASSWORD")
    val upKeyAlias = System.getenv("UPLOAD_KEY_ALIAS")
    val upKeyPw = System.getenv("UPLOAD_KEY_PASSWORD")
    val useUpload = upStoreFile != null && upStorePw != null && upKeyAlias != null && upKeyPw != null

    signingConfigs {
        create("stable") {
            if (useStable) {
                storeFile = ksFile
                storePassword = ksPw
                keyAlias = "halo"
                keyPassword = ksPw
            }
        }
        create("upload") {
            if (useUpload) {
                storeFile = file(upStoreFile!!)
                storePassword = upStorePw
                keyAlias = upKeyAlias
                keyPassword = upKeyPw
            }
        }
    }

    buildTypes {
        debug {
            if (useStable) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            // Prefer the Play upload key when present (AAB build); otherwise
            // fall back to the stable sideload key.
            signingConfig = when {
                useUpload -> signingConfigs.getByName("upload")
                useStable -> signingConfigs.getByName("stable")
                else -> null
            }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
