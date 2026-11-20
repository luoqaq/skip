import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProp(propName: String, envName: String): String? =
    keystoreProperties.getProperty(propName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

val resolvedVersionCode: Int =
    (findProperty("androidVersionCode") as? String)?.trim()?.toIntOrNull()
        ?: System.getenv("SKIP_ANDROID_VERSION_CODE")?.trim()?.toIntOrNull()
        ?: 5

val resolvedVersionName: String =
    (findProperty("androidVersionName") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("SKIP_ANDROID_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "1.0.0"

val resolvedUpdateUrl: String =
    (findProperty("androidUpdateUrl") as? String)?.trim()
        ?: System.getenv("SKIP_ANDROID_UPDATE_URL")?.trim()
        ?: "https://yanjiu-android-updates.bojin1229.workers.dev/skip/latest.json"

fun escapeBuildConfigString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.luo.skip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luo.skip"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${escapeBuildConfigString(resolvedUpdateUrl)}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        val storePath = signingProp("storeFile", "KEYSTORE_FILE")
        val storePwd = signingProp("storePassword", "KEYSTORE_PASSWORD")
        val alias = signingProp("keyAlias", "KEY_ALIAS")
        val keyPwd = signingProp("keyPassword", "KEY_PASSWORD")

        if (storePath != null && storePwd != null && alias != null && keyPwd != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/**",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
