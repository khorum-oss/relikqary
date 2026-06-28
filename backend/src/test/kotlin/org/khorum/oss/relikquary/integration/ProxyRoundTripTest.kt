package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText

/**
 * Principle II round-trip for proxy and group repositories (feature 006): a real Gradle client resolves
 * a dependency through the `maven-central` proxy (pointed at a local [StubUpstream]); the bytes match
 * the upstream and are then cached locally, so a second resolve succeeds once the upstream no longer
 * serves the artifact (SC-001, SC-002). A second test resolves both a first-party (hosted) artifact and
 * a proxied dependency through the single `public` group URL (SC-003). Security is disabled to keep the
 * focus on resolution.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ProxyRoundTripTest {

    @LocalServerPort
    var port: Int = 0

    companion object {
        private val stub = StubUpstream().start()

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("RELIKQUARY_MAVEN_CENTRAL_URL") { stub.baseUrl }
        }
    }

    private val rootProjectDir = File(System.getProperty("relikquary.rootProjectDir"))
    private val gradlew = File(rootProjectDir, "gradlew").absolutePath

    @Test
    fun `gradle resolves through the proxy, then from cache when the upstream drops the artifact`(
        @TempDir work: Path,
    ) {
        val version = "1.0.0"
        val jar = seedArtifact("com.example", "proxied", version)
        val proxyUrl = "http://127.0.0.1:$port/maven-central"

        // 1. Cold cache: a real Gradle build resolves the dependency through the proxy.
        val first = resolveWith(work.resolve("c1"), work.resolve("home1"), proxyUrl, "com.example:proxied:$version")
        assertArrayEquals(jar, Files.readAllBytes(first), "proxy-resolved jar differs from upstream")

        // The proxy cached the bytes locally (FR-002/FR-003).
        val cached = storageRoot.resolve("maven-central/com/example/proxied/$version/proxied-$version.jar")
        assertTrue(Files.isRegularFile(cached)) { "proxy did not cache the fetched jar" }

        // 2. Upstream no longer serves the files; a fresh Gradle resolve still succeeds from the cache.
        stub.remove("com/example/proxied/$version/proxied-$version.jar")
        stub.remove("com/example/proxied/$version/proxied-$version.pom")
        val second = resolveWith(work.resolve("c2"), work.resolve("home2"), proxyUrl, "com.example:proxied:$version")
        assertArrayEquals(jar, Files.readAllBytes(second), "cached jar differs after upstream dropped it")
    }

    @Test
    fun `one group url resolves both a first-party and a proxied dependency`(@TempDir work: Path) {
        val proxiedJar = seedArtifact("org.acme", "tool", "2.0.0")
        publishFirstParty(work.resolve("publisher"), "3.0.0")
        val groupUrl = "http://127.0.0.1:$port/public"

        val consumer = work.resolve("consumer")
        writeConsumer(consumer, groupUrl, listOf("com.example:firstparty:3.0.0", "org.acme:tool:2.0.0"))
        runProcess(
            listOf(
                gradlew, "-p", consumer.toString(), "resolveArtifacts",
                "-g", work.resolve("group-home").toString(),
                "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
            ),
        )
        val resolved = consumer.resolve("build/resolved").toFile().list()?.toList().orEmpty()
        assertTrue(resolved.any { it == "tool-2.0.0.jar" }) { "proxied dep not resolved via group: $resolved" }
        assertTrue(resolved.any { it == "firstparty-3.0.0.jar" }) { "first-party not resolved via group: $resolved" }
        assertArrayEquals(
            proxiedJar,
            Files.readAllBytes(consumer.resolve("build/resolved/tool-2.0.0.jar")),
            "group-resolved proxied jar differs from upstream",
        )
    }

    /** Seeds a full Maven artifact (pom + jar + sha1) at the stub upstream and returns the jar bytes. */
    private fun seedArtifact(group: String, artifact: String, version: String): ByteArray {
        val base = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version"
        stub.seed("$base.pom", pomXml(group, artifact, version).toByteArray())
        val jar = jarBytes(artifact)
        stub.seed("$base.jar", jar)
        return jar
    }

    private fun publishFirstParty(publisher: Path, version: String) {
        Files.createDirectories(publisher.resolve("src/main/java/com/example"))
        publisher.resolve("settings.gradle.kts").writeText("""rootProject.name = "firstparty"""" + "\n")
        publisher.resolve("src/main/java/com/example/Tool.java").writeText(
            "package com.example;\npublic final class Tool {}\n",
        )
        publisher.resolve("build.gradle.kts").writeText(
            """
            plugins { `java-library`; `maven-publish` }
            group = "com.example"
            version = "$version"
            publishing {
                publications { create<MavenPublication>("lib") { from(components["java"]) } }
                repositories { maven { url = uri("http://127.0.0.1:$port/releases"); isAllowInsecureProtocol = true } }
            }
            """.trimIndent(),
        )
        runProcess(
            listOf(gradlew, "-p", publisher.toString(), "publish", "--no-daemon", "--console=plain", "--stacktrace"),
        )
    }

    private fun resolveWith(consumer: Path, gradleHome: Path, url: String, dependency: String): Path {
        writeConsumer(consumer, url, listOf(dependency))
        runProcess(
            listOf(
                gradlew, "-p", consumer.toString(), "resolveArtifacts",
                "-g", gradleHome.toString(),
                "--no-daemon", "--refresh-dependencies", "--console=plain", "--stacktrace",
            ),
        )
        val file = consumer.resolve("build/resolved").toFile().listFiles()?.firstOrNull { it.extension == "jar" }
        return checkNotNull(file) { "no jar resolved into build/resolved" }.toPath()
    }

    private fun writeConsumer(dir: Path, url: String, dependencies: List<String>) {
        Files.createDirectories(dir)
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "consumer"""" + "\n")
        val deps = dependencies.joinToString("\n") { """    add("res", "$it")""" }
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins { base }
            repositories { maven { url = uri("$url"); isAllowInsecureProtocol = true } }
            val res = configurations.create("res")
            dependencies {
            $deps
            }
            tasks.register<Copy>("resolveArtifacts") {
                from(res)
                into(layout.buildDirectory.dir("resolved"))
            }
            """.trimIndent(),
        )
    }

    private fun pomXml(group: String, artifact: String, version: String): String =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>$group</groupId>
          <artifactId>$artifact</artifactId>
          <version>$version</version>
        </project>
        """.trimIndent()

    private fun jarBytes(artifact: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\nImplementation-Title: $artifact\n".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun runProcess(command: List<String>) {
        val process = ProcessBuilder(command).directory(rootProjectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "timed out: ${command.joinToString(" ")}\n$output" }
        check(process.exitValue() == 0) { "process failed (${process.exitValue()}): ${command.joinToString(" ")}\n$output" }
    }
}
