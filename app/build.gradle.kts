plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.usbmanager.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.usbmanager.app"
        minSdk = 26        // Android 8.0 -> USB Host API + coroutines icin guvenli taban
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true // SettingsFragment BuildConfig.VERSION_NAME kullanir
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- AndroidX / Material temelleri ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // by viewModels() delegesi + registerForActivityResult + OnBackPressedCallback icin
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // --- Lifecycle / Coroutines (arka plan islem izolasyonu icin) ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- USB Mass Storage: root gerektirmeyen ham blok erisimi ---
    // https://github.com/magnusja/libaums  (Apache 2.0)
    // NOT: Bu proje bilgi kesim tarihinden sonra da guncellenebilir; surum
    // uyusmazligi olursa https://github.com/magnusja/libaums adresindeki
    // "Releases" sekmesinden en guncel surumu kontrol edin.
    implementation("me.jahnen.libaums:core:0.10.0")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
