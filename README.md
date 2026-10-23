<p align="center">
  <img src="branding/opendash_logo.svg" width="128" height="128" alt="OpenDash logo">
</p>

<h1 align="center">Navigator Gen3</h1>

<p align="center">
  Google Maps turn-by-turn, phone notifications and ride analytics on the Gen-3 KTM and Husqvarna dash.<br>
  Local-first. No account, analytics, ads or data collection.
</p>

<p align="center">
  <a href="../../releases/latest"><strong>Download the latest APK</strong></a>
  &nbsp;|&nbsp;
  <a href="#compatibility-and-setup">Compatibility</a>
  &nbsp;|&nbsp;
  <a href="#known-issues">Known issues</a>
  &nbsp;|&nbsp;
  <a href="LICENSE">MIT licence</a>
</p>

<p align="center"><em>Unofficial beta. Not affiliated with KTM or Husqvarna.</em></p>

---


## What it does

Three jobs, all on your phone, with no account or project-operated server.

| | |
|---|---|
| **Push to the dash** | Google Maps turn-by-turn, phone notifications and an on-demand now-playing view, mirrored onto the bike's own display over Bluetooth. |
| **Pull from the handlebar** | The bike's four handlebar buttons control media, calls and Google Assistant, and can read the latest message aloud. |
| **Record and analyse the ride** | Rides record themselves as standard GPX, then come back as a map, charts, detected events, a smoothness score and a fuel estimate. |

---

## What works today

**Silent, ignition-linked reconnect.** Once paired, cycling the ignition reconnects without another
"add device" confirmation. When the bike remains unreachable for three minutes, the foreground service
stops and its notification disappears. A low-power, address-filtered BLE scan then waits for the dash to
advertise and starts the service again at the next ignition-on. Pairing keys survive normal app updates.

**Google Maps turn-by-turn, on your bike's dash.** Start navigating and the Gen-3 centre display shows
the turn arrow, distance, road name, ETA and remaining distance. The maneuver arrow — including
roundabout exits, U-turns, keep/fork and ramps — is recognised by a small on-device neural network
trained on Google Maps' own icons, because Maps puts no maneuver *text* in its notification.

**Automatic bike detection.** On connect it reads the dash's model, firmware and capabilities and shows
what it found in the connection banner. Nothing to configure.

**KTM and Husqvarna.** They share the same dash electronics, and the whole app re-themes to match your
bike — KTM dark and orange, Husqvarna light and blue. You pick on first run and can switch any time.

| Brand select (KTM) | Brand select (Husqvarna) |
|---|---|
| ![](docs/screenshots/brand-select-ktm.png) | ![](docs/screenshots/brand-select-husqvarna.png) |

**Handlebar remote — music and hands-free actions.** Hold **Back** on the dash to enter its remote-control
mode; hold it again to return the buttons to the bike. There is no app-side mode picker or Accessibility
service.

| Button | Tap | Double-tap | Hold |
|---|---|---|---|
| **Up** | volume up | — | Google Assistant |
| **Down** | volume down | — | show `MUSIC` and `Title - Artist` on the dash for five seconds, then restore the latest turn |
| **Set** | play / pause | — | read the latest non-navigation notification aloud |
| **Back** | next track (after a 350 ms window) | previous track | reserved by the dash for RC on/off |

While the phone is ringing, **Set** answers and **Back** rejects (or silences the ringer if Android
refuses third-party call rejection). A rising/falling chime confirms RC on/off, every accepted press has
an audible tick, and a low tone confirms a hold. A Back press left over from entering RC mode is ignored.
Presses are available to the app only while the dash reports RC mode as active.

**Turn sounds.** Optional stereo approach beeps — left ear for a left turn, right for a right — that
speed up as you near the corner and go quiet when you stop. Adjustable volume, swappable channels.

**Overspeed alert.** Uses phone GPS speed and plays on the alarm channel at full volume, so it is audible
over wind and engine and sounds through Do Not Disturb. Background use requires Location set to
**Allow all the time**. Diagnostics log the configured limit, first speed fix, per-minute top speed and
any tone failure.

**On-demand now playing.** Hold **Down** to replace the centre guidance lines with the active media
session's title and artist for five seconds. Navigation updates received during the peek are retained and
the newest arrow and instruction are restored afterwards.

**Notification mirroring** with group chats and summary notifications filtered out so only real messages
get through, emoji and unsupported glyphs stripped so text reads cleanly, and a one-tap quick-mute.
Hold **Set** to read the latest mirrored, non-navigation notification with on-device text-to-speech;
music is temporarily ducked while it speaks.

**Ride recording and analytics.** Rides save as standard GPX and come back as a real map coloured by
speed with traffic stretches highlighted, a master time slider that scrubs the map marker and every chart
together, harsh-acceleration / hard-braking / rough-road detection rolled into a 0–100 smoothness score,
a fuel-cost estimate, and a traffic map that aggregates every ride into a picture of where you routinely
lose time.

**Diagnostics that earn their keep:** symbol testing and turn-icon calibration to match glyphs to your
exact dash, a per-slot dash text playground, and shareable logs — which is how bug reports get made.

| Ride tools and sounds | Engine-vibration calibration |
|---|---|
| ![](docs/screenshots/riding-settings.png) | ![](docs/screenshots/engine-calibration.png) |

---

## Coming in the next build

Built and running, **not yet in the download above**. Field-testing on a bike is what stands between
these and a release.

**Ride replay.** Press play and the ride runs itself at 30×: a heading-oriented marker glides along the
route with the camera following, the trail behind it at full strength and the road ahead dimmed, and
every chart cursor advancing in step. The charts gained smooth curves, pinch-to-zoom into a shared time
window, and tappable peak/dip values.

**Crawl vs signal traffic.** Traffic is now two distinct things, using engine state recorded per GPS
point: creeping with the engine on is a crawl, fully stopped with the engine on is a signal, and engine
off is never traffic — so a coffee stop stops being counted as a jam.

**A traffic prediction mode** built only from your own rides: a frequency model over location ×
weekday/weekend × time-of-day that needs at least four passes through the same spot before it will say
anything, and always shows the sample count.

**In-app music screen** laid out one-to-one with the handlebar remote, so the screen teaches the buttons.

**Ride backup and restore.** Back up every ride and its sensor data to a single `.zip`, and restore it on
a new phone — rides already on the phone are skipped, never overwritten. Switching phones no longer means
losing your history or the traffic map built from it.

**A rebuilt interface** across every screen: glove-sized targets, a focus ring on everything the handlebar
remote can reach, and Settings split into eight labelled groups.

| Home | Rides | Recording & backup |
|---|---|---|
| ![](docs/screenshots/next-home.png) | ![](docs/screenshots/next-rides.png) | ![](docs/screenshots/next-settings-recording.png) |

| Pairing | Onboarding |
|---|---|
| ![](docs/screenshots/next-pairing.png) | ![](docs/screenshots/next-onboarding-rides.png) |

---

## Privacy

- No accounts. No analytics. No servers. No data collection. No ads.
- The turn-icon model, notification handling, text-to-speech, ride recording and analytics run on-device.
- Location, routes and notifications stay on the phone unless you explicitly share a GPX, backup or log.
- The only network feature is optional Gemini notification summarisation. It is disabled unless you enter
  your own API key. When enabled, opening the Notifications screen sends those notification app names,
  titles and message text to Google's Gemini API.
- Diagnostic logs are also copied to `Downloads/OpenDash`. They can contain notification banner text,
  song titles and the bike's Bluetooth address; inspect them before sharing.
- Android cloud backup is disabled. There are no analytics, crash-reporting, advertising or tracking SDKs.

---

## Compatibility and setup

- Tested on a **KTM 390 Adventure (2025)** and **KTM Duke 250 Gen-3**. It targets the Gen-3 "connected"
  dash that KTM's and Husqvarna's own apps use for turn-by-turn, so it should work on other 2020+ Gen-3
  bikes — but 1290 / 890 / SMC and the Husqvarna models are still lightly tested. Reports and logs are
  very welcome.
- You likely need the connectivity / Tech Pack active. The turn-by-turn dash feature appears to be gated
  behind that activation; without it the dash may not expose the nav feature to pair with. Not fully
  confirmed — testers with and without it, please report.
- **Pairing tips** if the bike doesn't show up: put the dash into "add device" mode from its own
  connectivity menu first, make sure Bluetooth is on, and note that the app also lists devices already
  bonded to your phone. The first pairing needs the physical "add device" confirmation on the dash.
- **Android 16:** sideloaded apps have notification access blocked until you enable "Allow restricted
  settings" (App info → three-dot menu) before granting it.

## Known issues

1. First pairing needs the physical "add device" confirmation on the dash. Expected, once per bike. (The
   very first reconnect after installing asks once more — that's when the keys are captured — then every
   ride after that is silent.)
2. Turn-icon accuracy depends on your Google Maps version and your specific dash. If an icon looks wrong,
   correct it with the Symbol Test and Turn-icon Calibration screens.
3. Husqvarna support has never been verified on a physical Husqvarna. It runs on the same dash protocol
   and should work, but all testing so far has been on a KTM.
4. Engine detection uses the phone's motion sensor, needs a short calibration, and varies by phone and
   mount. Without it, traffic detection falls back to a time-based guess.
5. Rough-road detection needs a ride recorded on v0.3.0 or later — older GPX files fall back to GPS-only
   event detection.
6. The full analytics map needs a free Google Maps API key added at build time (below). Without one,
   everything still works via the built-in route sketch.
7. Full vehicle telemetry (RPM, gear, coolant, fuel and TPMS) requires the dash's PRPC/telemetry service.
   The Duke 250 Gen-3 tested here does not expose that service, so this data cannot be read from that bike.
   Two other undocumented services return only zeroes and are deliberately left untouched.
8. This is a beta. If something crashes or misbehaves, please open an issue or share your logs from
   Settings → Diagnostics, and include your bike model and firmware if you can.

---

## Install (no build needed)

Grab the APK from the [latest release](../../releases/latest), copy it to your phone and open it (you'll
need to allow "install from unknown sources"). First-ever pairing needs the physical "add device"
confirmation on the bike's dash.

## Build it yourself

Prerequisites: Android Studio (latest) or the command-line Android SDK; JDK 17 (bundled with Android
Studio); Android SDK Platform 34 and build-tools.

```bash
git clone <this-repo>
cd <this-repo>

# point Gradle at your SDK (either export this or create local.properties)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
# echo "sdk.dir=$HOME/Android/Sdk"        > local.properties   # Linux

./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

**Optional — the full analytics map.** Get a free key at console.cloud.google.com, enable "Maps SDK for
Android", restrict it to package `com.navigator.app` plus your keystore's SHA-1, then add
`MAPS_API_KEY=your-key` to `local.properties` before building.

On the phone:

- Enable **Notification access** to mirror navigation and notifications, control active media sessions,
  and read the latest message aloud.
- Set Location to **Allow all the time** for ride recording and overspeed alerts while the phone is locked.
- Allow **Display over other apps** so a handlebar hold can launch Assistant from the background.
- Disable battery optimisation so Android permits the bike's BLE advertisement to wake the connection
  service reliably.
- `ANSWER_PHONE_CALLS` is used only for Set-to-answer and Back-to-reject while ringing. The Accessibility
  service is no longer used.

## The turn-icon model (`ml/`)

The classifier is trained from scratch on Google Maps' own publicly shipped, self-labeled maneuver icons
— no proprietary weights. `ml/train_maneuver_model.py` reproduces
`app/src/main/res/raw/maneuver_model.tflite` locally or on free Google Colab. The labeled dataset is
generated on-device by a debug exporter (see [`ml/README.md`](ml/README.md)).

## Contributing — help wanted

This is an early, community-driven hobby project and contributions are very welcome, whether you ride one
of these bikes or just enjoy reverse-engineering and Android/BLE work. Good places to start:

- **Full vehicle telemetry** on bike models whose dash actually exposes the PRPC/telemetry service.
- **Older "MY RIDE" bikes** need a second connection path (Bluetooth Classic + JSON).
- **Test on a physical Husqvarna** and report how it goes.
- More nav apps (Waze, OsmAnd, HERE) and notification sources.
- Improve the turn-icon model with real captured icons.
- Testing on other Gen-3 bikes and firmware, and reporting what works.
- UI polish, docs, translations.

Open an [issue](../../issues) with logs and details, or send a pull request. Please keep the project's
core promise intact: **on-device only, no data collection.**

## Disclaimer

Unofficial hobby project for Gen-3 KTM and Husqvarna dashes, provided as-is with no warranty. Not
affiliated with KTM or Husqvarna. Ride responsibly — do not interact with your phone while riding.

## License

[MIT](LICENSE)
