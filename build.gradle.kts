plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
}

group = "org.khorum.oss.relikquary"

dependencies {
    kover(project(":backend"))
}

sonar {
    properties {
        property("sonar.projectKey", "khorum-relikquary")
        property("sonar.organization", "khorum-oss")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${project(":backend").layout.buildDirectory.get()}/reports/kover/report.xml",
        )
    }
}

// Container image builds (feature 013). Thin wrappers over `docker build` so operators have one obvious
// entry point and can pick the split images vs. the combined single image. The build context is the repo
// root; each task fails with a clear message if the Docker CLI is absent. No new dependency is added.
val dockerCli = "docker"

fun Exec.dockerBuild(dockerfile: String, tag: String) {
    group = "deployment"
    workingDir = rootDir
    commandLine(dockerCli, "build", "-f", dockerfile, "-t", tag, ".")
    // Surface a friendly message instead of a stack trace when Docker isn't installed.
    doFirst {
        val onPath = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .any { dir -> File(dir, dockerCli).canExecute() || File(dir, "$dockerCli.exe").canExecute() }
        require(onPath) { "Docker CLI ('$dockerCli') not found on PATH — install Docker to build images." }
    }
}

tasks.register<Exec>("dockerBuildBackend") {
    description = "Build the backend (API) image: relikquary-backend:local"
    dockerBuild("deploy/backend.Dockerfile", "relikquary-backend:local")
}

tasks.register<Exec>("dockerBuildFrontend") {
    description = "Build the frontend (UI) image: relikquary-frontend:local"
    dockerBuild("deploy/frontend.Dockerfile", "relikquary-frontend:local")
}

tasks.register<Exec>("dockerBuildCombined") {
    description = "Build the combined API+UI image (UI under /ui): relikquary:local"
    dockerBuild("deploy/combined.Dockerfile", "relikquary:local")
}

tasks.register("dockerBuildSplit") {
    group = "deployment"
    description = "Build both split images (backend + frontend)."
    dependsOn("dockerBuildBackend", "dockerBuildFrontend")
}
