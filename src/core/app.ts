import type { ModuleRegistry } from './registry';
import { moduleHref, parseRoute, type Route } from './router';

export function startApp(root: HTMLElement, registry: ModuleRegistry): () => void {
  root.innerHTML = `
    <header class="app-header">
      <h1 class="app-title">Utilities</h1>
      <nav class="tabs" role="tablist" aria-label="Modules"></nav>
    </header>
    <main class="app-main" role="tabpanel"></main>
  `;
  const tabs = root.querySelector<HTMLElement>('.tabs')!;
  const main = root.querySelector<HTMLElement>('.app-main')!;
  const tabLinks = new Map<string, HTMLAnchorElement>();
  let cleanup: (() => void) | void;

  for (const module of registry.list()) {
    const tab = document.createElement('a');
    tab.className = 'tab';
    tab.href = moduleHref(module.id);
    tab.setAttribute('role', 'tab');

    const icon = document.createElement('span');
    icon.className = 'tab-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = module.icon;

    const label = document.createElement('span');
    label.textContent = module.title;

    tab.append(icon, label);
    tabs.append(tab);
    tabLinks.set(module.id, tab);
  }

  function render(route: Route): void {
    cleanup?.();
    cleanup = undefined;
    main.replaceChildren();

    // Unknown routes and the bare start URL open the first tab.
    const module = (route.name === 'module' && registry.get(route.id)) || registry.list()[0];
    for (const [id, tab] of tabLinks) {
      const selected = id === module?.id;
      tab.setAttribute('aria-selected', String(selected));
      if (selected) tab.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
    }
    if (!module) return;

    document.title = `${module.title} · Utility App`;
    cleanup = module.mount(main);
  }

  const onHashChange = () => render(parseRoute(location.hash));
  window.addEventListener('hashchange', onHashChange);
  onHashChange();

  return () => {
    window.removeEventListener('hashchange', onHashChange);
    cleanup?.();
  };
}
