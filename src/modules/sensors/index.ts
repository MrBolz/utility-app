import type { UtilityModule } from '../../core/types';
import { createSensorCard } from './card';
import { sources } from './sources';

export const sensors: UtilityModule = {
  id: 'sensors',
  title: 'Sensors',
  icon: '📡',
  mount(container) {
    const grid = document.createElement('div');
    grid.className = 'card-grid';
    // Sensors this device can't provide go last.
    const ordered = [...sources.filter((s) => s.isSupported()), ...sources.filter((s) => !s.isSupported())];
    const cards = ordered.map(createSensorCard);
    grid.append(...cards.map((card) => card.element));
    container.append(grid);
    return () => cards.forEach((card) => card.stop());
  },
};
