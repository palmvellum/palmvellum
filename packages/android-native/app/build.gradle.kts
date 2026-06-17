plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.tatliving.palmvellum.organizers"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tatliving.palmvellum.organizers"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-native"
    }

    // Two device targets from one codebase:
    //  - standard: the classic portrait Palm UI (any phone).
    //  - cosmo:    tuned for the Planet Cosmo Communicator (2160x1080 landscape
    //              clamshell + physical keyboard). Distinct applicationId so it
    //              installs side-by-side with the standard build.
    flavorDimensions += "device"
    productFlavors {
        create("standard") {
            dimension = "device"
            resValue("string", "app_name", "Palm Organizers")
            buildConfigField("boolean", "COSMO", "false")
        }
        create("cosmo") {
            dimension = "device"
            applicationIdSuffix = ".cosmo"
            versionNameSuffix = "-cosmo"
            resValue("string", "app_name", "Palm Organizers (Cosmo)")
            buildConfigField("boolean", "COSMO", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)
}
