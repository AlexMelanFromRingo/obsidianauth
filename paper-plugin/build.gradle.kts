// :paper-plugin — Paper 1.20.1 plugin, depends on :core.
// Shades runtime libraries under org.alex_melan.obsidianauth.shaded.*

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":core"))

    compileOnly(libs.paper.api)

    implementation(libs.zxing.core)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)

    // JDBC drivers — NOT relocated (JDBC SPI requires the original package).
    implementation(libs.sqlite.jdbc)
    implementation(libs.mysql.connector)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    testImplementation(testFixtures(project(":core")))
}

tasks.shadowJar {
    archiveBaseName.set("obsidianauth-paper")
    archiveClassifier.set("")

    relocate("com.zaxxer.hikari", "org.alex_melan.obsidianauth.shaded.hikari")
    relocate("org.flywaydb",      "org.alex_melan.obsidianauth.shaded.flyway")
    relocate("com.google.zxing",  "org.alex_melan.obsidianauth.shaded.zxing")
    // JDBC drivers are NOT relocated.

    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")

    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// MockBukkit 3.93.2 (the line that supports MC 1.20.x) ships Java 21 bytecode. Tests never
// ship, so paper-plugin's toolchain runs on Java 21 — but the SHIPPED plugin bytecode stays
// Java 17 (Paper 1.20.1's floor) via the explicit --release 17 on compileJava below.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
tasks.named<JavaCompile>("compileJava") {
    options.release.set(17)        // shipped plugin bytecode: Java 17
}
tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(21)        // test-only: read the Java 21 MockBukkit artifact
}
