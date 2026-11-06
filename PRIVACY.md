# Privacy policy — DSPi Console for Android

> **DRAFT for the owner's approval (2026-11-06, branch `feature/link`).** This version describes
> the optional DSPi Link feature. It must be approved, and published at the URL below, before any
> build with DSPi Link is uploaded to Google Play. Until then the policy published from `main`
> applies.

*Effective [date of the first Play release with DSPi Link]. Applies to the app "DSPi Console for
Android" (`se.bofr.dspi_console_android`) by Bofr5555.*

**In short: the app collects no personal data and sends nothing to the developer or to any third
party.** It has no accounts, no ads, no analytics and no crash reporting. It uses the network only
for the optional **DSPi Link** feature, which is **off until you turn it on**, and then only to talk
to a DSPi hub that you choose on your own local network.

## What the app does with data

- **Your DSPi device.** The app talks to a DSPi audio processor connected to your phone over USB,
  or, with DSPi Link, through a hub on your network (see below). It reads and changes the device's
  settings (filters, routing, volume, presets) and shows its live levels and status, including the
  device's serial number. This data stays between the phone and the device (or the hub).
- **Settings stored on the phone.** App preferences (display options, graph settings, which input
  channels you linked, per device serial number, and the DSPi Link settings) are stored in the
  app's private storage on your phone. They are not backed up to the cloud (Android backup is
  turned off for this app) and are removed when you uninstall the app.
- **Files you choose.** When you import or export presets or filter files, or install firmware from
  a file, the app reads or writes only the file you pick in Android's file picker. When you share a
  preset, a temporary copy is placed in the app's cache for the app you share it with.
- **Bundled data.** The AutoEQ headphone database and the official DSPi firmware images ship inside
  the app; using them involves no download.
- **Notifications.** While a DSPi is connected (over USB or DSPi Link), the app shows a notification
  with volume, mute and next-preset controls (Android requires a visible notification for this). It
  contains nothing but the device status.

## DSPi Link (optional, local network)

DSPi Link lets the app control a DSPi that is plugged into another computer running DSPi Console
(a "hub"), or wired to a DSPi bridge (an ESP32 board), over your local network. It is **off by
default**: until you turn it on in Settings › Networking, the app opens no network connection and
does not look for hubs.

When you turn it on:

- **Looking for hubs.** While Settings › Networking is open and "Find hubs automatically" is on,
  the app listens for DSPi hubs announcing themselves on your local network (DNS-SD/Bonjour,
  service `_dspi._tcp`). You can also add a hub by its address.
- **What is sent, and to whom.** Only to the hub you tap, on your network:
  - this phone's **client name**, which the hub shows in its list of clients. It is your phone's
    model name (for example "Pixel 9") unless you change it; the app never uses your name or your
    device's name by default;
  - the **PIN** you type when you pair with a hub that asks for one, and afterwards the **access
    token** the hub gave the app;
  - the **DSPi commands and settings** you change, exactly as they would go over USB.

  Nothing is sent to the developer, to Weeb Labs or to anyone else, and nothing goes to the
  internet. (If you reach your hub through a VPN such as Tailscale, the traffic goes through that
  VPN as you configured it.)
- **What is received.** The hub's name and id, the list of DSPi devices it shares (their serial
  numbers, names and firmware versions), and the devices' settings and live status.
- **Stored on the phone.** For each hub you pair with: its id, its name and the access token,
  encrypted with a key kept in the Android Keystore, in the app's private storage. Hubs you add by
  address and your client name are stored as settings. Settings › Networking › Paired Hubs lets you
  forget a hub; uninstalling the app deletes everything.
- **Security.** DSPi Link traffic is **not encrypted**: the DSPi Link protocol uses plain WebSocket
  on the local network, so anyone on the same network can see it, including the PIN and the token.
  Use DSPi Link only on networks you trust, turn on PIN pairing on the hub, and use a VPN if you
  want to reach your hub from elsewhere. The app connects only to a hub you tap; it never connects
  on its own to a hub you have not chosen.
- **Background.** While the app controls a DSPi through a hub, the same connection notification as
  for USB keeps the connection open in the background; disconnecting removes it.

## Permissions

The app asks Android for: USB access to the DSPi (when you plug it in), notifications (for the
connection notification), a foreground service of type "connected device", and, for DSPi Link,
**network access** ("full network access" on Google Play; used only as described above), **network
state** (to reconnect when Wi-Fi comes back) and **Wi-Fi multicast** (to find hubs).

## Links

The About page has links to Weeb Labs' pages (YouTube, GitHub, Discord, Patreon, Ko-fi) and to the
source code. They open in your browser; those sites have their own privacy policies.

## Children

The app is not directed at children and collects no data from anyone.

## Changes and contact

Changes to this policy are published in this file, with its history in the public repository:
https://github.com/Bofr5555/dspi-android/blob/main/PRIVACY.md. Questions: open an issue at
https://github.com/Bofr5555/dspi-android/issues.
