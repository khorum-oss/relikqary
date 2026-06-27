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
 * The authoritative Principle II round-trip: a real Gradle `maven-publish` publishes an artifact to a
 * running Relikquary instance, then a real Maven client AND a real Gradle client resolve it back, and
 * every file is compared byte-for-byte (SC-001..SC-004, FR-003, FR-011). The storage root is wired
 * via [DynamicPropertySource] to a per-class `@TempDir` (the real filesystem boundary).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublishResolveRoundTripTest {

    @LocalServerPort
    var port: Int = 0

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            // Auth is enabled by default; configure a publisher so credentialed publishing works.
            registry.add("relikquary.security.users[0].username") { "ci" }
            registry.add("relikquary.security.users[0].password") { "{noop}ci-secret" }
            registry.add("relikquary.security.users[0].roles[0]") { "PUBLISH" }
        }
    }

    private val rootProjectDir = File(System.getProperty("relikquary.rootProjectDir"))
    private val gradlew = File(rootProjectDir, "gradlew").absolutePath
    private val mvn = System.getProperty("relikquary.mavenExecutable")

    @Test
    fun `publish from gradle then resolve from maven and gradle byte-for-byte`(@TempDir work: Path) {
        // Unique release version so neither client can serve a cached copy — resolution must hit Relikquary.
        val version = "1.0.0-r${System.currentTimeMillis()}"
        val url = "http://127.0.0.1:$port/releases"

        // 1. Publish from a real Gradle build (maven-publish) WITH credentials. SC-001.
        val publisher = work.resolve("publisher")
        writePublisher(publisher, version, url, withCredentials = true)
        runProcess(listOf(gradlew, "-p", publisher.toString(), "publish", "--no-daemon", "--console=plain", "--stacktrace"))

        // Stored byte-for-byte, with artifact metadata present (FR-002, research.md §3 source guard).
        val coordDir = storageRoot.resolve("releases/com/example/widget/$version")
        val storedJar = coordDir.resolve("widget-$version.jar")
        assertTrue(Files.isRegularFile(storedJar)) { "published jar not stored" }
        assertTrue(Files.isRegularFile(coordDir.resolve("widget-$version.pom"))) { "published pom not stored" }
        assertTrue(
            Files.isRegularFile(storageRoot.resolve("releases/com/example/widget/maven-metadata.xml")),
        ) { "maven-metadata.xml not produced by publish — version discovery would have no source" }

        val publishedBytes = Files.readAllBytes(publisher.resolve("build/libs/widget-$version.jar"))
        assertArrayEquals(publishedBytes, Files.readAllBytes(storedJar)) { "stored jar differs from published jar" }

        // 2. Resolve with a real Maven client into a fresh local repo (so the artifact must come from
        //    Relikquary, not a cache). dependency:get honours -DremoteRepositories. SC-002, SC-004.
        val mavenRepo = work.resolve("m2-repo")
        runProcess(
            listOf(
                mvn, "-B",
                "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get",
                "-Dartifact=com.example:widget:$version",
                "-Dtransitive=false",
                "-DremoteRepositories=relikquary::default::$url",
                "-Dmaven.repo.local=$mavenRepo",
            ),
        )
        val mavenBytes = Files.readAllBytes(mavenRepo.resolve("com/example/widget/$version/widget-$version.jar"))
        assertArrayEquals(publishedBytes, mavenBytes) { "maven-resolved jar differs from published jar" }

        // 3. Resolve with a real Gradle client (fresh Gradle home → must download from Relikquary). SC-003, SC-004.
        val consumer = work.resolve("consumer")
        writeConsumer(consumer, version, url)
        runProcess(
            listOf(
                gradlew, "-p", consumer.toString(), "resolveArtifact",
                "-g", work.resolve("gradle-home").toString(),
                "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
            ),
        )
        val gradleBytes = Files.readAllBytes(consumer.resolve("build/resolved/widget-$version.jar"))
        assertArrayEquals(publishedBytes, gradleBytes) { "gradle-resolved jar differs from published jar" }
        assertArrayEquals(mavenBytes, gradleBytes) { "maven and gradle resolved different bytes" }
    }

    private fun writePublisher(dir: Path, version: String, url: String, withCredentials: Boolean) {
        Files.createDirectories(dir.resolve("src/main/java/com/example"))
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "widget"""" + "\n")
        dir.resolve("src/main/java/com/example/Widget.java").writeText(
            """
            package com.example;
            public final class Widget {
                public String name() { return "widget"; }
            }
            """.trimIndent(),
        )
        val credentials = if (withCredentials) {
            """
                        credentials {
                            username = "ci"
                            password = "ci-secret"
                        }
            """.trimIndent()
        } else {
            ""
        }
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                `maven-publish`
            }
            group = "com.example"
            version = "$version"
            publishing {
                publications { create<MavenPublication>("lib") { from(components["java"]) } }
                repositories {
                    maven {
                        url = uri("$url")
                        isAllowInsecureProtocol = true
                        $credentials
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
            plugins { base }
            repositories {
                maven {
                    url = uri("$url")
                    isAllowInsecureProtocol = true
                }
            }
            val res = configurations.create("res")
            dependencies { add("res", "com.example:widget:$version") }
            tasks.register<Copy>("resolveArtifact") {
                from(res)
                into(layout.buildDirectory.dir("resolved"))
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `publish without credentials is rejected when auth is enabled`(@TempDir work: Path) {
        val version = "1.0.0-noauth-r${System.currentTimeMillis()}"
        val publisher = work.resolve("publisher-nocreds")
        writePublisher(publisher, version, "http://127.0.0.1:$port/releases", withCredentials = false)
        val exit = runProcessExitCode(
            listOf(gradlew, "-p", publisher.toString(), "publish", "--no-daemon", "--console=plain"),
        )
        // Gradle's publish fails because the server returns 401 (SC-001 negative).
        org.junit.jupiter.api.Assertions.assertNotEquals(0, exit) { "no-credential publish should have failed" }
        org.junit.jupiter.api.Assertions.assertFalse(
            Files.exists(storageRoot.resolve("releases/com/example/widget/$version/widget-$version.jar")),
        ) { "an unauthorized artifact was stored" }
    }

    private fun runProcess(command: List<String>) {
        check(runProcessExitCode(command) == 0) { "process failed: ${command.joinToString(" ")}" }
    }

    private fun runProcessExitCode(command: List<String>): Int {
        val process = ProcessBuilder(command)
            .directory(rootProjectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "timed out: ${command.joinToString(" ")}\n$output" }
        if (process.exitValue() != 0) {
            System.err.println("[process exit ${process.exitValue()}] ${command.joinToString(" ")}\n$output")
        }
        return process.exitValue()
    }
}
