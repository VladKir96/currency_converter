import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonOutput
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val updateManifestOverride = providers.gradleProperty("pocketFxUpdateManifestUrl").orNull
val updateApkOrigin = providers.gradleProperty("pocketFxUpdateApkOrigin").orElse("").get()
val stableManifest = "https://github.com/VladKir96/currency_converter/releases/latest/download/update-manifest.json"
val releaseKeyStore = providers.environmentVariable("POCKET_FX_KEYSTORE")
val reuseDebugSigning = providers.gradleProperty("pocketFxReuseDebugSigning").orNull == "true"

fun validatedUpdateUrl(value: String): String = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null && it.fragment == null
}?.toString().orEmpty()

android {
    namespace = "com.pocketfx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pocketfx"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("pocketFxVersionCode")
            .orElse(providers.gradleProperty("pocketFxBaseVersionCode")).get().toInt()
        versionName = providers.gradleProperty("pocketFxVersionName")
            .orElse(providers.gradleProperty("pocketFxBaseVersionName")).get()
        buildConfigField("String", "UPDATE_APK_ORIGIN", JsonOutput.toJson(updateApkOrigin))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    require(!reuseDebugSigning || !releaseKeyStore.isPresent) {
        "Choose either an explicit release keystore or reuse of debug signing."
    }
    if (releaseKeyStore.isPresent) {
        val distribution = signingConfigs.create("distribution") {
            storeFile = file(releaseKeyStore.get())
            storePassword = providers.environmentVariable("POCKET_FX_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("POCKET_FX_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("POCKET_FX_KEY_PASSWORD").get()
        }
        buildTypes.named("release") { signingConfig = distribution }
    } else if (reuseDebugSigning) {
        val androidUserHome = providers.environmentVariable("ANDROID_USER_HOME")
            .getOrElse("${System.getProperty("user.home")}/.android")
        require(file("$androidUserHome/debug.keystore").isFile) { "The existing debug keystore is required." }
        buildTypes.named("release") { signingConfig = signingConfigs.getByName("debug") }
    }

    for ((buildType, defaultUrl) in mapOf("debug" to "", "release" to stableManifest)) {
        val url = validatedUpdateUrl(updateManifestOverride ?: defaultUrl)
        buildTypes.named(buildType) { buildConfigField("String", "UPDATE_MANIFEST_URL", JsonOutput.toJson(url)) }
        if (url.isNotEmpty()) sourceSets[buildType].manifest.srcFile("src/updates/AndroidManifest.xml")
    }
    sourceSets["androidTest"].assets.srcDir("schemas")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language.enableSplit = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")

    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
