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
    testImplementation(project(":fake-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
