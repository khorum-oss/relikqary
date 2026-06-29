package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * The Gradle Module Metadata fidelity proof (feature 011, FR-004/SC-001): a real Gradle build publishes a
 * library with a **feature variant bearing a capability** (expressible only in the `.module`, not the
 * POM); a separate real Gradle build that **requires that capability** resolves it through Relikquary.
 * Resolution can only succeed if the `.module` was served faithfully and drove variant selection, and the
 * resolved feature jar is compared byte-for-byte against what was published.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GradleModuleRoundTripTest {

    @LocalServerPort
    var port: Int = 0

    private val rootProjectDir = File(System.getProperty("relikquary.rootProjectDir"))
    private val gradlew = File(rootProjectDir, "gradlew").absolutePath

    @Test
    fun `gradle feature-variant module round-trips with variant selection`(@TempDir work: Path) {
        val version = "1.0.0-gmm-r${System.currentTimeMillis()}"
        val url = "http://127.0.0.1:$port/releases"

        // 1. Publish a library with a feature variant + capability (real Gradle, Gradle Module Metadata).
        val publisher = work.resolve("publisher")
        writePublisher(publisher, version, url)
        runProcess(listOf(gradlew, "-p", publisher.toString(), "publish", "--no-daemon", "--console=plain", "--stacktrace"))

        // The `.module` was accepted and stored byte-for-byte (FR-001).
        val coordDir = storageRoot.resolve("releases/com/example/widget/$version")
        assertTrue(Files.isRegularFile(coordDir.resolve("widget-$version.module"))) { ".module not stored" }
        val publishedExtra = Files.readAllBytes(publisher.resolve("build/libs/widget-$version-extra.jar"))

        // 2. A consumer that REQUIRES the capability resolves it (only possible via the `.module`).
        val consumer = work.resolve("consumer")
        writeConsumer(consumer, version, url)
        runProcess(
            listOf(
                gradlew, "-p", consumer.toString(), "resolveExtra",
                "-g", work.resolve("gradle-home").toString(),
                "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
            ),
        )
        val resolvedExtra = Files.readAllBytes(consumer.resolve("build/resolved/widget-$version-extra.jar"))
        assertArrayEquals(publishedExtra, resolvedExtra) { "resolved feature-variant jar differs from published" }
    }

    private fun writePublisher(dir: Path, version: String, url: String) {
        Files.createDirectories(dir.resolve("src/main/java/com/example"))
        Files.createDirectories(dir.resolve("src/extra/java/com/example"))
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "widget"""" + "\n")
        dir.resolve("src/main/java/com/example/Widget.java").writeText(
            "package com.example; public final class Widget { public String name() { return \"widget\"; } }\n",
        )
        dir.resolve("src/extra/java/com/example/Extra.java").writeText(
            "package com.example; public final class Extra { public String extra() { return \"extra\"; } }\n",
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                `maven-publish`
            }
            group = "com.example"
            version = "$version"
            sourceSets { create("extra") }
            java {
                registerFeature("extra") { usingSourceSet(sourceSets["extra"]) }
            }
            publishing {
                publications { create<MavenPublication>("lib") { from(components["java"]) } }
                repositories {
                    maven {
                        url = uri("$url")
                        isAllowInsecureProtocol = true
                        credentials { username = "ci"; password = "ci-secret" }
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun writeConsumer(dir: Path, version: String, url: String) {
        Files.createDirectories(dir)
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "consumer"""" + "\n")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins { `java-library` }
            repositories {
                maven {
                    url = uri("$url")
                    isAllowInsecureProtocol = true
                }
            }
            dependencies {
                implementation("com.example:widget:$version") {
                    capabilities { requireCapability("com.example:widget-extra") }
                }
            }
            tasks.register<Copy>("resolveExtra") {
                from(configurations.runtimeClasspath)
                into(layout.buildDirectory.dir("resolved"))
            }
            """.trimIndent(),
        )
    }

    private fun runProcess(command: List<String>) {
        val process = ProcessBuilder(command).directory(rootProjectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "timed out: ${command.joinToString(" ")}\n$output" }
        check(process.exitValue() == 0) { "process failed: ${command.joinToString(" ")}\n$output" }
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("relikquary.security.users[0].username") { "ci" }
            registry.add("relikquary.security.users[0].password") { "{noop}ci-secret" }
            registry.add("relikquary.security.users[0].roles[0]") { "PUBLISH" }
        }
    }
}
