// The SvelteKit UI is a standalone Node project; these tasks wrap its npm build so it can be built
// via Gradle and optionally bundled into the backend. It is intentionally NOT part of the default
// `build` lifecycle, keeping the frontend separable (run/deploy on its own with `npm` directly).

val npmExecutable = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

tasks.register<Exec>("npmCi") {
    description = "Installs frontend dependencies (npm ci)."
    group = "frontend"
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
    commandLine(npmExecutable, "ci")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}

tasks.register<Exec>("npmBuild") {
    description = "Builds the SvelteKit static app into frontend/build (base path /ui for bundling)."
    group = "frontend"
    dependsOn("npmCi")
    inputs.dir("src")
    inputs.dir("static")
    inputs.files("package.json", "svelte.config.js", "vite.config.ts", "tsconfig.json")
    outputs.dir("build")
    commandLine(npmExecutable, "run", "build")
    // The Gradle build targets the bundled location served by the backend at /ui.
    environment("BASE_PATH", "/ui")
}
