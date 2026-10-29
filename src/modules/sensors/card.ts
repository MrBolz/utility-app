import type { Reading, SensorSource } from './sources';

/** How long to wait for a first reading before assuming the device has no such sensor. */
export const DEFAULT_NO_DATA_TIMEOUT_MS = 3000;

type State = 'unsupported' | 'idle' | 'starting' | 'live' | 'no-data' | 'error';

const STATUS: Record<State, string> = {
  unsupported: 'Unavailable',
  idle: 'Off',
  starting: 'Starting',
  live: 'Live',
  'no-data': 'No data',
  error: 'Error',
};

export function formatValue(reading: Reading): string {
  if (reading.text !== undefined) return reading.text;
  if (reading.value === null || !Number.isFinite(reading.value)) return '—';
  const text = reading.value.toFixed(reading.decimals ?? 2);
  // Avoid "-0.00" flickering around zero.
  return Number(text) === 0 ? (0).toFixed(reading.decimals ?? 2) : text;
}

/** Left offset and width (both 0–1) of a bar drawn from zero to the reading's value. */
export function barExtent(value: number, min: number, max: number): { left: number; width: number } {
  const position = (v: number) => Math.min(1, Math.max(0, (v - min) / (max - min)));
  const zero = position(0);
  const current = position(value);
  return { left: Math.min(zero, current), width: Math.abs(current - zero) };
}

const hasData = (readings: Reading[]) => readings.some((r) => r.value !== null || r.text !== undefined);

export function createSensorCard(source: SensorSource): { element: HTMLElement; stop(): void } {
  const element = document.createElement('section');
  element.className = 'card sensor-card';
  element.innerHTML = `
    <header class="card-header">
      <span class="card-icon" aria-hidden="true"></span>
      <h2 class="card-title"></h2>
      <span class="status"></span>
    </header>
    <div class="card-body"></div>
  `;
  element.querySelector('.card-icon')!.textContent = source.icon;
  element.querySelector('.card-title')!.textContent = source.title;
  const status = element.querySelector<HTMLElement>('.status')!;
  const body = element.querySelector<HTMLElement>('.card-body')!;

  let stopSource: (() => void) | undefined;
  let stopped = false;
  let noDataTimer: ReturnType<typeof setTimeout> | undefined;
  let rows: { value: HTMLElement; fill?: HTMLElement }[] = [];
  let pending: Reading[] | undefined;
  let frame: number | undefined;

  function setState(state: State, message?: string, action?: { label: string; onClick(): void }) {
    element.dataset.state = state;
    status.textContent = STATUS[state];
    if (state === 'live') return;
    rows = [];
    body.replaceChildren();
    if (message) {
      const p = document.createElement('p');
      p.className = 'card-message';
      p.textContent = message;
      body.append(p);
    }
    if (action) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'button';
      button.textContent = action.label;
      button.addEventListener('click', action.onClick);
      body.append(button);
    }
  }

  function buildRows(readings: Reading[]) {
    const list = document.createElement('dl');
    list.className = 'readings';
    rows = readings.map((reading) => {
      const row = document.createElement('div');
      row.className = 'reading';
      const label = document.createElement('dt');
      label.textContent = reading.label;
      const value = document.createElement('dd');
      value.className = 'reading-value';
      row.append(label, value);
      let fill: HTMLElement | undefined;
      if (reading.min !== undefined && reading.max !== undefined) {
        const bar = document.createElement('div');
        bar.className = 'bar';
        fill = document.createElement('div');
        fill.className = 'bar-fill';
        bar.append(fill);
        row.append(bar);
      }
      list.append(row);
      return { value, fill };
    });
    body.replaceChildren(list);
  }

  function render() {
    frame = undefined;
    const readings = pending!;
    if (rows.length !== readings.length) buildRows(readings);
    readings.forEach((reading, i) => {
      const { value, fill } = rows[i];
      const text = formatValue(reading);
      const withUnit = reading.unit && reading.text === undefined && reading.value !== null;
      value.textContent = withUnit ? `${text}${reading.unit === '°' ? '' : ' '}${reading.unit}` : text;
      if (fill && reading.value !== null && reading.min !== undefined && reading.max !== undefined) {
        const { left, width } = barExtent(reading.value, reading.min, reading.max);
        fill.style.left = `${left * 100}%`;
        fill.style.width = `${width * 100}%`;
      }
    });
  }

  function onReading(readings: Reading[]) {
    if (stopped || !hasData(readings)) return;
    clearTimeout(noDataTimer);
    if (element.dataset.state !== 'live') setState('live');
    pending = readings;
    // Motion events arrive at up to 60 Hz; update the DOM at most once per frame.
    frame ??= requestAnimationFrame(render);
  }

  function onError(message: string) {
    if (stopped) return;
    clearTimeout(noDataTimer);
    stopSource?.();
    stopSource = undefined;
    setState('error', message);
  }

  function start() {
    setState('starting', 'Waiting for data…');
    noDataTimer = setTimeout(
      () => setState('no-data', 'No readings. This device may not have this sensor.'),
      source.noDataTimeoutMs ?? DEFAULT_NO_DATA_TIMEOUT_MS,
    );
    source.start({ reading: onReading, error: onError }).then(
      (stop) => (stopped ? stop() : (stopSource = stop)),
      (error: unknown) => onError(error instanceof Error ? error.message : String(error)),
    );
  }

  if (!source.isSupported()) {
    setState('unsupported', source.unsupportedReason ?? 'Not available on this device or browser.');
  } else {
    setState('idle');
    source.requiresGesture().then((needsTap) => {
      if (stopped) return;
      if (needsTap) setState('idle', 'Needs your permission.', { label: 'Enable', onClick: start });
      else start();
    });
  }

  return {
    element,
    stop() {
      stopped = true;
      clearTimeout(noDataTimer);
      if (frame !== undefined) cancelAnimationFrame(frame);
      stopSource?.();
    },
  };
}
