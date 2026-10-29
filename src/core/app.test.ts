import { afterEach, describe, expect, it, vi } from 'vitest';
import { startApp } from './app';
import { ModuleRegistry } from './registry';
import { hello } from '../modules/hello';

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

  it('shows a tile per module and opens a module from its route', () => {
    const registry = new ModuleRegistry();
    registry.register(hello);
    const root = document.createElement('div');
    stop = startApp(root, registry);

    const tiles = root.querySelectorAll<HTMLAnchorElement>('.tile');
    expect(tiles).toHaveLength(1);
    expect(tiles[0].getAttribute('href')).toBe('#/m/hello');

    navigate('#/m/hello');
    expect(root.querySelector('.title')?.textContent).toBe('Hello World');
    expect(root.querySelector('.hello-greeting')?.textContent).toBe('Hello, World!');
  });

  it('runs the module cleanup when navigating away', () => {
    const cleanup = vi.fn();
    const registry = new ModuleRegistry();
    registry.register({ id: 'x', title: 'X', description: '', icon: '', mount: () => cleanup });
    const root = document.createElement('div');
    stop = startApp(root, registry);

    navigate('#/m/x');
    expect(cleanup).not.toHaveBeenCalled();
    navigate('#/');
    expect(cleanup).toHaveBeenCalledOnce();
  });
});
