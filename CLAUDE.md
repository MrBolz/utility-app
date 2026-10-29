# Utility App

Mobile-first PWA (Vite + TypeScript, no UI framework), deployed to GitHub Pages, and wrapped as an
Android app with Capacitor (`android/`). Each module is a tab in the top menu; the first registered
module is the start tab.

- Features live in `src/modules/<id>/` as `UtilityModule` objects (`src/core/types.ts`)
  and are registered in `src/modules/index.ts`. Keep modules self-contained; shared code goes
  in `src/core/`.
- Routing is hash-based (`#/m/<id>` opens a tab; anything else opens the first tab) so it works under the Pages sub-path.
- Asset URLs must respect the Vite `base` (`BASE_PATH` env in CI): never hard-code `/utility-app/`.
- Before committing, run `npm run typecheck && npm test && npm run build`.
- Styling lives in `src/styles.css` with theme tokens on `:root` (light + dark). Reuse the shared
  building blocks (`.card-grid`, `.card`, `.card-header`, `.button`) for new modules.
- Native features go in a Capacitor plugin under `android/app/src/main/java/io/github/mrbolz/utilityapp/`,
  registered in `MainActivity`. Keep protocol logic in plain Java (no Android APIs) so its JUnit
  tests run anywhere; modules must check `Capacitor.isNativePlatform()` and degrade in the browser.
- `android/` is committed except generated files (`npx cap sync android` recreates them). CI builds
  and tests the APK; this container cannot download the Android SDK (`dl.google.com` is blocked).
