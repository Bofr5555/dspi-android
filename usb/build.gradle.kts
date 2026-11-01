// :usb — UsbManager attach/permission, fd ownership, foreground service,
// BootloaderLocator. Owned by package USB. Talks to the core only through
// the bridge (attachDevice/detachDevice); never touches audio interfaces.
plugins {
    id("dspi.android-library")
}

android {
    namespace = "se.bofr.dspi.usb"
}

dependencies {
    api(project(":bridge"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
