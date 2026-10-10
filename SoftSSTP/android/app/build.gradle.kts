import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing is read from android/key.properties. When the file is absent
// (e.g. a fresh clone without the secrets) the build falls back to the debug
// key so `flutter build` still works, but CI injects a stable keystore so every
// release is signed with the same certificate.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "io.github.mrksey.softsstp"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "io.github.mrksey.softsstp"
        minSdk = 26
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Name the produced APKs clearly, e.g.
    //   SoftSSTP-1.0.0-arm64-v8a.apk
    //   SoftSSTP-1.0.0-armeabi-v7a.apk
    //   SoftSSTP-1.0.0-universal.apk
    applicationVariants.all {
        val variantVersionName = versionName
        outputs.all {
            val output = this as BaseVariantOutputImpl
            val abi = output.getFilter("ABI") ?: "universal"
            output.outputFileName = "SoftSSTP-$variantVersionName-$abi.apk"
        }
    }
}

flutter {
    source = "../.."
}
