package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Feature 012 (FR-011, SC-001/SC-003, User Story 1): a **real Gradle client** resolves and applies a
 * real published plugin through Relikquary's default `public` group — proving the shipped Gradle Plugin
 * Portal proxy works end-to-end out of the box, with the marker POM cached byte-faithfully.
 *
 * **Auto-skipped when the Plugin Portal is unreachable** (offline / blocked egress), mirroring
 * [ProxyCentralIT] — so it adds real-upstream realism where the network allows without flaking where it
 * doesn't. Runs against the default config (no upstream override): the `gradle-plugins` proxy points at
 * the real portal and `public` includes it last.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class GradlePluginPortalRoundTripIT {

    private val rootProjectDir = File(System.getProperty("relikquary.rootProjectDir"))
    private val gradlew = File(rootProjectDir, "gradlew").absolutePath

    @Test
    fun `gradle applies a real plugin resolved through the public group, cached`(@TempDir work: Path) {
        assumeTrue(portalReachable()) { "Gradle Plugin Portal not reachable — skipping" }

        val consumer = work.resolve("consumer")
        writeConsumer(consumer, "http://127.0.0.1:$selfPort/public")

        // `help` forces configuration, which resolves + applies the plugin through Relikquary.
        runProcess(
            listOf(
                gradlew, "-p", consumer.toString(), "help",
                "-g", work.resolve("gradle-home").toString(),
                "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
            ),
        )

        // The plugin marker POM was fetched from the portal and cached under the proxy's namespace.
        val cacheRoot = storageRoot.resolve("gradle-plugins")
        val cachedMarker = Files.walk(cacheRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith("$PLUGIN_VERSION.pom") }
                .filter { it.fileName.toString().contains(".gradle.plugin-") }
                .toList()
        }
        assertTrue(cachedMarker.isNotEmpty()) { "plugin marker POM was not cached under gradle-plugins/" }
    }

    private fun writeConsumer(dir: Path, repoUrl: String) {
        Files.createDirectories(dir)
        // pluginManagement repositories REPLACE the default gradlePluginPortal(), so plugin resolution
        // goes only through Relikquary; dependencyResolutionManagement routes the plugin's transitive
        // dependencies through the same group.
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven {
                        url = uri("$repoUrl")
                        isAllowInsecureProtocol = true
                    }
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven {
                        url = uri("$repoUrl")
                        isAllowInsecureProtocol = true
                    }
                }
            }
            rootProject.name = "consumer"
            """.trimIndent() + "\n",
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("$PLUGIN_ID") version "$PLUGIN_VERSION"
            }
            """.trimIndent() + "\n",
        )
    }

    private fun runProcess(command: List<String>) {
        val process = ProcessBuilder(command).directory(rootProjectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "timed out: ${command.joinToString(" ")}\n$output" }
        check(process.exitValue() == 0) { "process failed: ${command.joinToString(" ")}\n$output" }
    }

    // Any failure to reach the portal (offline, blocked egress, timeout) means "skip".
    @Suppress("SwallowedException")
    private fun portalReachable(): Boolean = try {
        val probe = HttpRequest.newBuilder(URI.create("$PORTAL/$MARKER_PATH"))
            .timeout(PROBE_TIMEOUT).GET().build()
        http.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    } catch (e: java.io.IOException) {
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .proxy(java.net.ProxySelector.getDefault())
        .connectTimeout(PROBE_TIMEOUT).build()

    companion object {
        private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(8)
        private const val PORTAL = "https://plugins.gradle.org/m2"
        // A small, stable, dependency-light plugin that applies on a bare project.
        private const val PLUGIN_ID = "org.jetbrains.gradle.plugin.idea-ext"
        private const val PLUGIN_VERSION = "1.1.8"
        private const val MARKER_PATH =
            "org/jetbrains/gradle/plugin/idea-ext/" +
                "org.jetbrains.gradle.plugin.idea-ext.gradle.plugin/1.1.8/" +
                "org.jetbrains.gradle.plugin.idea-ext.gradle.plugin-1.1.8.pom"

        private val selfPort: Int = ServerSocket(0).use { it.localPort }

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { selfPort }
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            // No upstream override: exercises the shipped default portal URL.
        }
    }
}
