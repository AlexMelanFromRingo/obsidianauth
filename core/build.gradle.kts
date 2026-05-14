// :core — plain Java 17 library shared by paper-plugin and velocity-plugin.
// MUST NOT depend on Paper API, Velocity API, or any platform-specific class.

plugins {
    // Exposes core/src/testFixtures/** so plugin modules can reuse shared test helpers
    // (e.g. ImmediateAsyncExecutor) via testImplementation(testFixtures(project(":core"))).
    `java-test-fixtures`
}

dependencies {
    // SLF4J is provided by Paper / Velocity at runtime.
    compileOnly(libs.slf4j.api)

    // Declared compile-only here so callers (paper-plugin) get the types for shaded usage.
    compileOnly(libs.zxing.core)
    compileOnly(libs.hikaricp)
    compileOnly(libs.flyway.core)
    compileOnly(libs.sqlite.jdbc)

    // Test
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.zxing.core)
    testImplementation(libs.hikaricp)
    testImplementation(libs.flyway.core)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.slf4j.api)
}
