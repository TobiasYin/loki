// Product builds use the repository root. Kept as a clear failure instead of a stale standalone demo build.
throw GradleException("Loki product is integrated: run ../gradlew :app:assembleDebug from the repository root")
