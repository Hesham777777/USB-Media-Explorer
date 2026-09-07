plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.usbmediaexplorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.usbmediaexplorer"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Keep the APK small: the app ships English + Arabic only.
        resourceConfigurations += listOf("en", "ar")
    }

    // Signing (audit CRITICAL: the key + passwords were committed to a public repo).
    //   1. CI materializes the rotated key from the USBMEDIA_KEYSTORE_B64 secret into
    //      keystore/ci.p12 and exports USBMEDIA_STORE_PASSWORD / USBMEDIA_KEY_ALIAS /
    //      USBMEDIA_KEY_PASSWORD — whenever present, those win.
    //   2. Until the owner completes SETUP_SIGNING.md, the build falls back to the committed
    //      keystore/usbmedia.p12 so published APKs keep update-compatible signatures.
    // Without any explicit config, AGP would auto-generate a fresh random debug keystore per
    // CI run and Android would refuse every update ("App not installed").
    val secretKeystore = rootProject.file("keystore/ci.p12")
    val committedKeystore = rootProject.file("keystore/usbmedia.p12")
    val keystoreFile = when {
        secretKeystore.exists() -> secretKeystore
        committedKeystore.exists() -> committedKeystore
        else -> null
    }
    signingConfigs {
        create("stable") {
            storeFile = keystoreFile
            // GitHub Actions sets declared-but-unconfigured secrets to the EMPTY string, so
            // "not null" is not enough — only a non-empty env var overrides the fallback.
            storePassword = System.getenv("USBMEDIA_STORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "usbmedia"
            keyAlias = System.getenv("USBMEDIA_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "usbmedia"
            keyPassword = System.getenv("USBMEDIA_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "usbmedia"
            storeType = "PKCS12"
        }
    }
    val stableKeystoreExists = keystoreFile != null

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (stableKeystoreExists) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (stableKeystoreExists) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Opt in globally: Media3 and Material3 annotate a lot of the APIs this app depends on.
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.okio)
    implementation(libs.androidx.exifinterface)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
