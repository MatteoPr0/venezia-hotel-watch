plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val dataUrl: String = (project.findProperty("dataUrl") as String?)
    ?: "https://raw.githubusercontent.com/OWNER/venezia-hotel-watch/main/data/hotels.json"
val vCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "it.matteo.veneziahotel"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.matteo.veneziahotel"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = "1.0.$vCode"
        buildConfigField("String", "DATA_URL", "\"$dataUrl\"")
    }

    signingConfigs {
        create("release") {
            // keystore personale fisso: così ogni nuova build si installa sopra la precedente
            storeFile = file("release.jks")
            storePassword = "venezia2027"
            keyAlias = "veneziahotel"
            keyPassword = "venezia2027"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
        debug { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
