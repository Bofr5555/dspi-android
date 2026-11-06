# DSPi Console for Android — copyright, licence and additional terms

Android port: Copyright (C) 2026 Bofr5555 (https://github.com/Bofr5555).
DSPi, the DSPi firmware and the official DSPi Console applications: Copyright (C) Weeb Labs
(https://github.com/WeebLabs).

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License version 3 as published by the Free Software Foundation, with the
additional terms below. It is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
PURPOSE. See [`LICENSE`](LICENSE) (GPL-3.0) for details.

## Additional terms (GNU GPL v3, section 7)

These terms apply to the material Bofr5555 added to this work (section 7, paragraph 2). They
do not restrict the rights Weeb Labs' GPL-3.0 code carries on its own.

1. **Attributions** (7b). Keep the author attributions — "Made with love by Weeb Labs",
   "Android port by Bofr5555" — and the Licences screen in the app's About page, or in an
   equivalent place a user can reach from the app.
2. **Modified versions are marked** (7c). A distributed modified version must be clearly
   marked as different from the original. It must not be presented as this app or as an
   official Weeb Labs app: it uses its own application ID, its own app name and its own icon.
3. **No trademark rights** (7e). This licence grants no rights under trademark law to the
   names "DSPi" and "DSPi Console" (Weeb Labs) or to this app's icon and store graphics.

Everything else the GPL-3.0 allows stays allowed: you may study, change, rebuild and share the
app, including for a fee, as long as you pass the same freedoms on and publish your source.

## Third-party components

The app includes or links:

| Component | Licence | Source |
|---|---|---|
| DSPi-Console-Linux core (Rust protocol core, `third_party/`) | MIT (per Weeb Labs; licence file pending upstream) | https://github.com/WeebLabs/DSPi-Console-Linux |
| DSPi Console for macOS — UI, behaviour and texts ported from it; AutoEQ database | GPL-3.0 | https://github.com/WeebLabs/DSPi-Console |
| DSPi Link protocol spec, policy table and test fixtures (`fixtures/link/`, tests only); the Link client follows the macOS Console's `Link/` | MIT | https://github.com/WeebLabs/DSPi-Link |
| DSPi firmware images (`feature-firmware` assets, v1.1.6-beta5) | GPL-3.0 | https://github.com/WeebLabs/DSPi/tree/v1.1.6-beta5 |
| AutoEq headphone measurements (in the AutoEQ database) | MIT | https://github.com/jaakkopasanen/AutoEq |
| libusb (compiled into the native library) | LGPL-2.1-or-later | https://github.com/libusb/libusb |
| UniFFI | MPL-2.0 | https://github.com/mozilla/uniffi-rs |
| Other Rust crates | MIT / Apache-2.0 / Unlicense (as chosen) | listed with their licence files in `feature-settings/src/main/assets/licenses/rust-crates.txt` |
| JNA | Apache-2.0 (dual-licensed with LGPL-2.1) | https://github.com/java-native-access/jna |
| AndroidX, Jetpack Compose, Kotlin, kotlinx, Guava listenablefuture, Okio, JSpecify | Apache-2.0 | https://developer.android.com/jetpack/androidx |

The full licence texts ship in the app (Settings › About › Licences) and live in
`feature-settings/src/main/assets/licenses/`. Regenerate the Rust list with
`tools/dev.sh python3 tools/gen_third_party_licenses.py`.
