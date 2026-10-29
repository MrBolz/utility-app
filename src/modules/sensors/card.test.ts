import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { barExtent, createSensorCard, DEFAULT_NO_DATA_TIMEOUT_MS, formatValue } from './card';
import type { SensorListener, SensorSource } from './sources';

describe('formatValue', () => {
  it('formats numbers, missing values and text', () => {
    expect(formatValue({ label: '', value: 1.234 })).toBe('1.23');
    expect(formatValue({ label: '', value: 1.5, decimals: 0 })).toBe('2');
    expect(formatValue({ label: '', value: -0.001 })).toBe('0.00');
    expect(formatValue({ label: '', value: null })).toBe('—');
    expect(formatValue({ label: '', value: null, text: 'Charging' })).toBe('Charging');
  });
});

describe('barExtent', () => {
  it('draws from zero to the value', () => {
    expect(barExtent(5, -10, 10)).toEqual({ left: 0.5, width: 0.25 });
    expect(barExtent(-10, -10, 10)).toEqual({ left: 0, width: 0.5 });
    expect(barExtent(90, 0, 360)).toEqual({ left: 0, width: 0.25 });
    expect(barExtent(99, -10, 10)).toEqual({ left: 0.5, width: 0.5 });
  });
});

describe('createSensorCard', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function fakeSource(overrides: Partial<SensorSource> = {}) {
    let listener: SensorListener | undefined;
    const stop = vi.fn();
    const source: SensorSource = {
      id: 'fake',
      title: 'Fake',
      icon: '',
      isSupported: () => true,
      requiresGesture: async () => false,
      start: vi.fn(async (l: SensorListener) => {
        listener = l;
        return stop;
      }),
      ...overrides,
    };
    return { source, stop, emit: (value: number | null) => listener!.reading([{ label: 'X', value, unit: 'm', min: -1, max: 1 }]) };
  }

  const state = (card: { element: HTMLElement }) => card.element.dataset.state;
  const flush = () => vi.advanceTimersByTimeAsync(20);

  it('explains when a sensor is unsupported', () => {
    const card = createSensorCard(fakeSource({ isSupported: () => false, unsupportedReason: 'Nope' }).source);
    expect(state(card)).toBe('unsupported');
    expect(card.element.textContent).toContain('Nope');
  });

  it('starts automatically and shows live readings', async () => {
    const { source, emit } = fakeSource();
    const card = createSensorCard(source);
    await flush();
    expect(state(card)).toBe('starting');

    emit(0.5);
    await flush();
    expect(state(card)).toBe('live');
    expect(card.element.querySelector('.reading-value')?.textContent).toBe('0.50 m');
    expect(card.element.querySelector<HTMLElement>('.bar-fill')?.style.width).toBe('25%');
  });

  it('waits for a tap when the sensor needs permission', async () => {
    const { source } = fakeSource({ requiresGesture: async () => true });
    const card = createSensorCard(source);
    await flush();
    expect(source.start).not.toHaveBeenCalled();

    card.element.querySelector('button')!.click();
    expect(source.start).toHaveBeenCalledOnce();
  });

  it('reports no data when readings stay empty', async () => {
    const { source, emit } = fakeSource();
    const card = createSensorCard(source);
    await flush();
    emit(null);
    await vi.advanceTimersByTimeAsync(DEFAULT_NO_DATA_TIMEOUT_MS);
    expect(state(card)).toBe('no-data');

    emit(1);
    await flush();
    expect(state(card)).toBe('live');
  });

  it('shows start errors', async () => {
    const card = createSensorCard(fakeSource({ start: () => Promise.reject(new Error('Permission denied')) }).source);
    await flush();
    expect(state(card)).toBe('error');
    expect(card.element.textContent).toContain('Permission denied');
  });

  it('stops the sensor when the card is removed', async () => {
    const { source, stop } = fakeSource();
    const card = createSensorCard(source);
    await flush();
    card.stop();
    expect(stop).toHaveBeenCalledOnce();
  });
});
