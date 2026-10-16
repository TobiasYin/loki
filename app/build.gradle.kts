plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.thor.agent"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.thor.agent"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.0-integration"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    sourceSets["debug"].assets.srcDir(rootProject.layout.buildDirectory.dir("runtime-assets/debug"))
    sourceSets["release"].assets.srcDir(rootProject.layout.buildDirectory.dir("runtime-assets/release"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":android"))
    implementation(project(":device-android"))
    implementation(project(":device-authority"))
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
dependencyLocking { lockAllConfigurations() }
