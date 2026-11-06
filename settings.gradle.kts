// Repositories: Google Maven and Maven Central ONLY (supply-chain rule,
// docs/SUPPLY-CHAIN.md). Plugins come from buildSrc (resolved from the same
// two repositories), so no Gradle Plugin Portal.
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dspi-android"

// Contracts / infrastructure (Wave 0) and Wave A packages
include(":core-api") //    pure JVM: DspiCoreApi + domain types (contract)
include(":fake-core") //   pure JVM: FakeDspiCore (BRIDGE)
include(":bridge") //      pure JVM: DspiBridge API + implementation (BRIDGE)
include(":ffi") //         Android lib: UniFFI bindings + libdspi_ffi.so + FfiDspiCore (FFI)
include(":usb") //         Android lib: UsbSession, foreground service, BootloaderLocator (USB)
include(":link") //        Android lib: DSPi Link client: discovery, session engine, tokens (LINK)
include(":ui-common") //   Android lib: theme, CustomSlider, ChannelPalette (HOME)

// Wave B UI feature packages
include(":feature-home")
include(":feature-graph")
include(":feature-matrix")
include(":feature-tools")
include(":feature-presets")
include(":feature-settings")
include(":feature-analysis")
include(":feature-firmware")

include(":app")
