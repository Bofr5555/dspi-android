# Publishing on Google Play

Step-by-step for the owner. Everything that builds runs in the container
(`tools/dev.sh`, `tools/ci.sh`); nothing is installed on the host. Steps marked
**(owner)** need your Google account, money or a legal decision and cannot be
done by an agent.

Checked 2026-11-01. Play policies change; re-check the linked pages before
each step.

## 0. Before anything else: the blockers

Do not make the app public until all three are settled. Internal testing
(step 6) is fine before that, because it is not public distribution.

1. **The core has no licence.** The Rust core comes from
   `third_party/DSPi-Console-Linux` (https://github.com/WeebLabs/DSPi-Console-Linux).
   That repository has no LICENSE file; its Arch package says
   `license=('unknown')`. With no licence, all rights are reserved and you
   need the author's permission to distribute a binary built from it. **Ask
   WeebLabs** for permission, or better, ask them to add a licence (GPL-3.0
   would match their other repositories).
2. **GPL-3.0 obligations.** The app bundles the official DSPi firmware UF2s
   (WeebLabs/DSPi, GPL-3.0) and the AutoEQ database taken from the macOS client
   repository (WeebLabs/DSPi-Console, GPL-3.0; the AutoEQ data itself is MIT,
   jaakkopasanen/AutoEq). Large parts of the UI are ports of the GPL-3.0 macOS
   client (`docs/PORT-MAP.md`), so the app as a whole should be treated as
   GPL-3.0 too. Shipping on Play is compatible with the GPL, but you must:
   - publish the app's complete source under GPL-3.0 (this repository is
     private today; a public mirror of the released tag is enough) and add a
     LICENSE file;
   - for the firmware, point to its corresponding source: the
     `v1.1.6-beta5` tag of https://github.com/WeebLabs/DSPi;
   - show the licences and source links in the app (an "About / licences"
     screen) and in the store listing.
3. **The name "DSPi".** DSPi is WeebLabs' project name. A Play listing called
   "DSPi" by someone else can be rejected or removed under Play's
   impersonation policy. Either get WeebLabs' written OK (ideally as part of
   the same conversation as point 1), or name the app e.g. "Controller for
   DSPi (unofficial)" and say in the description that it is not affiliated
   with WeebLabs.

Suggested message to WeebLabs: you have built an Android port of DSPi Console
that uses DSPi-Console-Linux's core unchanged (plus two small patches,
`third_party/patches/`) and bundles the official firmware and AutoEQ database;
you would like to publish it on Google Play for free under GPL-3.0 with full
source; you ask (a) what licence DSPi-Console-Linux is under / for permission
to distribute it, and (b) whether they are OK with the name and listing text.

## 1. Play Console account **(owner)**

1. Create a developer account at https://play.google.com/console/signup
   (one-time USD 25 fee, identity verification with an official ID, and a
   verified contact email and phone number). Choose a *personal* account
   unless you have a registered organisation (an organisation account needs a
   D-U-N-S number but skips the testing requirement in step 7).
2. Accounts on Play also satisfy Google's Android developer verification
   (identity + registered package names), which certified Android devices
   start enforcing in 2026–2027 for apps installed from anywhere:
   https://support.google.com/android-developer-console/answer/16561738.

## 2. App ID: decide once **(owner)**

The application ID is `se.bofr.dspi` (`app/build.gradle.kts`,
`applicationId`). It is a placeholder chosen from your domain `bofr.se`.

**It can never change after the first upload to Play.** Play identifies the
app by it, forever: another ID is another app with no users, no reviews and no
update path. It is also the package name you register for developer
verification. Decide now. Reasonable options:

- `se.bofr.dspi` (keep): fine if you own `bofr.se` long term.
- something that does not imply the app is official, e.g.
  `se.bofr.dspicontrol`, if WeebLabs might one day publish their own
  Android app and would want an ID like `com.weeblabs.dspi`.

Changing it before the first upload is one line (`applicationId`); the
`namespace` values (`se.bofr.dspi.*`, the Kotlin packages) do not need to
change.

## 3. Create the upload key and back it up **(owner)**

Google Play App Signing (step 5) means Google holds the key that signs what
users install. You sign uploads with your own **upload key**.

```
tools/upload-key.sh
```

It asks for a password (empty = a random one) and writes, inside the
gitignored `keystore/` directory:

- `keystore/upload.jks` (PKCS12, RSA 4096, valid 10000 days, alias `upload`)
- `keystore/upload.properties` (the path, alias and password the release build reads)

**Back up both files now**, to two offline places (e.g. an encrypted USB stick
and a password manager attachment). Never commit them (`/keystore/` is in
`.gitignore`). If the upload key is lost or leaked, the Play Console account
owner can request an upload key reset (Play Console → Test and release → App
integrity → App signing); that takes a few days during which you cannot
publish updates, but users are not affected.

Print the upload certificate fingerprints (Play Console shows them after the
first upload; they should match):

```
tools/dev.sh keytool -list -v -keystore keystore/upload.jks -alias upload
```

## 4. Build the release bundle

1. Bump the version in `app/build.gradle.kts`, the only place it is set:
   `versionCode` (an integer; every upload must be higher than every earlier
   one, on every track) and `versionName` (what users see).
2. Build:

   ```
   tools/dev.sh ./gradlew :app:bundleRelease
   ```

   Output: `app/build/outputs/bundle/release/app-release.aab`, signed with the
   upload key. The build:
   - fails with a clear message if `keystore/upload.properties` or the keystore
     is missing (task `:app:checkUploadKey`);
   - always compiles `libdspi_ffi.so` with the Rust **release** profile (LTO,
     no debug info) for arm64-v8a, armeabi-v7a and x86_64, whatever
     `dspi.rustProfile` says (that property only affects debug builds);
   - has R8 / minification **off**: UniFFI's bindings reach the native
     library through JNA reflection. Enabling R8 would need keep rules for JNA and the
     generated bindings plus testing on a device, and would gain little because
     the native libraries and bundled assets dominate the size (about 11 MB
     Play download per device, 13.8 MB `.aab`);
   - targets API 36 (`gradle/libs.versions.toml`), Google Play's requirement
     for new apps and updates from 2026-08-31
     (https://developer.android.com/google/play/requirements/target-sdk).
3. Optional check of the same bundle, as CI does it:
   `tools/ci.sh release` (also part of `tools/ci.sh all`) builds with a throwaway key, runs
   `bundletool validate`, checks the manifest and the native libraries, and
   prints sizes. It deletes its key and bundle afterwards: **never upload a
   bundle built by `tools/ci.sh release`**.
4. Optional local install of the bundle on your phone (you, not an agent: the
   container has no USB access): `bundletool build-apks --mode=universal`
   in the container, then install the `.apk` from the extracted `.apks` with
   adb on the host.

## 5. Create the app and enrol in Play App Signing **(owner)**

1. Play Console → Create app. App name (see step 0.3), default language,
   "App", "Free". Free cannot later become paid.
2. Test and release → App integrity → App signing: keep **Use a
   Google-generated app signing key** (recommended). New apps must use Play
   App Signing; the first `.aab` you upload registers your upload key.

## 6. Internal testing first **(owner)**

1. Test and release → Testing → Internal testing → Create new release →
   upload `app-release.aab`. Release notes: what changed.
2. Testers: an email list (up to 100). Testers opt in through the link Play
   gives you, then install from the Play Store.
3. Internal releases are available within minutes and have no review. Test
   with the real DSPi board: attach → USB permission → connect, the
   foreground service notification, preset load/save, firmware update.

## 7. Closed testing (new personal accounts) **(owner)**

Personal accounts created after 2023-11-13 must run a **closed test with at
least 12 testers opted in continuously for at least 14 days** before they can
apply for production access
(https://support.google.com/googleplay/android-developer/answer/14151465).
A DSPi board is rare hardware, so recruit testers who own one (WeebLabs
community). Organisation accounts skip this.

## 8. App content (Policy → App content) **(owner)**

Answers specific to this app. Verify each against the build you upload
(`tools/ci.sh release` prints the permissions and the foreground service).

- **Privacy policy (URL required).** Host a short public page, e.g.:
  "DSPi for Android does not collect, store off-device or share any personal
  data. It has no internet access (it does not request the INTERNET
  permission). It talks only to a DSPi audio processor connected over USB.
  Settings and presets are stored on the device; files are read or written
  only where you choose them in the system file picker. Android backup is
  disabled." Plus a contact email.
- **Data safety.** "Does your app collect or share any of the required user
  data types?" → **No**. Justification: the merged manifest has no
  `android.permission.INTERNET` (CI checks this), so nothing can leave the
  device. No third-party SDKs (no analytics, crash reporting or ads).
  Data is not encrypted in transit because there is no transit; "users can
  request deletion" → not applicable (uninstall deletes everything,
  `allowBackup="false"`).
- **Ads:** no ads.
- **App access:** all features are available without login. Note for the
  reviewer: "Requires a DSPi USB audio processor (RP2040/RP2350). Without one,
  the connection strip offers *Demo (no device)*, which runs the full UI
  against a simulated device."
- **Foreground service declaration** (required for targetSdk 34+): type
  `connectedDevice`, permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Use
  case: "Keeps the USB connection to the user's DSPi audio processor while the
  app is in the background, so volume, mute and preset changes from the
  notification reach the device. Starts only once a DSPi connection is open
  (after the user grants USB permission); stops when the connection closes or
  the device is unplugged." Play asks for a short video link
  (e.g. unlisted YouTube) showing the notification while the device is
  connected.
- **Content rating:** IARC questionnaire, category "Utility / productivity";
  all answers "no" → rated for everyone.
- **Target audience:** 18+ (or 13+); not designed for children.
- **News app / health / financial / government:** no.

## 9. Store listing **(owner)**

- Short description (80 chars), full description (4000). State clearly:
  needs a DSPi board and a phone/tablet with USB host (OTG) support;
  unofficial if so (step 0.3); GPL-3.0 with a source link (step 0.2).
- Graphics: 512×512 icon, 1024×500 feature graphic, at least 2 phone
  screenshots (Demo mode is fine for screenshots), tablet screenshots if you
  want the app shown as tablet-ready.
- Category: Music & Audio (or Tools). Contact email required.
- The manifest declares `android.hardware.usb.host` with `required="false"`,
  so the app is offered to every device; on a device without USB host only
  the demo mode is useful.

## 10. Production **(owner)**

After step 7 (personal accounts): apply for production access, answer the
questions about your test, then create a production release (staged rollout
of e.g. 20% is sensible). Reviews take hours to several days.

## Each later update

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. `tools/ci.sh all`, `tools/ci.sh release`.
3. `tools/dev.sh ./gradlew :app:bundleRelease`, upload to internal testing,
   test, then promote the same release to closed/production.
4. Each August, Play raises the target-API requirement (one year after each
   Android release). Bump `compileSdk`/`targetSdk`/`buildTools` in
   `gradle/libs.versions.toml` and `ANDROID_PLATFORM`/`ANDROID_BUILD_TOOLS` in
   `container/Containerfile` together, and update `docs/SUPPLY-CHAIN.md`.

## API 36 behaviour changes checked for this app (2026-11-01)

From targetSdk 35 to 36:

- **Edge-to-edge:** enforced since targetSdk 35; 36 removes the
  `windowOptOutEdgeToEdgeEnforcement` opt-out. The app never used the opt-out;
  `AppScaffold` is a Material 3 `Scaffold`, which applies the system bar
  insets. No change.
- **Predictive back:** on by default at 36. The app already sets
  `enableOnBackInvokedCallback="true"` and every back path goes through the
  `OnBackPressedDispatcher` (NavHost, `BackHandler`s); nothing overrides
  `onBackPressed()` or handles `KEYCODE_BACK`. No change.
- **Large screens (sw ≥ 600dp):** orientation, resizability and aspect-ratio
  restrictions are ignored. The manifest sets none. No change (check the
  layouts on a tablet during internal testing).
- **Foreground service `connectedDevice`:** no new rules at 36 (the type and
  its permission have been required since 34; the start-time prerequisite is
  the USB permission, which the app holds before it starts the service).
  `dataSync`/`mediaProcessing` timeouts do not apply to this type.
- **JobScheduler quotas, intent redirection hardening:** the app uses neither
  JobScheduler nor implicit-intent forwarding.
