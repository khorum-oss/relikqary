import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "org.khorum.oss.relikqary"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.mockk)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Real-client round-trip tests drive the repo's Gradle wrapper and the system Maven via
    // external processes (keeping gradle-api/maven libs off the test classpath).
    systemProperty("relikqary.rootProjectDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("relikqary.mavenExecutable", System.getenv("MAVEN_EXECUTABLE") ?: "/opt/maven/bin/mvn")
}
