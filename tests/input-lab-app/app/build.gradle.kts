plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.thor.inputlab"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.thor.inputlab"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "dev.thor.inputlab.SnapshotInstrumentation"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = false }
}
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
dependencyLocking { lockAllConfigurations() }
