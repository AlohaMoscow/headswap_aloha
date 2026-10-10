import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key lives outside the project: %USERPROFILE%\.android-keys\headswap-release.properties
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.android-keys/headswap-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.yegorkapomidorka.headswap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yegorkapomidorka.headswap"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.1"
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
