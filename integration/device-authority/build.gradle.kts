plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { api(project(":core")); api(project(":device-core")); testImplementation("junit:junit:4.13.2") }
