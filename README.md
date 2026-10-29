# Utility App

A personal multi-purpose toolbox for the phone, built as a Progressive Web App
(PWA). Each feature is a small, self-contained **module** shown as a tab in
the top menu. You add a feature by writing a new module.

Current modules:

- **Sensors:** live readings from the phone's motion sensors (acceleration,
  rotation rate, orientation and compass), location, battery and, where the
  browser supports them, magnetic field and ambient light. Web apps cannot read
  temperature sensors, so that card says so.

## Install on your phone

1. Open the deployed app: `https://mrbolz.github.io/utility-app/`
2. Add it to the home screen:
   - **iPhone (Safari):** Share → *Add to Home Screen*
   - **Android (Chrome):** ⋮ menu → *Install app* / *Add to Home screen*

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

## Adding a module

1. Create `src/modules/<id>/index.ts` exporting a `UtilityModule`
   (see `src/core/types.ts`; the `hello` module is the smallest example).
2. Add it to the list in `src/modules/index.ts`.
3. Add a test next to it, e.g. `src/modules/<id>/<id>.test.ts`.

`mount(container)` renders the module. If it starts timers, listeners or other
resources, return a cleanup function that stops them when the user switches to another tab.

## Deployment

`.github/workflows/ci.yml` typechecks, tests and builds every push and pull
request. Pushes to the default branch are also deployed to GitHub Pages.

One-time setup: in the repository on GitHub, go to **Settings → Pages** and set
**Source** to **GitHub Actions**.
