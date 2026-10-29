import { describe, expect, it } from 'vitest';
import { ModuleRegistry } from './registry';
import type { UtilityModule } from './types';

const fake = (id: string): UtilityModule => ({ id, title: id, description: '', icon: '', mount() {} });

describe('ModuleRegistry', () => {
  it('lists modules in registration order', () => {
    const registry = new ModuleRegistry();
    registry.register(fake('b'));
    registry.register(fake('a'));
    expect(registry.list().map((m) => m.id)).toEqual(['b', 'a']);
    expect(registry.get('a')?.id).toBe('a');
  });

  it('rejects duplicate ids', () => {
    const registry = new ModuleRegistry();
    registry.register(fake('a'));
    expect(() => registry.register(fake('a'))).toThrow(/already registered/);
  });

  it('rejects ids that are not URL slugs', () => {
    expect(() => new ModuleRegistry().register(fake('Not Valid'))).toThrow(/Invalid module id/);
  });
});
