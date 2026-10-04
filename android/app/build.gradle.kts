import java.time.Instant
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val inkreaderlinkSdkVersion = providers.gradleProperty("inkreaderlinkSdkVersion").get()
val appUpdateCheckIntervalDays = providers.gradleProperty("appUpdateCheckIntervalDays").get().toLongOrNull()
    ?.takeIf { it in 1L..36_500L }
    ?: error("appUpdateCheckIntervalDays must be an integer from 1 to 36500")
val debugSigningConfigName = providers.gradleProperty("debugSigningConfig").getOrElse("release")
val buildTime = Instant.now().toString()

if (inkreaderlinkSdkVersion == "local") {
    configurations.configureEach {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }
}

val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.cold04.inkreadermgr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cold04.inkreadermgr"
        minSdk = 28
        targetSdk = 36
        versionCode = System.getenv("APP_VERSION_CODE")?.toInt() ?: 100199
        versionName = System.getenv("APP_VERSION_NAME") ?: "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "INKREADERLINK_SDK_VERSION", "\"$inkreaderlinkSdkVersion\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("long", "APP_UPDATE_CHECK_INTERVAL_MILLIS", "${appUpdateCheckIntervalDays * 24L * 60L * 60L * 1000L}L")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            excludes += setOf("**/armeabi/**", "**/mips/**", "**/mips64/**", "**/x86/**")
        }
    }

    signingConfigs {
        create("release") {
            // The missing-file fallback makes release builds fail instead of producing an unsigned APK.
            storeFile = rootProject.file(
                System.getenv("ANDROID_KEYSTORE_FILE")
                    ?: keystoreProperties.getProperty("storeFile")
                    ?: "release-keystore-not-configured.jks"
            )
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword") ?: ""
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias") ?: ""
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword") ?: ""
        }
    }

    buildTypes {
        debug {
            // Keep the package signature compatible with locally signed release builds.
            // CI can select the generated debug key when the release keystore is unavailable.
            signingConfig = signingConfigs.getByName(debugSigningConfigName)
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.cold04:inkreaderlink-uniffi:$inkreaderlinkSdkVersion") {
        isChanging = inkreaderlinkSdkVersion == "local"
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.speed.dial)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
