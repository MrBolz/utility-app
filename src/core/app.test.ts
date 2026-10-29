import { afterEach, describe, expect, it, vi } from 'vitest';
import { startApp } from './app';
import { ModuleRegistry } from './registry';
import type { UtilityModule } from './types';

const fake = (id: string, mount: UtilityModule['mount'] = (el) => void (el.textContent = id)): UtilityModule => ({
  id,
  title: id.toUpperCase(),
  icon: '',
  mount,
});

describe('startApp', () => {
  let stop: (() => void) | undefined;
  afterEach(() => {
    stop?.();
    location.hash = '';
  });

  function navigate(hash: string) {
    location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  }

  function setup(...modules: UtilityModule[]) {
    const registry = new ModuleRegistry();
    modules.forEach((m) => registry.register(m));
    const root = document.createElement('div');
    stop = startApp(root, registry);
    const selected = () => root.querySelector('.tab[aria-selected="true"]')?.textContent;
    const content = () => root.querySelector('.app-main')?.textContent;
    return { root, selected, content };
  }

  it('shows a tab per module and opens the first one by default', () => {
    const { root, selected, content } = setup(fake('a'), fake('b'));

    const tabs = [...root.querySelectorAll<HTMLAnchorElement>('.tab')];
    expect(tabs.map((t) => t.getAttribute('href'))).toEqual(['#/m/a', '#/m/b']);
    expect(selected()).toBe('A');
    expect(content()).toBe('a');
  });

  it('switches tabs on navigation and falls back to the first tab for unknown routes', () => {
    const { selected, content } = setup(fake('a'), fake('b'));

    navigate('#/m/b');
    expect(selected()).toBe('B');
    expect(content()).toBe('b');

    navigate('#/m/missing');
    expect(selected()).toBe('A');
  });

  it('runs the module cleanup when switching tabs', () => {
    const cleanup = vi.fn();
    setup(fake('a'), fake('b', () => cleanup));

    navigate('#/m/b');
    expect(cleanup).not.toHaveBeenCalled();
    navigate('#/m/a');
    expect(cleanup).toHaveBeenCalledOnce();
  });
});
