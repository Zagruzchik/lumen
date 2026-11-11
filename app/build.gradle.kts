plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.lumen.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lumen.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 55
        versionName = "0.27.1"
    }

    signingConfigs {
        create("lumen") {
            storeFile = file("lumen.jks")
            storePassword = "lumen-player"
            keyAlias = "lumen"
            keyPassword = "lumen-player"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lumen")
        }
        debug {
            signingConfig = signingConfigs.getByName("lumen")
        }
    }

    compileOptions {
        // NewPipe Extractor использует java.nio и java.time — для Android ниже 13 нужен desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/DEPENDENCIES"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/INDEX.LIST"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    // Встроенный онлайн-движок: SoundCloud, YouTube (Music), Bandcamp без сервера.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Передача на телефон рядом: Bluetooth для поиска, Wi‑Fi (точка доступа / Wi‑Fi Direct) для файлов.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("dev.chrisbanes.haze:haze:1.6.10")
}
