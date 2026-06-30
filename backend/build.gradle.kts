import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "org.khorum.oss.relikquary"

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
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.aws.s3)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)

    // Persistence drivers (feature 016, Phase 3): embedded SQLite by default, PostgreSQL when selected.
    // hibernate-community-dialects supplies the SQLite dialect (not in hibernate-core).
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.hibernate.community.dialects)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.postgresql)

    s3mock("com.adobe.testing:s3mock:${libs.versions.s3mock.get()}:exec") { isTransitive = false }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Real-client round-trip tests drive the repo's Gradle wrapper and the system Maven via
    // external processes (keeping gradle-api/maven libs off the test classpath).
    systemProperty("relikquary.rootProjectDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("relikquary.mavenExecutable", System.getenv("MAVEN_EXECUTABLE") ?: "/opt/maven/bin/mvn")
    // Keep the default (SQLite) persistence backend's database file inside the build directory during
    // tests, so the JPA datasource every @SpringBootTest now boots never writes into the source tree.
    systemProperty(
        "relikquary.persistence.sqlite.path",
        layout.buildDirectory.file("test-sqlite/relikquary.db").get().asFile.absolutePath,
    )
    // The s3mock runnable jar, resolved lazily and passed to S3RoundTripTest.
    val s3mockJar = s3mock
    doFirst { systemProperty("relikquary.s3mockJar", s3mockJar.singleFile.absolutePath) }
}

// Opt-in: bundle the SvelteKit static build into the backend jar, served under /ui (see WebConfig).
// Enable with `-PbundleFrontend`. Kept opt-in so the frontend stays a separable module by default.
if (project.hasProperty("bundleFrontend")) {
    tasks.named<Copy>("processResources") {
        dependsOn(":frontend:npmBuild")
        from(rootProject.layout.projectDirectory.dir("frontend/build")) {
            into("static/ui")
        }
    }
}
