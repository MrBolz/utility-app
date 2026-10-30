import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';

/** Shapes returned by the native NetworkScanner plugin (android/…/network/NetworkScannerPlugin.java). */
export interface NearbyNetwork {
  ssid?: string;
  bssid?: string;
  rssi: number;
  frequencyMhz: number;
  channel: number;
}

export interface WifiInfo {
  ssid?: string;
  bssid?: string;
  rssi?: number;
  signalPercent?: number;
  linkSpeedMbps?: number;
  txLinkSpeedMbps?: number;
  rxLinkSpeedMbps?: number;
  frequencyMhz?: number;
  channel?: number;
  standard?: string;
  /** Only present with location permission. */
  nearby?: NearbyNetwork[];
}

export interface NetworkInfo {
  connected: boolean;
  locationPermission: boolean;
  transport?: 'wifi' | 'ethernet' | 'cellular' | 'vpn' | 'other';
  internet?: boolean;
  captivePortal?: boolean;
  metered?: boolean;
  vpn?: boolean;
  downstreamKbps?: number;
  upstreamKbps?: number;
  interfaceName?: string;
  ipAddress?: string;
  prefixLength?: number;
  gateway?: string;
  dnsServers?: string[];
  domain?: string;
  privateDns?: boolean;
  privateDnsServer?: string;
  mtu?: number;
  internetLatencyMs?: number;
  wifi?: WifiInfo;
}

export interface MdnsService {
  name: string;
  type: string;
  port: number;
  txt: Record<string, string>;
}

export interface UpnpInfo {
  friendlyName?: string;
  manufacturer?: string;
  modelName?: string;
  modelNumber?: string;
  deviceType?: string;
  server?: string;
}

export interface ScannedDevice {
  ip: string;
  self: boolean;
  gateway: boolean;
  selfName?: string;
  /** How the device was found: ping, tcp, mdns, ssdp, self, gateway. */
  sources: string[];
  latencyMs?: number;
  hostname?: string;
  mdnsName?: string;
  openPorts: number[];
  services: MdnsService[];
  upnp?: UpnpInfo;
  httpTitle?: string;
  httpServer?: string;
}

export interface ScanResult {
  devices: ScannedDevice[];
  scannedHosts: number;
  subnet: string;
  durationMs: number;
}

export interface ScanProgress {
  phase: 'sweep' | 'details';
  done: number;
  total: number;
}

type PermissionStatus = { location: 'granted' | 'denied' | 'prompt' | 'prompt-with-rationale' };

export interface NetworkScannerPlugin {
  getInfo(): Promise<NetworkInfo>;
  scan(): Promise<ScanResult>;
  checkPermissions(): Promise<PermissionStatus>;
  requestPermissions(options: { permissions: ['location'] }): Promise<PermissionStatus>;
  addListener(event: 'device', listener: (device: ScannedDevice) => void): Promise<PluginListenerHandle>;
  addListener(event: 'scanProgress', listener: (progress: ScanProgress) => void): Promise<PluginListenerHandle>;
}

export const NetworkScanner = registerPlugin<NetworkScannerPlugin>('NetworkScanner');

/** Device discovery needs the Android app; the web version cannot reach the local network. */
export function isScannerAvailable(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.isPluginAvailable('NetworkScanner');
}
