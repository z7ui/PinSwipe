import java.util.Properties

plugins {
    id("com.android.application")
}

// 正式签名：密钥在仓库根目录 keystore/ 下（已 gitignore）。
// 没有这个文件时自动退回 debug 签名，保证别人 clone 下来也能构建。
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystorePropsFile.exists()

android {
    namespace = "com.z7ui.pinswipe"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.z7ui.pinswipe"
        minSdk = 27
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // storeFile 可能是裸文件名（相对 keystore/ 目录），也可能是仓库内相对路径
                val storePath = keystoreProps.getProperty("storeFile")
                storeFile = rootProject.file(storePath).takeIf { it.exists() }
                    ?: keystorePropsFile.parentFile.resolve(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有正式密钥就用正式密钥；没有则退回 debug（本地/CI 都能出包）
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
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