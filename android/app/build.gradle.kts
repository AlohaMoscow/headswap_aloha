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
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yegorkapomidorka.headswap"
        minSdk = 29
        targetSdk = 37
        versionCode = 40
        versionName = "1.2.2"
        // беспровод (бета): пункт в настройках и ветка B; бета-APK для тестеров: gradlew assembleRelease -Pwlbeta=true
        buildConfigField("boolean", "WIRELESS_BETA", (project.findProperty("wlbeta") == "true").toString())
    }

    buildFeatures {
        buildConfig = true
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
        debug {
            buildConfigField("boolean", "WIRELESS_BETA", "true")
        }
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
