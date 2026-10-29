import type { NetworkInfo, NetworkScannerPlugin, ScannedDevice, ScanProgress, ScanResult } from './plugin';

export interface NetworkState {
  info?: NetworkInfo;
  infoError?: string;
  devices: Map<string, ScannedDevice>;
  scanning: boolean;
  progress?: ScanProgress;
  scanError?: string;
  lastScan?: Omit<ScanResult, 'devices'> & { finishedAt: number };
}

/**
 * Network data that outlives the view: a scan keeps running natively while another tab is open,
 * and its results are still there when the user comes back.
 */
export class NetworkStore {
  state: NetworkState = { devices: new Map(), scanning: false };
  private readonly subscribers = new Set<() => void>();
  private listening = false;

  constructor(private readonly plugin: NetworkScannerPlugin) {}

  subscribe(subscriber: () => void): () => void {
    this.subscribers.add(subscriber);
    return () => this.subscribers.delete(subscriber);
  }

  async refreshInfo(): Promise<void> {
    try {
      this.update({ info: await this.plugin.getInfo(), infoError: undefined });
    } catch (error) {
      this.update({ infoError: message(error) });
    }
  }

  async requestLocation(): Promise<void> {
    await this.plugin.requestPermissions({ permissions: ['location'] });
    await this.refreshInfo();
  }

  async scan(): Promise<void> {
    if (this.state.scanning) return;
    await this.listen();
    this.update({ scanning: true, scanError: undefined, progress: undefined, devices: new Map() });
    try {
      const { devices, ...summary } = await this.plugin.scan();
      const merged = new Map(devices.map((device) => [device.ip, device]));
      this.update({ devices: merged, lastScan: { ...summary, finishedAt: Date.now() } });
    } catch (error) {
      this.update({ scanError: message(error) });
    } finally {
      this.update({ scanning: false, progress: undefined });
    }
  }

  private async listen(): Promise<void> {
    if (this.listening) return;
    this.listening = true;
    await this.plugin.addListener('device', (device) => {
      if (!this.state.scanning) return;
      const devices = new Map(this.state.devices);
      devices.set(device.ip, device);
      this.update({ devices });
    });
    await this.plugin.addListener('scanProgress', (progress) => {
      if (this.state.scanning) this.update({ progress });
    });
  }

  private update(changes: Partial<NetworkState>): void {
    this.state = { ...this.state, ...changes };
    this.subscribers.forEach((subscriber) => subscriber());
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
