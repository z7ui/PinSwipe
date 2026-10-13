plugins {
    id("com.android.application")
}

android {
    namespace = "com.z7ui.pinswipe"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.z7ui.pinswipe"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // LSPosed 在运行期提供这些类，所以只用 compileOnly，不打进包里
    compileOnly("de.robv.android.xposed:api:82")
}
