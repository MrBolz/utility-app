import type { AlarmBridge, BridgeRun, BridgesPlugin, BridgesState, CloudCredentials, DeviceChanges, DiscoveredDevice, SettingsTarget } from './plugin';

/** What an action belongs to; its progress, error and success note show up next to the control that started it. */
export type Scope = 'load' | 'bridge' | 'run' | 'setup' | 'discover' | 'cloud' | 'device-form' | `switch:${string}`;

export interface BridgesStoreState {
  /** Last state the native plugin reported; undefined until the first load. */
  data?: BridgesState;
  /** Running actions per scope (a count: quick taps can overlap). */
  pending: Partial<Record<Scope, number>>;
  /** Readable message of the last failed action per scope. */
  errors: Partial<Record<Scope, string>>;
  /** Short success messages per scope, e.g. "Imported 3 devices". */
  notes: Partial<Record<Scope, string>>;
  /** Result of the last "Test now". */
  lastTest?: BridgeRun;
  /** Devices the last "Find on network" heard. */
  found?: DiscoveredDevice[];
}

/** Bridge data that outlives the view, so results and running actions survive switching tabs. */
export class BridgesStore {
  state: BridgesStoreState = { pending: {}, errors: {}, notes: {} };
  private readonly subscribers = new Set<() => void>();
  private bridgeRequests = 0;

  constructor(private readonly plugin: BridgesPlugin) {}

  subscribe(subscriber: () => void): () => void {
    this.subscribers.add(subscriber);
    return () => this.subscribers.delete(subscriber);
  }

  isBusy(scope: Scope): boolean {
    return (this.state.pending[scope] ?? 0) > 0;
  }

  async refresh(): Promise<void> {
    await this.run('load', () => this.plugin.getState(), (data) => ({ data }));
  }

  /** Saves changes to the alarm bridge, e.g. { enabled: true } or { deviceIds: [...] }. */
  async updateBridge(changes: Partial<AlarmBridge>): Promise<void> {
    const data = this.state.data;
    if (!data) return;
    const bridge = { ...data.bridge, ...changes };
    // Show the change at once so quick taps build on each other; only the latest answer counts.
    const request = ++this.bridgeRequests;
    this.update({ data: { ...data, bridge } });
    const saved = await this.run('bridge', () => this.plugin.saveBridge(bridge), (state) => (request === this.bridgeRequests ? { data: state } : {}));
    if (!saved) await this.refresh();
  }

  async runNow(): Promise<void> {
    await this.run('run', () => this.plugin.runNow(), ({ state, run }) => ({ data: state, lastTest: run }));
  }

  async openSettings(target: SettingsTarget): Promise<void> {
    await this.run('setup', () => this.plugin.openSettings({ target }), () => ({}));
  }

  /** Resolves true once the credentials are saved (the secret is then only kept natively). */
  saveCloud(credentials: CloudCredentials): Promise<boolean> {
    return this.run('cloud', () => this.plugin.saveCloud(credentials), (data) => ({ data }));
  }

  async importFromCloud(): Promise<void> {
    await this.run('cloud', () => this.plugin.importFromCloud(), ({ state, imported }) => ({
      data: state,
      ...this.note('cloud', imported === 0 ? 'No devices found in the cloud project' : `Imported ${count(imported, 'device')}`),
    }));
  }

  async discover(): Promise<void> {
    await this.run('discover', () => this.plugin.discover(), ({ state, found }) => ({ data: state, found }));
  }

  saveDevice(changes: DeviceChanges): Promise<boolean> {
    const known = this.state.data?.devices.some((device) => device.id === changes.id);
    return this.run('device-form', () => this.plugin.saveDevice(changes), (data) => ({
      data,
      found: this.state.found?.map((device) => (device.id === changes.id ? { ...device, known: true } : device)),
      ...this.note('device-form', known ? 'Saved' : 'Device added'),
    }));
  }

  removeDevice(id: string): Promise<boolean> {
    return this.run('device-form', () => this.plugin.removeDevice({ id }), (data) => ({ data, ...this.note('device-form', 'Device removed') }));
  }

  async setSwitch(id: string, on: boolean): Promise<void> {
    const scope: Scope = `switch:${id}`;
    await this.run(scope, () => this.plugin.setSwitch({ id, on }), ({ ok }) => this.note(scope, ok ? `Switched ${on ? 'on' : 'off'}` : 'The device did not confirm'));
  }

  /** Runs a plugin call: marks the scope busy, then stores the result or the error. Resolves whether it worked. */
  private async run<T>(scope: Scope, call: () => Promise<T>, apply: (result: T) => Partial<BridgesStoreState>): Promise<boolean> {
    const { [scope]: _error, ...errors } = this.state.errors;
    const { [scope]: _note, ...notes } = this.state.notes;
    this.update({ pending: this.count(scope, 1), errors, notes });
    try {
      this.update(apply(await call()));
      return true;
    } catch (error) {
      this.update({ errors: { ...this.state.errors, [scope]: message(error) } });
      return false;
    } finally {
      this.update({ pending: this.count(scope, -1) });
    }
  }

  private count(scope: Scope, delta: number): BridgesStoreState['pending'] {
    return { ...this.state.pending, [scope]: (this.state.pending[scope] ?? 0) + delta };
  }

  private note(scope: Scope, text: string): Pick<BridgesStoreState, 'notes'> {
    return { notes: { ...this.state.notes, [scope]: text } };
  }

  private update(changes: Partial<BridgesStoreState>): void {
    this.state = { ...this.state, ...changes };
    this.subscribers.forEach((subscriber) => subscriber());
  }
}

export function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
