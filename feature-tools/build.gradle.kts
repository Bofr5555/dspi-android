// :feature-tools — Wave B package TOOLS (docs/PACKAGES.md).
// Ports: LoudnessView, CrossfeedView, VolumeLevellerView, PsychoacousticBassView, SubharmonicSynthView, TubeModellerView, UpmixerView, OutputLimiterView, ToolParameters.swift
// Depends on contracts only: :bridge (DspiBridge) and :ui-common.
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.feature.tools"
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":ui-common"))
    // BackHandler for the internal tool sub-routes (decision E3).
    implementation(libs.androidx.activity.compose)
    // @Previews (src/debug only) run against FakeDspiCore; never in release.
    debugImplementation(project(":fake-core"))
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
