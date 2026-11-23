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
        // Every GitHub build gets a higher version, so a new APK always installs as an update.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "3.3.$run"
    }

    // Fixed signing key from the repo's GitHub secrets (the workflow sets KS_FILE / KS_PASS). With it every
    // new APK installs over the old one and keeps the data. Without it Gradle uses a throwaway debug key.
    val ksFile = System.getenv("KS_FILE")
    if (!ksFile.isNullOrBlank()) {
        signingConfigs {
            create("fixed") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KS_PASS")?.trim()
                keyAlias = "kharcha"
                keyPassword = System.getenv("KS_PASS")?.trim()
            }
        }
    }

    buildTypes {
        debug {
            if (!ksFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("fixed")
        }
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
    testImplementation("junit:junit:4.13.2")
}
