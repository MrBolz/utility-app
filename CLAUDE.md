# Utility App

Mobile-first PWA (Vite + TypeScript, no UI framework), deployed to GitHub Pages.

- Features live in `src/modules/<id>/` as `UtilityModule` objects (`src/core/types.ts`)
  and are registered in `src/modules/index.ts`. Keep modules self-contained; shared code goes
  in `src/core/`.
- Routing is hash-based (`#/` home, `#/m/<id>` module) so it works under the Pages sub-path.
- Asset URLs must respect the Vite `base` (`BASE_PATH` env in CI): never hard-code `/utility-app/`.
- Before committing, run `npm run typecheck && npm test && npm run build`.
