plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.leo88q.orbitfall.mwa"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Godot Android library: provided at runtime by the engine, compile-only here.
    // compileOnly: runtime comes from the Godot export template.
    // Maven Central publishes up to 4.7.1.stable (checked 2026-09-17).
    compileOnly("org.godotengine:godot:4.7.1.stable")
    // Solana Mobile Wallet Adapter (2.x) client.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.1.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
