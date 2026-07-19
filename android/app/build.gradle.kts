import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keep AGP aligned with compileSdk 34 for this machine's SDK layout

// ---------------------------------------------------------------------------
// Release signing (OPTIONAL for local debug builds; REQUIRED to publish APKs)
//
// Keys are NEVER in this repo. Load from (first match wins per key):
//   1) Environment: GRLD_STORE_FILE, GRLD_STORE_PASSWORD, GRLD_KEY_ALIAS, GRLD_KEY_PASSWORD
//   2) ~/.grld/signing.properties  (maintainer machine only)
//   3) android/keystore.properties (local, gitignored — forks create their own)
//
// Anyone publishing their own build must generate their own keystore:
//   keytool -genkeypair -v -keystore my-release.jks -alias grld \
//     -keyalg RSA -keysize 2048 -validity 10000
// Then point storeFile/storePassword/keyAlias/keyPassword at that keystore.
// ---------------------------------------------------------------------------
fun loadReleaseSigningProps(): Properties {
    val props = Properties()
    val homeProps = file("${System.getProperty("user.home")}/.grld/signing.properties")
    if (homeProps.exists()) {
        homeProps.inputStream().use { props.load(it) }
    }
    val localProps = rootProject.file("keystore.properties")
    if (localProps.exists()) {
        localProps.inputStream().use { props.load(it) }
    }
    System.getenv("GRLD_STORE_FILE")?.let { props["storeFile"] = it }
    System.getenv("GRLD_STORE_PASSWORD")?.let { props["storePassword"] = it }
    System.getenv("GRLD_KEY_ALIAS")?.let { props["keyAlias"] = it }
    System.getenv("GRLD_KEY_PASSWORD")?.let { props["keyPassword"] = it }
    return props
}

val releaseSigningProps = loadReleaseSigningProps()
val hasReleaseSigning =
    releaseSigningProps.getProperty("storeFile")?.isNotBlank() == true &&
        releaseSigningProps.getProperty("storePassword")?.isNotBlank() == true &&
        releaseSigningProps.getProperty("keyAlias")?.isNotBlank() == true &&
        releaseSigningProps.getProperty("keyPassword")?.isNotBlank() == true

android {
    namespace = "com.blankspeaker.grld"
    // Compile against 34 for AGP stability; Live Update / MetricStyle used via
    // reflection + runtime checks on Android 17 (API 37) devices.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blankspeaker.grld"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val storePath = releaseSigningProps.getProperty("storeFile")!!
                storeFile = file(storePath)
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
        buildConfig = true
    }
}

// Fail fast when someone tries to assemble a publishable release without keys
tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            if (!hasReleaseSigning) {
                throw GradleException(
                    "Release signing not configured. Create your own keystore and either:\n" +
                        "  • ~/.grld/signing.properties  or  android/keystore.properties\n" +
                        "    storeFile=/path/to/your.jks\n" +
                        "    storePassword=...\n" +
                        "    keyAlias=...\n" +
                        "    keyPassword=...\n" +
                        "  • or export GRLD_STORE_FILE / GRLD_STORE_PASSWORD / GRLD_KEY_ALIAS / GRLD_KEY_PASSWORD\n" +
                        "Do not commit keystores or passwords."
                )
            }
            val sf = releaseSigningProps.getProperty("storeFile")
            if (sf != null && !file(sf).exists()) {
                throw GradleException("Release keystore not found: $sf")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
