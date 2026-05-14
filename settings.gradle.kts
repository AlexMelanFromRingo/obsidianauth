plugins {
    // Lets Gradle auto-download the required Java toolchain (17) when the host JVM is older or newer.
    // Pinned exact-version per Constitution Security §Dependencies.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "obsidianauth"

include(":core", ":paper-plugin", ":velocity-plugin")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}
