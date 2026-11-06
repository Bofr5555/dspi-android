# Publishing on Google Play

Step-by-step for the owner. Everything that builds runs in the container
(`tools/dev.sh`, `tools/ci.sh`); nothing is installed on the host. Steps marked
**(owner)** need your Google account, money or a legal decision and cannot be
done by an agent.

Checked 2026-11-01. Play policies change; re-check the linked pages before
each step.

## 0. Licensing and name: status

Settled with Weeb Labs (2026-11-02): the app may be published; the
DSPi-Console-Linux core and the consoles are MIT per the author (the core's
LICENSE file is still pending upstream — keep the written OK until it lands;
GitHub currently shows the macOS DSPi-Console repo as GPL-3.0, which the app
satisfies either way).

The app is **GPL-3.0 with additional terms** (`LICENSE`, `NOTICE.md`):
attributions kept, modified versions marked and renamed, no trademark rights to
"DSPi" / the icon (GPL-3.0 §7 b, c, e). That does not forbid paid or ad-filled
copies — the GPL allows charging — but such copies must publish their source,
cannot use the name or icon, and a copy that does can be reported to Google
Play (impersonation / IP complaint).

Remaining before a public release:
- make the source public for each released version (this repository, or a
  public mirror of the release tag) — the in-app "Source code" link and the
  store listing point to https://github.com/Bofr5555/dspi-android;
- keep the third-party notices current: `tools/ci.sh settings` fails when
  `assets/licenses/rust-crates.txt` is stale
  (`tools/dev.sh python3 tools/gen_third_party_licenses.py` regenerates it);
  update `THIRD_PARTY` in `LicensesView.kt` and `NOTICE.md` when a JVM
  dependency or bundled asset changes;
- firmware source: the bundled UF2s are WeebLabs/DSPi `v1.1.6-beta5`; bump the
  link with the images;
- store listing: say the app is GPL-3.0, link the source, credit Weeb Labs.

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

## 2. App ID: decided

The application ID is **`se.bofr.dspi_console_android`** (`app/build.gradle.kts`,
`applicationId`), registered in Play Console on 2026-11-03 with the app name
"DSPi Console for Android". **It can never change after the first upload:**
Play identifies the app by it, forever. The `namespace` values
(`se.bofr.dspi.*`, the Kotlin packages) are internal and stay as they are.
Builds with the old ID `se.bofr.dspi` (test releases up to 0.1.0-hil5) are a
different app on a phone: uninstall them before installing a newer build.

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

- **Privacy policy (URL required, in the listing and in the app).** Done:
  [`PRIVACY.md`](../PRIVACY.md), public at
  https://github.com/Bofr5555/dspi-android/blob/main/PRIVACY.md; the app links
  it from Settings › About › Licence. Enter that URL in Play Console (App
  content → Privacy policy). Keep it in step with what the app does.
- **Data safety.** *DSPi Link (approved by the owner 2026-11-06, together with
  the new `PRIVACY.md`); for builds with DSPi Link the answer below replaces
  the "no INTERNET" one.* "Does your app
  collect or share any of the required user data types?" → **No**.
  Justification: the app now holds `android.permission.INTERNET` (plus
  `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`; `tools/ci.sh release`
  asserts exactly this permission set), used **only** by the optional DSPi Link
  feature, which is off by default, to talk to a DSPi hub the user selects on
  their own local network (a computer running DSPi Console, or a DSPi bridge).
  Nothing is sent to the developer or to third parties, and nothing goes to the
  internet. Play defines collection as transmitting data off the device to the
  developer or a third party; the data that goes to the user's own hub (DSPi
  settings, the phone model as client name, a pairing token the hub issued) is
  not collected in that sense, and none of it is a Play user-data type. No
  third-party SDKs (no analytics, crash reporting or ads). "Is all of the user
  data collected by your app encrypted in transit?": not applicable (none is
  collected); the DSPi Link protocol itself is plaintext on the LAN, which the
  privacy policy states. "Users can request deletion" → not applicable
  (uninstall deletes everything, `allowBackup="false"`).
  *Owner decision D8 (2026-11-06): answer "No" as above.* The
  conservative alternative, kept for reference if Play support disagrees: declare "Device
  or other IDs" and "App activity" as collected (not shared, not encrypted in
  transit, optional: only with DSPi Link on). When in doubt, ask Play support
  before submitting. Existing testers will see the new "full network access"
  permission on update.
- **Ads:** no ads.
- **App access:** all features are available without login. Note for the
  reviewer: "Requires a DSPi USB audio processor (RP2040/RP2350). Without one,
  the connection strip offers *Demo (no device)*, which runs the full UI
  against a simulated device."
- **Foreground service declaration** (required for targetSdk 34+): type
  `connectedDevice`, permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`. *DSPi Link
  (approved 2026-11-06) adds the network case; re-submit the declaration and
  video with the first DSPi Link build.* Use case: "Keeps the connection to the user's
  DSPi audio processor while the app is in the background, so volume, mute and
  preset changes from the notification reach the device. The DSPi is either
  connected over USB, or (optional DSPi Link feature) reached over the local
  network through a hub the user selected. Starts only once a DSPi connection
  is open (after the user grants USB permission, or after the user connects to
  a hub); stops when the connection closes, the device is unplugged or the user
  disconnects." Android's `connectedDevice` type covers "external devices that
  require a Bluetooth, NFC, IR, USB, or network connection"; for the network
  case the app holds `CHANGE_WIFI_MULTICAST_STATE`, one of the Android 14
  runtime prerequisites for this type. Play asks for a short video link
  (e.g. unlisted YouTube) showing the notification while the device is
  connected; show both a USB and a DSPi Link connection.
- **Content rating:** IARC questionnaire, category "Utility / productivity";
  all answers "no" → rated for everyone.
- **Target audience:** 18+ (or 13+); not designed for children.
- **News app / health / financial / government:** no.

## 9. Store listing **(owner)**

- DSPi Link (approved 2026-11-06): the full description has, after the second
  paragraph: "Optional DSPi Link (off by default): control a DSPi that is
  plugged into a Mac running DSPi Console, or wired to a DSPi bridge, over your
  local network. The connection is not encrypted; use it on networks you
  trust." (added to `store/play/listing/en-US/full_description.txt` on
  `feature/link`; it ships with the first DSPi Link build.)
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
