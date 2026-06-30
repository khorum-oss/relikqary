package org.khorum.oss.relikquary.integration

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
 * Real-client proof of server-authoritative hosted metadata (feature 014, FR-012/SC-003): two versions of
 * an artifact are published by **independent** Gradle builds (each uploading only its own version's
 * metadata), then a real Gradle consumer resolves the dynamic version `+` (highest). For `+` to pick the
 * newest version the client must read Relikquary's merged `maven-metadata.xml` — proving an independent
 * publisher's upload didn't clobber the listing. The storage root is a real per-class `@TempDir`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HostedMetadataRoundTripTest {

    @LocalServerPort
    var port: Int = 0

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

    private val rootProjectDir = File(System.getProperty("relikquary.rootProjectDir"))
    private val gradlew = File(rootProjectDir, "gradlew").absolutePath

    @Test
    fun `dynamic version resolves the newest of two independently published versions`(@TempDir work: Path) {
        val url = "http://127.0.0.1:$port/releases"
        // Unique artifact id so this run starts from an empty listing.
        val artifactId = "widget${System.currentTimeMillis()}"

        // Two independent publisher builds, each unaware of the other's version.
        for (version in listOf("1.0.0", "2.0.0")) {
            val publisher = work.resolve("publisher-$version")
            writePublisher(publisher, artifactId, version, url)
            runProcess(listOf(gradlew, "-p", publisher.toString(), "publish", "--no-daemon", "--console=plain", "--stacktrace"))
        }

        // A real Gradle consumer resolving `+` must read the server's merged metadata to pick 2.0.0.
        val consumer = work.resolve("consumer")
        writeConsumer(consumer, artifactId, url)
        runProcess(
            listOf(
                gradlew, "-p", consumer.toString(), "resolveArtifact",
                "-g", work.resolve("gradle-home").toString(),
                "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
            ),
        )
        val resolved = consumer.resolve("build/resolved")
        assertTrue(Files.isRegularFile(resolved.resolve("$artifactId-2.0.0.jar"))) {
            "dynamic '+' did not resolve 2.0.0 — the merged listing was wrong"
        }
    }

    private fun writePublisher(dir: Path, artifactId: String, version: String, url: String) {
        Files.createDirectories(dir.resolve("src/main/java/com/example"))
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "$artifactId"""" + "\n")
        dir.resolve("src/main/java/com/example/Widget.java").writeText(
            "package com.example; public final class Widget { public String name() { return \"widget\"; } }\n",
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins { `java-library`; `maven-publish` }
            group = "com.example"
            version = "$version"
            publishing {
                publications { create<MavenPublication>("lib") { from(components["java"]); artifactId = "$artifactId" } }
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

    private fun writeConsumer(dir: Path, artifactId: String, url: String) {
        Files.createDirectories(dir)
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "consumer"""" + "\n")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins { base }
            repositories { maven { url = uri("$url"); isAllowInsecureProtocol = true } }
            val res = configurations.create("res")
            dependencies { add("res", "com.example:$artifactId:+") }
            tasks.register<Copy>("resolveArtifact") {
                from(res)
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
}
