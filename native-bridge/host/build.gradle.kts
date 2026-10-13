plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.thor.bridge.host"
    compileSdk = 35
    defaultConfig { applicationId = "dev.thor.bridge.host"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":android")) }
