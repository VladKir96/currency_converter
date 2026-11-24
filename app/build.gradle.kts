import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonOutput
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val updateManifestUrl = providers.gradleProperty("pocketFxUpdateManifestUrl").orElse("").get()
val updateApkOrigin = providers.gradleProperty("pocketFxUpdateApkOrigin").orElse("").get()
val updatesConfigured = runCatching { URI(updateManifestUrl) }.getOrNull()?.let {
    it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null && it.fragment == null
} == true
val releaseKeyStore = providers.environmentVariable("POCKET_FX_KEYSTORE")

android {
    namespace = "com.pocketfx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pocketfx"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("pocketFxVersionCode").map(String::toInt).getOrElse(4)
        versionName = providers.gradleProperty("pocketFxVersionName").getOrElse("1.3.0")
        buildConfigField("String", "UPDATE_MANIFEST_URL", JsonOutput.toJson(updateManifestUrl.takeIf { updatesConfigured }.orEmpty()))
        buildConfigField("String", "UPDATE_APK_ORIGIN", JsonOutput.toJson(updateApkOrigin))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (releaseKeyStore.isPresent) {
        val distribution = signingConfigs.create("distribution") {
            storeFile = file(releaseKeyStore.get())
            storePassword = providers.environmentVariable("POCKET_FX_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("POCKET_FX_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("POCKET_FX_KEY_PASSWORD").get()
        }
        buildTypes.named("release") { signingConfig = distribution }
    }

    if (updatesConfigured) {
        for (buildType in listOf("debug", "release")) {
            sourceSets[buildType].manifest.srcFile("src/updates/AndroidManifest.xml")
        }
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
