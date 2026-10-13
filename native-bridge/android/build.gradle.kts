plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.thor.bridge.android"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("fetch-assets"))
}
dependencies { api(project(":core")); implementation("androidx.webkit:webkit:1.14.0") }

val copyFetchAssets by tasks.registering(Sync::class) { from("../js/fetch-adapter.js"); into(layout.buildDirectory.dir("fetch-assets")) }
tasks.named("preBuild").configure { dependsOn(copyFetchAssets) }
