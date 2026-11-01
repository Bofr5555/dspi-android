// ktlint (CLI from Maven Central, run as a JavaExec; no Plugin Portal plugin).
// `./gradlew :<module>:ktlintCheck`, `ktlintFormat` to fix. Rules: .editorconfig.
val ktlint: Configuration by configurations.creating

dependencies {
    ktlint(libs.lib("ktlint-cli"))
}

val ktlintArgs = listOf("src/**/*.kt", "*.kts") + GENERATED_GLOBS

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Lint Kotlin sources with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    args = listOf("--relative") + ktlintArgs
    inputs.files(fileTree("src") { include("**/*.kt") })
    outputs.upToDateWhen { true }
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-format Kotlin sources with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    args = listOf("--relative", "-F") + ktlintArgs
}
