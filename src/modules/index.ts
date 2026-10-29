import type { UtilityModule } from '../core/types';
import { hello } from './hello';
import { sensors } from './sensors';

/** Every module shown in the tab menu, in display order. Add new modules here. */
export const modules: UtilityModule[] = [sensors, hello];
