import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}



val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
// Public client configuration for the existing Nedu Wallet Supabase project.
// These values are intentionally safe to bundle in a mobile app: use only the
// publishable key here, never a Supabase service-role or secret key.
// Gradle command-line properties and local.properties can still override them.
// Provider.orElse() does not treat an empty string as missing. Read each
// source explicitly and reject blank values so CI and local builds both use
// the existing project's defaults when local.properties is absent or empty.
val supabaseUrl = providers.gradleProperty("supabaseUrl").orNull
    ?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("supabase.url")?.takeIf { it.isNotBlank() }
    ?: "https://yybmhqviescjpplqfaok.supabase.co"

val supabasePublishableKey = providers.gradleProperty("supabasePublishableKey").orNull
    ?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("supabase.publishableKey")?.takeIf { it.isNotBlank() }
    ?: "sb_publishable_62Mt0Zs-p-z1dwEcgiNiCA_QDzOok13"

require(supabaseUrl.startsWith("https://") && supabaseUrl.contains(".supabase.co")) {
    "Invalid Supabase project URL. Check supabase.url in local.properties."
}
require(supabasePublishableKey.startsWith("sb_publishable_") || supabasePublishableKey.startsWith("eyJ")) {
    "A Supabase publishable/anon key is required. Do not use a service-role key in the Android app."
}

android {
    namespace = "com.nedu.wallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nedu.wallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
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

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Supabase Kotlin SDK 3.0.1 supports this project's Kotlin 2.0.21 toolchain.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.1"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-android:3.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
