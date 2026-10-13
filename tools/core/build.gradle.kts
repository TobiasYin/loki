plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test {
    val localTemp = layout.buildDirectory.dir("tmp-tests")
    systemProperty("java.io.tmpdir", localTemp.get().asFile.absolutePath)
    doFirst { localTemp.get().asFile.mkdirs() }
    testLogging { events("passed", "skipped", "failed") }
}
