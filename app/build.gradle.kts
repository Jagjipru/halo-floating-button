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
        versionCode = 12
        versionName = "0.5.1"
    }

    val ksFile = rootProject.file(".signing/debug.jks")
    val ksPw = System.getenv("KS_PW")
    val useStable = ksFile.exists() && ksPw != null

    signingConfigs {
        create("stable") {
            if (useStable) {
                storeFile = ksFile
                storePassword = ksPw
                keyAlias = "halo"
                keyPassword = ksPw
            }
        }
    }

    buildTypes {
        debug {
            if (useStable) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (useStable) signingConfig = signingConfigs.getByName("stable")
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
