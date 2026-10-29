import type { UtilityModule } from '../core/types';
import { bridges } from './bridges';
import { network } from './network';
import { sensors } from './sensors';

/** Every module shown in the tab menu, in display order. Add new modules here. */
export const modules: UtilityModule[] = [sensors, network, bridges];
