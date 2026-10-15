import com.android.build.gradle.internal.api.ApkVariantOutputImpl
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

    // AGP 8.5+ otherwise embeds a Play "Dependency metadata" block in the APK
    // signing block, which the F-Droid scanner rejects as an extra signing
    // block. Turning it off here keeps the GitHub and the F-Droid APK identical,
    // which the reproducible build check needs. AGP exposes no Gradle property
    // for this, so the DSL block has to live in the build script.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }


    // Name the produced APKs clearly and give every ABI its own version code
    // (versionCode * 10 + slot) so F-Droid can tell the split APKs apart. The
    // slot numbers must stay in sync with VercodeOperation in the F-Droid
    // recipe (fdroid/io.github.mrksey.softsstp.yml).
    applicationVariants.all {
        val variantVersionName = versionName
        val variantVersionCode = versionCode
        outputs.all {
            val output = this as ApkVariantOutputImpl
            val abi = output.getFilter("ABI") ?: "universal"
            val abiSlot = when (abi) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                else -> 0
            }
            if (abiSlot > 0) {
                output.versionCodeOverride = variantVersionCode * 10 + abiSlot
            }
            output.outputFileName = "SoftSSTP-$variantVersionName-$abi.apk"
        }
    }
}

flutter {
    source = "../.."
}
