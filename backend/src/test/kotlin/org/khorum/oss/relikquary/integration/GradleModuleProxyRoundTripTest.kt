package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Gradle Module Metadata through a proxy (feature 011, FR-005/SC-002): a feature-variant library is
 * published to a hosted `releases` repo, then resolved through a `maven-central` proxy whose upstream is
 * that same instance's `releases` (proxy-of-self, via a fixed port). The proxy caches the versioned
 * `.module` and re-serves it, so a second resolve is a cache hit and still drives variant selection,
 * byte-identical to what was published.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class GradleModuleProxyRoundTripTest {

    private val rootProjectDir = File(System.getProperty("relikquary.rootProjectDir"))
    private val gradlew = File(rootProjectDir, "gradlew").absolutePath

    @Test
    fun `gradle resolves a feature-variant module through a proxy, cached and re-served`(@TempDir work: Path) {
        val version = "1.0.0-proxy-r${System.currentTimeMillis()}"
        val hosted = "http://127.0.0.1:$selfPort/releases"
        val proxy = "http://127.0.0.1:$selfPort/maven-central"

        // Publish to the hosted repo.
        val publisher = work.resolve("publisher")
        writePublisher(publisher, version, hosted)
        runProcess(listOf(gradlew, "-p", publisher.toString(), "publish", "--no-daemon", "--console=plain", "--stacktrace"))
        val publishedExtra = Files.readAllBytes(publisher.resolve("build/libs/widget-$version-extra.jar"))

        // First resolve through the proxy (cache miss → fetches + caches the `.module` and jars).
        val first = work.resolve("consumer1")
        writeConsumer(first, version, proxy)
        runProcess(consumerCmd(first, work.resolve("gradle-home-1")))
        assertArrayEquals(publishedExtra, Files.readAllBytes(first.resolve("build/resolved/widget-$version-extra.jar")))

        // The proxy cached the versioned `.module` (FR-005).
        assertTrue(
            Files.isRegularFile(storageRoot.resolve("maven-central/com/example/widget/$version/widget-$version.module")),
        ) { "proxy did not cache the .module" }

        // Second resolve from a fresh Gradle home → served from cache, still byte-identical.
        val second = work.resolve("consumer2")
        writeConsumer(second, version, proxy)
        runProcess(consumerCmd(second, work.resolve("gradle-home-2")))
        assertArrayEquals(publishedExtra, Files.readAllBytes(second.resolve("build/resolved/widget-$version-extra.jar")))
    }

    private fun consumerCmd(dir: Path, gradleHome: Path): List<String> =
        listOf(
            gradlew, "-p", dir.toString(), "resolveExtra", "-g", gradleHome.toString(),
            "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
        )

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
        private val selfPort: Int = ServerSocket(0).use { it.localPort }

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { selfPort }
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("RELIKQUARY_MAVEN_CENTRAL_URL") { "http://127.0.0.1:$selfPort/releases" }
            registry.add("relikquary.security.users[0].username") { "ci" }
            registry.add("relikquary.security.users[0].password") { "{noop}ci-secret" }
            registry.add("relikquary.security.users[0].roles[0]") { "PUBLISH" }
        }
    }
}
