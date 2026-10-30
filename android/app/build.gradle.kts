plugins {
    id("com.android.application")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

android {
    namespace = "dev.mtvxlqq.claudewidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mtvxlqq.claudewidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // тот же ключ, что у debug: релиз ставится поверх без удаления
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
