import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    `maven-publish`
}

group = "org.khorum.oss.relikquary"

// A SNAPSHOT version so re-running `publish` overwrites instead of being rejected (releases are
// immutable). It must be published to the `snapshots` repo (the `releases` repo rejects snapshot coords).
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    // Resolve this app's dependencies THROUGH the running relikquary so they exercise its proxy/cache.
    // Use the `public` group repo (releases + the maven-central proxy) — NOT `snapshots`, which is a
    // plain store with no upstream and would 404 every transitive dependency. Port 8081 = sandbox profile.
    maven {
        name = "relikquary-public"
        url = uri("http://localhost:8081/public")
        isAllowInsecureProtocol = true
    }
    maven {
        name = "relikquary-snapshots"
        url = uri("http://localhost:8081/snapshots")
        isAllowInsecureProtocol = true
    }
    val disableMavenCentral = System.getenv("DISABLE_MAVEN_CENTRAL")?.toBoolean() ?: false

    if (disableMavenCentral) mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

publishing {
    // Without a publication, the `publish` task has nothing to do and is SKIPPED. Publish the module's
    // jar (plus generated POM / Gradle module metadata) as a Maven publication.
    publications {
        create<MavenPublication>("sandbox") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "relikquary"
            // The first path segment selects the named repository, so the repo name (`snapshots`) is
            // part of the URL — not just the host. `allowInsecureProtocol` is required for plain http.
            // Port 8081 matches the 'sandbox' profile (application-sandbox.yml), which runs the server
            // with auth on + the 'test' publisher.
            url = uri("http://localhost:8081/snapshots")
            isAllowInsecureProtocol = true
            credentials {
                username = "test"
                password = "test_password"
            }
        }
    }
}