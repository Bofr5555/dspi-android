// :ffi — the real core for Android: libdspi_ffi.so (cargo-ndk) + UniFFI Kotlin
// bindings (generated at build time) + FfiDspiCore (generated adapter,
// checked in). Owned by package FFI.
//
// The Rust steps need cargo/cargo-ndk/the NDK: run Gradle via tools/dev.sh.
plugins {
    id("dspi.android-library")
}

android {
    namespace = "se.bofr.dspi.ffi"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi/kotlin"))
    // Per build type: debug -> rust/jniLibs-debug (profile from dspi.rustProfile,
    // default debug), release -> rust/jniLibs-release (ALWAYS the cargo release
    // profile, so a Play .aab can never ship an unoptimised .so).
    sourceSets["debug"].jniLibs.srcDir(layout.buildDirectory.dir("rust/jniLibs-debug"))
    sourceSets["release"].jniLibs.srcDir(layout.buildDirectory.dir("rust/jniLibs-release"))
}

dependencies {
    api(project(":core-api"))
    // UniFFI's Kotlin runtime needs JNA (the AAR carries libjnidispatch.so per ABI).
    implementation("${libs.jna.get().module}:${libs.versions.jna.get()}@aar")
}

val repoRoot = rootProject.layout.projectDirectory
val rustDir = repoRoot.dir("rust")
val rustProfile = providers.gradleProperty("dspi.rustProfile").getOrElse("debug")
val cargoTarget = providers.environmentVariable("CARGO_TARGET_DIR").getOrElse(rustDir.dir("target").asFile.path)

fun requireContainer() {
    check(System.getenv("DSPI_IN_CONTAINER") == "1") {
        "The :ffi Rust build runs inside the build container: use tools/dev.sh ./gradlew ..."
    }
}

val stageCore by tasks.registering(Exec::class) {
    description = "Copy the core submodule and apply third_party/patches (tools/stage-core.sh)."
    workingDir = repoRoot.asFile
    commandLine("tools/stage-core.sh")
    doFirst { requireContainer() }
}

val rustSources =
    fileTree(rustDir) {
        include("dspi-ffi/**", "uniffi-bindgen/**", "Cargo.toml", "Cargo.lock", ".staged/dspi-core/core/src/**")
    }

val cargoBuildHost by tasks.registering(Exec::class) {
    description = "Build libdspi_ffi for the host (UniFFI reads its metadata)."
    dependsOn(stageCore)
    workingDir = rustDir.asFile
    commandLine("cargo", "build", "-p", "dspi-ffi", "--locked")
    inputs.files(rustSources)
    outputs.file("$cargoTarget/debug/libdspi_ffi.so")
    doFirst { requireContainer() }
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    description = "Generate the UniFFI Kotlin bindings from the host library."
    dependsOn(cargoBuildHost)
    val out = layout.buildDirectory.dir("generated/uniffi/kotlin")
    workingDir = rustDir.asFile
    inputs.file("$cargoTarget/debug/libdspi_ffi.so")
    outputs.dir(out)
    doFirst {
        requireContainer()
        delete(out)
    }
    commandLine(
        "cargo", "run", "-q", "--locked", "-p", "uniffi-bindgen", "--",
        "generate", "--library", "$cargoTarget/debug/libdspi_ffi.so",
        "--language", "kotlin", "--no-format",
        "--out-dir", out.get().asFile.path
    )
}

fun registerCargoNdkBuild(variant: String, profile: String) =
    tasks.register<Exec>("cargoNdkBuild${variant.replaceFirstChar { it.uppercase() }}") {
        description = "Cross-compile libdspi_ffi.so ($profile profile) for the $variant variant with cargo-ndk."
        dependsOn(stageCore)
        val out = layout.buildDirectory.dir("rust/jniLibs-$variant")
        workingDir = rustDir.asFile
        inputs.files(rustSources)
        inputs.property("profile", profile)
        outputs.dir(out)
        doFirst {
            requireContainer()
            delete(out) // cargo-ndk only adds files; drop stale libraries
        }
        val profileArgs = if (profile == "release") listOf("--release") else emptyList()
        commandLine(
            listOf(
                "cargo", "ndk",
                "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
                "-P", libs.versions.minSdk.get(),
                "-o", out.get().asFile.path,
                "build", "-p", "dspi-ffi", "--locked"
            ) + profileArgs
        )
    }

// dspi.rustProfile (gradle.properties) only picks the DEBUG variant's profile;
// the release variant is always built with --release.
val cargoNdkBuildDebug = registerCargoNdkBuild("debug", rustProfile)
val cargoNdkBuildRelease = registerCargoNdkBuild("release", "release")

tasks.named("preBuild") { dependsOn(generateUniffiKotlin) }
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(cargoNdkBuildDebug) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(cargoNdkBuildRelease) }
