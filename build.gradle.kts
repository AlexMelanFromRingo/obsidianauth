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

// --- Constitution No-Go list enforcement (plan.md §"No-Go list") ---
// A grep guard, runnable on its own as `./gradlew checkNoGoList` and wired into `check`.
// Reviewers MUST refuse PRs that introduce any banned construct; this catches the obvious
// regressions automatically.
tasks.register("checkNoGoList") {
    group = "verification"
    description = "Fails the build on any construct banned by plan.md §\"No-Go list\"."
    doLast {
        val violations = mutableListOf<String>()

        fun rel(file: java.io.File) = file.relativeTo(rootDir).invariantSeparatorsPath

        val mainJava = subprojects
            .map { it.projectDir.resolve("src/main/java") }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
            }

        mainJava.forEach { file ->
            val path = rel(file)
            val inListenerOrCommand = path.contains("/listeners/") || path.contains("/command/")
            // Cipher/Mac.doFinal is legitimate only inside the synchronous core crypto
            // primitives — never on a platform thread or in a non-primitive class.
            val cryptoPrimitive = path.contains("/core/crypto/")
                || path.contains("/core/channel/")
                || path.contains("/core/totp/")
            file.readLines().forEachIndexed { i, raw ->
                val ln = i + 1
                val code = raw.trim()
                // Skip comment-only lines so doc references to banned tokens don't trip the guard.
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                    return@forEachIndexed
                }
                if (raw.contains("net.minecraft.server")) {
                    violations += "$path:$ln  NMS reference (net.minecraft.server)"
                }
                if (raw.contains("org.spongepowered.asm.mixin")) {
                    violations += "$path:$ln  Mixin import (org.spongepowered.asm.mixin)"
                }
                if (raw.contains(".doFinal(") && !cryptoPrimitive) {
                    violations += "$path:$ln  Cipher/Mac.doFinal outside the core crypto/channel/totp primitives"
                }
                if (inListenerOrCommand
                    && (raw.contains(".join()") || raw.contains(".get()") || raw.contains(".getNow("))) {
                    violations += "$path:$ln  blocking CompletableFuture call in a listener/command handler"
                }
            }
        }

        val configFiles = subprojects
            .map { it.projectDir.resolve("src/main/resources") }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "yml" || it.extension == "toml") }
                    .toList()
            }

        configFiles.forEach { file ->
            val path = rel(file)
            file.readLines().forEachIndexed { i, raw ->
                val ln = i + 1
                val withoutComment = raw.substringBefore('#')
                if (!withoutComment.lowercase().contains("password")) {
                    return@forEachIndexed
                }
                val colon = withoutComment.indexOf(':')
                if (colon < 0) {
                    return@forEachIndexed
                }
                val value = withoutComment.substring(colon + 1).trim().trim('"', '\'').trim()
                // The only allowed forms are indirect: env:VAR or file:PATH (config-schema.md).
                if (value.isNotEmpty() && !value.startsWith("env:") && !value.startsWith("file:")) {
                    violations += "$path:$ln  possible literal password (use an env: or file: source)"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "checkNoGoList: ${violations.size} No-Go-list violation(s):\n  "
                    + violations.joinToString("\n  "))
        }
        logger.lifecycle(
            "checkNoGoList: ${mainJava.size} source + ${configFiles.size} config file(s) scanned, clean.")
    }
}

tasks.named("check") {
    dependsOn("checkNoGoList")
}
