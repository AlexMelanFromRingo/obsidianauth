// Root build for the ObsidianAuth multi-module project.
// Per Constitution Principle V, every Java source lives under org.alex_melan.obsidianauth.*
// Per Constitution Principle II, no NMS, no Mixin, no shading of server internals.

plugins {
    `java-library`
}

allprojects {
    group = "org.alex_melan.obsidianauth"
    version = "1.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        // The older Sonatype OSS snapshots repo — Paper's transitive deps (bungeecord-chat) live here.
        maven("https://oss.sonatype.org/content/repositories/snapshots/") {
            name = "sonatype-oss-snapshots"
            mavenContent { snapshotsOnly() }
        }
        // Newer Sonatype S01 snapshots (for projects published after Feb 2021).
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
            name = "sonatype-s01-snapshots"
            mavenContent { snapshotsOnly() }
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
        // -Xlint:all minus the "processing" category: Velocity's annotation processor doesn't
        // claim its own @Plugin / @Subscribe / @Inject / @DataDirectory annotations under
        // javac's processing pass, which triggers a spurious warning we don't want as an error.
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
        }
    }
}
