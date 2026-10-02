plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Use the existing watch release credentials; no separate phone key is generated.
val releaseVersion = providers.environmentVariable("APP_VERSION_NAME").orNull
val releaseParts = releaseVersion?.let { Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(it) }
    ?.groupValues?.drop(1)?.map(String::toLong)
val validReleaseVersion = releaseParts?.let { (major, minor, patch) ->
    minor <= 999 && patch <= 999 && major * 1_000_000 + minor * 1_000 + patch <= 2_100_000_000
} ?: false
val releaseCode = releaseParts?.takeIf { validReleaseVersion }
    ?.let { (major, minor, patch) -> (major * 1_000_000 + minor * 1_000 + patch).toInt() } ?: 1
val releaseStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "net.sibbl.dwt.phone"
    compileSdk = 35
    defaultConfig {
        // Data Layer requires the watch's package ID AND signing certificate.
        applicationId = "net.sibbl.dwt"
        minSdk = 30
        targetSdk = 35
        versionCode = releaseCode
        versionName = releaseVersion ?: "0.0.0-companion-prototype"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs(
        "../app/src/main/java/net/sibbl/dwt/data/radar",
        "../app/src/main/java/net/sibbl/dwt/model",
        "../app/src/main/java/net/sibbl/dwt/transport"
    )
    sourceSets["test"].java.srcDirs(
        "../app/src/test/java/net/sibbl/dwt/data/radar",
        "../app/src/test/java/net/sibbl/dwt/transport"
    )
    testOptions { unitTests.isIncludeAndroidResources = true }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

tasks.register("verifyCompanionReleaseConfiguration") {
    doLast {
        check(validReleaseVersion) { "APP_VERSION_NAME must be a valid MAJOR.MINOR.PATCH release version." }
        check(hasReleaseSigning) { "Companion release signing requires the same four SIGNING_* variables as the watch." }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("verifyCompanionReleaseConfiguration")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
