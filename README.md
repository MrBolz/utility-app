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

The native code is in `android/app/src/main/java/io/github/mrbolz/utilityapp/network/`.
Everything except `NetworkInfoReader` and `NetworkScannerPlugin` is plain Java
without Android APIs, so its unit tests run on any JVM.

## Adding a module

1. Create `src/modules/<id>/index.ts` exporting a `UtilityModule`
   (see `src/core/types.ts`; the `hello` module is the smallest example).
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
