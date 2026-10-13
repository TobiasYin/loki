plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.thor.tools.diagnostic"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "dev.thor.tools.diagnostic"; minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets["main"].assets.srcDir("../contracts")
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
