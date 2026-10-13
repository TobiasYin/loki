pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "thor-agent"
val modules = mapOf(":app" to "app", ":core" to "native-bridge/core", ":android" to "native-bridge/android", ":bridge-host" to "native-bridge/host", ":device-core" to "tools/core", ":device-android" to "integration/device-android", ":device-authority" to "integration/device-authority", ":diagnostic" to "tools/diagnostic", ":input-lab" to "tests/input-lab-app/app")
modules.forEach { (name, dir) -> include(name); project(name).projectDir = file(dir) }
