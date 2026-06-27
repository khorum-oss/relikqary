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
