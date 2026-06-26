import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "org.khorum.oss.relikqary"

// adobe/s3mock runnable jar (exec classifier) — driven as an external process in S3RoundTripTest so it
// runs in its own JVM (no Spring Boot classpath clash) and needs no Docker.
val s3mock: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

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
    implementation(libs.aws.s3)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)

    s3mock("com.adobe.testing:s3mock:${libs.versions.s3mock.get()}:exec") { isTransitive = false }
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
    // The s3mock runnable jar, resolved lazily and passed to S3RoundTripTest.
    val s3mockJar = s3mock
    doFirst { systemProperty("relikqary.s3mockJar", s3mockJar.singleFile.absolutePath) }
}
