// :velocity-plugin — Velocity 3.3.x plugin, depends on :core.
// Velocity has no DB / no QR rendering — minimal shading.

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":core"))

    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    // NightConfig — Velocity already bundles this transitively, so compileOnly is fine.
    compileOnly(libs.nightconfig.toml)
    compileOnly(libs.nightconfig.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.velocity.api)
}

tasks.shadowJar {
    archiveBaseName.set("obsidianauth-velocity")
    archiveClassifier.set("")

    relocate("com.zaxxer.hikari", "org.alex_melan.obsidianauth.shaded.hikari")
    relocate("org.flywaydb",      "org.alex_melan.obsidianauth.shaded.flyway")
    relocate("com.google.zxing",  "org.alex_melan.obsidianauth.shaded.zxing")

    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")

    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
