import { vi } from 'vitest';
import type { NetworkInfo, NetworkScannerPlugin, ScannedDevice, ScanProgress, ScanResult } from './plugin';

/** A controllable stand-in for the native plugin. */
export function fakePlugin(info: NetworkInfo) {
  const listeners: { device: ((d: ScannedDevice) => void)[]; scanProgress: ((p: ScanProgress) => void)[] } = { device: [], scanProgress: [] };
  let finish: (result: ScanResult) => void = () => {};
  let fail: (error: Error) => void = () => {};
  const plugin = {
    getInfo: vi.fn(async () => info),
    scan: vi.fn(
      () =>
        new Promise<ScanResult>((resolve, reject) => {
          finish = resolve;
          fail = reject;
        }),
    ),
    checkPermissions: vi.fn(async () => ({ location: 'prompt' as const })),
    requestPermissions: vi.fn(async () => ({ location: 'granted' as const })),
    addListener: vi.fn(async (event: 'device' | 'scanProgress', listener: (value: never) => void) => {
      listeners[event].push(listener as never);
      return { remove: async () => {} };
    }),
  } as unknown as NetworkScannerPlugin & { scan: ReturnType<typeof vi.fn>; requestPermissions: ReturnType<typeof vi.fn> };
  return {
    plugin,
    emitDevice: (device: ScannedDevice) => listeners.device.forEach((l) => l(device)),
    emitProgress: (progress: ScanProgress) => listeners.scanProgress.forEach((l) => l(progress)),
    finish: (result: ScanResult) => finish(result),
    fail: (error: Error) => fail(error),
  };
}
