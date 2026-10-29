import type { ModuleRegistry } from './registry';
import { moduleHref, parseRoute, type Route } from './router';

export function startApp(root: HTMLElement, registry: ModuleRegistry): () => void {
  root.innerHTML = `
    <header class="app-header">
      <a class="back" href="#/" aria-label="Back to home" hidden>‹</a>
      <h1 class="title"></h1>
    </header>
    <main class="app-main"></main>
  `;
  const back = root.querySelector<HTMLAnchorElement>('.back')!;
  const title = root.querySelector<HTMLHeadingElement>('.title')!;
  const main = root.querySelector<HTMLElement>('.app-main')!;
  let cleanup: (() => void) | void;

  function render(route: Route): void {
    cleanup?.();
    cleanup = undefined;
    main.replaceChildren();

    const module = route.name === 'module' ? registry.get(route.id) : undefined;
    if (module) {
      back.hidden = false;
      title.textContent = module.title;
      document.title = `${module.title} · Utility App`;
      cleanup = module.mount(main);
    } else {
      back.hidden = true;
      title.textContent = 'Utilities';
      document.title = 'Utility App';
      main.append(renderHome(registry));
    }
  }

  const onHashChange = () => render(parseRoute(location.hash));
  window.addEventListener('hashchange', onHashChange);
  onHashChange();

  return () => {
    window.removeEventListener('hashchange', onHashChange);
    cleanup?.();
  };
}

function renderHome(registry: ModuleRegistry): HTMLElement {
  const grid = document.createElement('nav');
  grid.className = 'tile-grid';
  for (const module of registry.list()) {
    const tile = document.createElement('a');
    tile.className = 'tile';
    tile.href = moduleHref(module.id);

    const icon = document.createElement('span');
    icon.className = 'tile-icon';
    icon.textContent = module.icon;

    const name = document.createElement('span');
    name.className = 'tile-title';
    name.textContent = module.title;

    const description = document.createElement('span');
    description.className = 'tile-description';
    description.textContent = module.description;

    tile.append(icon, name, description);
    grid.append(tile);
  }
  return grid;
}
