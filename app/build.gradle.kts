plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.orientar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.orientar"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // ══════════════════════════════════════════════════════════════
    // CORE ANDROID
    // ══════════════════════════════════════════════════════════════
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")

    // ══════════════════════════════════════════════════════════════
    // JETPACK COMPOSE
    // ══════════════════════════════════════════════════════════════
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.androidx.foundation.android)


    // ══════════════════════════════════════════════════════════════
    // ARCORE & SCENEVIEW (single version — used by Treasure Hunt + AR Navigation)
    // ══════════════════════════════════════════════════════════════
    implementation("com.google.ar:core:1.48.0") //for T.H. it was ("com.google.ar:core:1.42.0")
    implementation("io.github.sceneview:arsceneview:2.3.0")
    implementation("io.github.sceneview:sceneview:2.3.0")

    // ══════════════════════════════════════════════════════════════
    // GOOGLE PLAY SERVICES (Maps + Location for AR Navigation)
    // ══════════════════════════════════════════════════════════════
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // ══════════════════════════════════════════════════════════════
    // ML KIT (OCR - Text Recognition)
    // ══════════════════════════════════════════════════════════════
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation(libs.play.services.mlkit.text.recognition.common)
    implementation(libs.androidx.espresso.core)

    // ══════════════════════════════════════════════════════════════
    // NETWORKING (Retrofit + OkHttp)
    // ══════════════════════════════════════════════════════════════
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ══════════════════════════════════════════════════════════════
    // FIREBASE
    // ══════════════════════════════════════════════════════════════
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-common-ktx")
    implementation("com.google.firebase:firebase-appcheck-debug")

    // ══════════════════════════════════════════════════════════════
    // TESTING
    // ══════════════════════════════════════════════════════════════
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}