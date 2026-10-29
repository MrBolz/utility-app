import { Capacitor, registerPlugin } from '@capacitor/core';

/** Shapes exchanged with the native Bridges plugin (android/…/bridge/). Keys and secrets never come back from it. */
export type TuyaRegion = 'central-europe' | 'western-europe' | 'western-america' | 'eastern-america' | 'china' | 'india' | 'singapore';

export interface TuyaDeviceInfo {
  id: string;
  name: string;
  ip?: string;
  version?: string;
  /** Data point that switches the device; "1" for most plugs. */
  dpId: string;
  /** Tuya category, e.g. "cz" (socket) or "pc" (power strip). */
  category?: string;
  productName?: string;
  hasKey: boolean;
  /** ms epoch of the last discovery that saw the device. */
  lastSeen?: number;
}

export interface AlarmBridge {
  enabled: boolean;
  deviceIds: string[];
  /** Clock apps whose alarms trigger the bridge; [] = any clock app. */
  clockPackages: string[];
}

export interface NextAlarm {
  triggerAt: number;
  packageName?: string;
  appLabel?: string;
  /** Whether the alarm comes from an allowed clock app. */
  matches: boolean;
}

export interface BridgeRunResult {
  id: string;
  name: string;
  ok: boolean;
  message?: string;
}

export interface BridgeRun {
  at: number;
  trigger: 'alarm' | 'test';
  results: BridgeRunResult[];
}

export interface BridgePermissions {
  exactAlarms: boolean;
  batteryUnrestricted: boolean;
  notifications: boolean;
}

export interface CloudSettings {
  accessId?: string;
  region: TuyaRegion;
  hasSecret: boolean;
}

export interface BridgesState {
  devices: TuyaDeviceInfo[];
  bridge: AlarmBridge;
  nextAlarm: NextAlarm | null;
  /** When Brutus's own alarm for the bridge is due, if one is scheduled. */
  scheduledAt?: number;
  permissions: BridgePermissions;
  cloud: CloudSettings;
  /** Newest first. */
  runs: BridgeRun[];
}

export interface DiscoveredDevice {
  id: string;
  ip: string;
  version?: string;
  productKey?: string;
  /** Whether the device is already in the device list (its IP and version were updated). */
  known: boolean;
}

export type SettingsTarget = 'exactAlarms' | 'battery' | 'notifications' | 'appInfo';

export interface CloudCredentials {
  accessId: string;
  /** Omit to keep the saved secret. */
  accessSecret?: string;
  region: TuyaRegion;
}

export interface DeviceChanges {
  id: string;
  name?: string;
  ip?: string;
  version?: string;
  dpId?: string;
  localKey?: string;
}

/** Every method rejects with a readable message, e.g. "The plug is busy (another app is connected to it)". */
export interface BridgesPlugin {
  getState(): Promise<BridgesState>;
  saveBridge(bridge: AlarmBridge): Promise<BridgesState>;
  saveCloud(options: CloudCredentials): Promise<BridgesState>;
  /** Merges the cloud's devices into the list; local IP and DP id edits are kept. */
  importFromCloud(): Promise<{ state: BridgesState; imported: number }>;
  /** Listens for device announcements on the Wi-Fi; durationMs defaults to 6000. */
  discover(options?: { durationMs?: number }): Promise<{ state: BridgesState; found: DiscoveredDevice[] }>;
  saveDevice(options: DeviceChanges): Promise<BridgesState>;
  removeDevice(options: { id: string }): Promise<BridgesState>;
  setSwitch(options: { id: string; on: boolean }): Promise<{ ok: boolean }>;
  /** Does what the alarm does, right now (trigger "test"). */
  runNow(): Promise<{ state: BridgesState; run: BridgeRun }>;
  openSettings(options: { target: SettingsTarget }): Promise<void>;
}

export const Bridges = registerPlugin<BridgesPlugin>('Bridges');

/** Bridges need the Android app: browsers cannot run at alarm time or talk to plugs on the LAN. */
export function isBridgesAvailable(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.isPluginAvailable('Bridges');
}

/** Tuya data centers; a cloud project only answers on the one it was created in. */
export const REGIONS: { id: TuyaRegion; label: string }[] = [
  { id: 'central-europe', label: 'Central Europe' },
  { id: 'western-europe', label: 'Western Europe' },
  { id: 'western-america', label: 'Western America' },
  { id: 'eastern-america', label: 'Eastern America' },
  { id: 'china', label: 'China' },
  { id: 'india', label: 'India' },
  { id: 'singapore', label: 'Singapore' },
];

/** Tuya categories of plugs and power strips; these come first in the bridge's checklist. */
export function isSocket(device: TuyaDeviceInfo): boolean {
  return device.category === 'cz' || device.category === 'pc';
}
