// :ui-common — theme, shared components ported from macOS CustomSlider.swift,
// ChannelPalette.swift, Components.swift and the Linux qt/qml/components/ set,
// and the route table. Owned by package HOME; other UI packages request
// additions. No dependency on :bridge (pure UI + presentation logic).
plugins {
    id("dspi.android-compose")
}

android {
    namespace = "se.bofr.dspi.ui"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
