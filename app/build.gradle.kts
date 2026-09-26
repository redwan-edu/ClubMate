import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    kotlin("plugin.serialization") version "2.0.20"
    alias(libs.plugins.compose.compiler)
   alias(libs.plugins.google.gms.google.services)
}

// Your own Cloudinary account, pasted into cloudinary.properties in the project folder.
val cloudinary = Properties().apply {
    val file = rootProject.file("cloudinary.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun cloudinaryProp(key: String): String = cloudinary.getProperty(key, "").trim()

android {
    namespace = "com.example.clubmate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.clubmate"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "CLOUDINARY_CLOUD_NAME", "\"${cloudinaryProp("cloudName")}\"")
        buildConfigField("String", "CLOUDINARY_API_KEY", "\"${cloudinaryProp("apiKey")}\"")
        buildConfigField("String", "CLOUDINARY_API_SECRET", "\"${cloudinaryProp("apiSecret")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // rounded icon set used across the UI (version from the Compose BOM)
    implementation("androidx.compose.material:material-icons-extended")

    implementation(libs.androidx.runtime.livedata)
    implementation(libs.firebase.database.ktx)
    implementation(libs.firebase.database)
    debugImplementation(libs.androidx.ui.tooling)


    // navigation
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // firebase
    implementation(libs.firebase.auth)

    // coil
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // end-to-end encryption (X25519 + HKDF)
    implementation("com.google.crypto.tink:tink-android:1.23.0")

    // cloudinary
    implementation("com.cloudinary:cloudinary-android:2.0.0")
}