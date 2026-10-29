import type { UtilityModule } from './types';

const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

export class ModuleRegistry {
  private readonly modules = new Map<string, UtilityModule>();

  register(module: UtilityModule): void {
    if (!ID_PATTERN.test(module.id)) {
      throw new Error(`Invalid module id "${module.id}": use lowercase letters, digits and dashes`);
    }
    if (this.modules.has(module.id)) {
      throw new Error(`Module "${module.id}" is already registered`);
    }
    this.modules.set(module.id, module);
  }

  get(id: string): UtilityModule | undefined {
    return this.modules.get(id);
  }

  list(): UtilityModule[] {
    return [...this.modules.values()];
  }
}
