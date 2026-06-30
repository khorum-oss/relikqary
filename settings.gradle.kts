pluginManagement {
    repositories {
        // Plugins resolve through relikquary's 'plugins' group (portal-first), NOT 'public'
        // (Central-first): the Plugin Portal and Maven Central ship byte-different jars for some
        // plugin coordinates, and verification-metadata.xml pins the portal's bytes.
        maven {
            url = uri("http://localhost:8081/plugins")
            isAllowInsecureProtocol = true
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "relikquary"

include("backend", "sandbox", "frontend")
