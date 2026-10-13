import java.util.Base64

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// A build explicitly marked push-capable must carry the complete public client config.
val pushDefines = providers.gradleProperty("dart-defines").orNull.orEmpty()
    .split(",").filter { it.isNotBlank() }.mapNotNull {
        runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
    }.associate { it.substringBefore("=") to it.substringAfter("=", "") }
if (pushDefines["FCM_REQUIRED"] == "true") {
    require(listOf("FCM_FIREBASE_API_KEY", "FCM_FIREBASE_APP_ID", "FCM_FIREBASE_SENDER_ID", "FCM_FIREBASE_PROJECT_ID")
        .all { !pushDefines[it].isNullOrBlank() }) {
        "FCM_REQUIRED: incomplete Android client configuration; build refused"
    }
    require(pushDefines["ENVIRONMENT"] == "dev" && pushDefines["FCM_FIREBASE_PROJECT_ID"] == "simertpi-dev") {
        "FCM_REQUIRED: expected existing DEV Firebase project"
    }
}

android {
    namespace = "ec.gob.simertpi.simertpi_citizen_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "ec.gob.simertpi.simertpi_citizen_app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Stable AndroidX biometric: CryptoObject + Class 3 Keystore authorization.
    implementation("androidx.biometric:biometric:1.1.0")
    // The app's guarded MessagingService subclasses the FlutterFire service,
    // so its Firebase Messaging API must be on the app compile classpath too.
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
