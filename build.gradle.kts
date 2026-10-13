plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("android") version "2.2.10" apply false
    id("com.android.library") version "8.10.1" apply false
    id("com.android.application") version "8.10.1" apply false
}
allprojects { dependencyLocking { lockAllConfigurations() } }
val bundleRuntime by tasks.registering(Exec::class) { workingDir(rootDir); commandLine("node", "scripts/bundle-runtime.mjs") }
project(":app") { tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(bundleRuntime) } }
