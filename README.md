# Brutus

A personal multi-purpose toolbox for the phone, built as a Progressive Web App
(PWA). Each feature is a small, self-contained **module** shown as a tab in
the top menu. You add a feature by writing a new module.

Current modules:

- **Sensors:** live readings from the phone's motion sensors (acceleration,
  rotation rate, orientation and compass), location, battery and, where the
  browser supports them, magnetic field and ambient light. Web apps cannot read
  temperature sensors, so that card says so.
- **Network** (Android app only): the devices on your Wi-Fi with names, type,
  model, services and open ports, plus connection details (Wi-Fi name, signal,
  band and channel, IP, router, DNS, internet latency) and nearby Wi-Fi networks.
- **Bridges** (Android app only): connections between apps and devices. The
  first bridge switches selected Tuya Wi-Fi plugs **on** when an alarm from the
  Clock app rings. Brutus talks to the plugs directly on your Wi-Fi, without the
  Tuya app or cloud (see [Bridges setup](#bridges-setup)).

## Install on your phone

1. Open the deployed app: `https://mrbolz.github.io/utility-app/`
2. Add it to the home screen:
   - **iPhone (Safari):** Share → *Add to Home Screen*
   - **Android (Chrome):** ⋮ menu → *Install app* / *Add to Home screen*

### Android app

The Network tab needs the Android app, because browsers cannot look into the
local network. It is the same app wrapped with [Capacitor](https://capacitorjs.com)
plus a native network scanner.

1. On the phone, download
   [brutus.apk](https://github.com/MrBolz/utility-app/releases/download/android-latest/brutus.apk)
   (CI publishes a new build for every push to `main`).
2. Open it and allow installing apps from your browser when Android asks.
3. To update, download and open the new APK the same way; your data stays.

To show the Wi-Fi name and nearby networks, allow location access when the app
asks: Android only reveals those to apps with location permission.

### Bridges setup

Brutus switches the plugs with Tuya's local protocol, which needs each plug's
secret **local key**. Tuya only hands these out through its developer cloud, so
fetch them once (a computer makes step 3 easier, because the Tuya app has to
scan a QR code):

1. Sign up at [platform.tuya.com](https://platform.tuya.com) (free).
2. **Cloud → Development → Create Cloud Project**: any name, industry *Smart
   Home*, development method *Smart Home*, data center **Central Europe** (the
   data center of a Swiss Tuya / Smart Life account). Keep the suggested API
   services (IoT Core, Authorization).
3. In the project: **Devices → Link Tuya App Account → Add App Account**, then
   scan the QR code with the Tuya Smart app (**Me** → scan icon, top right).
4. Copy **Access ID** and **Access Secret** from the project's **Overview**.
5. In Brutus: **Bridges → Tuya devices → Import from Tuya cloud**, paste both,
   pick the data center, **Import devices**. Then **Find on network** fills in
   each plug's IP address and protocol version.
6. Tick the plugs in **Alarm → Sockets**, turn the bridge on, and fix anything
   the **Setup checklist** marks (exact alarms, unrestricted battery use, and
   "Pause app activity if unused", which would otherwise stop the bridge after a
   few months without opening Brutus).

The keys keep working when the developer project's free trial ends. You only
need the cloud again after re-pairing a plug in the Tuya app (its key changes);
if the import then fails with a subscription error, extend the IoT Core trial
under **Cloud → My Services**.

Brutus reads the time of your next alarm from Android and sets its own exact
alarm for the same moment, so it needs no notification access. Snoozing runs the
bridge again, which is harmless because it only switches plugs on. Many plugs
accept only one local connection at a time: if a plug reports that it is busy,
close other apps or integrations that control it locally.

### Web app

It then opens full-screen like a native app, works offline, and updates itself
after each deploy (reopen the app once or twice to load a new version).

## Development

Requires Node.js 22.12+.

```sh
npm install
npm run dev        # dev server (also reachable from your phone on the same Wi-Fi)
npm test           # unit tests (Vitest)
npm run typecheck  # TypeScript
npm run build      # production build into dist/
```

Android (needs JDK 21 and the Android SDK; CI does this for you):

```sh
npm run android:sync                         # build the web app and copy it into android/
cd android && ./gradlew testDebugUnitTest assembleDebug
```

The native code is in `android/app/src/main/java/io/github/mrbolz/utilityapp/`:
`network/` (Network tab), `tuya/` (Tuya local protocol, discovery and cloud key
import) and `bridge/` (alarm trigger, foreground run, storage, Bridges plugin).
`network/` (except `NetworkInfoReader` and `NetworkScannerPlugin`) and `tuya/`
are plain Java without Android APIs, so their unit tests run on any JVM. The
Tuya tests use vectors generated with [tinytuya](https://github.com/jasonacox/tinytuya).

## Adding a module

1. Create `src/modules/<id>/index.ts` exporting a `UtilityModule`
   (see `src/core/types.ts`; the `sensors` module is a small example).
2. Add it to the list in `src/modules/index.ts`.
3. Add a test next to it, e.g. `src/modules/<id>/<id>.test.ts`.

`mount(container)` renders the module. If it starts timers, listeners or other
resources, return a cleanup function that stops them when the user switches to another tab.

## Deployment

`.github/workflows/ci.yml` typechecks, tests and builds every push and pull
request, and builds the Android APK. Pushes to the default branch are also
deployed to GitHub Pages, and the APK is published as the `android-latest`
release. The APK is signed with the committed `android/app/debug.keystore`, so
each build installs as an update of the previous one.

One-time setup: in the repository on GitHub, go to **Settings → Pages** and set
**Source** to **GitHub Actions**.
