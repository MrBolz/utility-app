import { vi } from 'vitest';
import type { BridgeRun, BridgesPlugin, BridgesState, TuyaDeviceInfo } from './plugin';

/** A phone with two plugs (one ticked), a lamp and an alarm tomorrow morning. */
export function sampleState(overrides: Partial<BridgesState> = {}): BridgesState {
  return {
    devices: [
      { id: 'bf01', name: 'Desk lamp', ip: '192.168.1.31', version: '3.3', dpId: '20', category: 'dj', hasKey: true },
      { id: 'bf02', name: 'Coffee machine', ip: '192.168.1.20', version: '3.4', dpId: '1', category: 'cz', productName: 'Smart Plug', hasKey: true },
      { id: 'bf03', name: 'Bedroom heater', version: '3.5', dpId: '1', category: 'cz', hasKey: false },
    ],
    bridge: { enabled: true, deviceIds: ['bf02'], clockPackages: [] },
    nextAlarm: { triggerAt: new Date(2026, 10, 3, 6, 30).getTime(), packageName: 'com.google.android.deskclock', appLabel: 'Clock', matches: true },
    scheduledAt: new Date(2026, 10, 3, 6, 30).getTime(),
    permissions: { exactAlarms: true, batteryUnrestricted: false, notifications: true },
    cloud: { region: 'central-europe', hasSecret: false },
    runs: [],
    ...overrides,
  };
}

/** A stand-in for the native plugin that keeps its state in memory. */
export function fakePlugin(initial: BridgesState = sampleState()) {
  let state = structuredClone(initial);
  const snapshot = () => structuredClone(state);
  const plugin = {
    getState: vi.fn<BridgesPlugin['getState']>(async () => snapshot()),
    saveBridge: vi.fn<BridgesPlugin['saveBridge']>(async (bridge) => {
      state.bridge = structuredClone(bridge);
      return snapshot();
    }),
    saveCloud: vi.fn<BridgesPlugin['saveCloud']>(async ({ accessId, accessSecret, region }) => {
      state.cloud = { accessId, region, hasSecret: state.cloud.hasSecret || !!accessSecret };
      return snapshot();
    }),
    importFromCloud: vi.fn<BridgesPlugin['importFromCloud']>(async () => {
      state.devices = state.devices.map((device) => ({ ...device, hasKey: true }));
      return { state: snapshot(), imported: state.devices.length };
    }),
    discover: vi.fn<BridgesPlugin['discover']>(async () => ({ state: snapshot(), found: [] })),
    saveDevice: vi.fn<BridgesPlugin['saveDevice']>(async ({ localKey, ...changes }) => {
      const existing = state.devices.find((device) => device.id === changes.id);
      const device: TuyaDeviceInfo = { name: changes.id, dpId: '1', hasKey: false, ...existing, ...changes };
      if (localKey) device.hasKey = true;
      state.devices = existing ? state.devices.map((d) => (d.id === device.id ? device : d)) : [...state.devices, device];
      return snapshot();
    }),
    removeDevice: vi.fn<BridgesPlugin['removeDevice']>(async ({ id }) => {
      state.devices = state.devices.filter((device) => device.id !== id);
      state.bridge.deviceIds = state.bridge.deviceIds.filter((deviceId) => deviceId !== id);
      return snapshot();
    }),
    setSwitch: vi.fn<BridgesPlugin['setSwitch']>(async () => ({ ok: true })),
    runNow: vi.fn<BridgesPlugin['runNow']>(async () => {
      const run: BridgeRun = {
        at: Date.now(),
        trigger: 'test',
        results: state.devices.filter((device) => state.bridge.deviceIds.includes(device.id)).map(({ id, name }) => ({ id, name, ok: true })),
      };
      state.runs = [run, ...state.runs];
      return { state: snapshot(), run: structuredClone(run) };
    }),
    openSettings: vi.fn<BridgesPlugin['openSettings']>(async () => {}),
  } satisfies BridgesPlugin;
  return {
    plugin,
    get state() {
      return state;
    },
    /** Replaces the native state, e.g. to simulate changes made while the app was in the background. */
    setState(next: BridgesState) {
      state = structuredClone(next);
    },
  };
}
