plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.thor.device.android"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { aidl = true }
    sourceSets["main"].apply {
        java.srcDir("../../tools/diagnostic/src/main/kotlin")
        java.exclude("**/DiagnosticsActivity.kt")
        aidl.srcDir("../../tools/diagnostic/src/main/aidl")
        res.srcDir("../../tools/diagnostic/src/main/res")
        assets.srcDir("../../tools/contracts")
    }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":device-core"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
