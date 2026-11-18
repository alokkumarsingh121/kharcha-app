plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alok.kharcha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alok.kharcha"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
