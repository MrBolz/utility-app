import { describe, expect, it, vi } from 'vitest';
import { fakePlugin } from './fake-plugin';
import type { ScannedDevice } from './plugin';
import { NetworkStore } from './store';

const device = (ip: string): ScannedDevice => ({ ip, self: false, gateway: false, sources: ['ping'], openPorts: [], services: [] });
const flush = () => new Promise((resolve) => setTimeout(resolve));

describe('NetworkStore', () => {
  it('loads connection info', async () => {
    const { plugin } = fakePlugin({ connected: true, locationPermission: false, ipAddress: '192.168.1.42' });
    const store = new NetworkStore(plugin);
    await store.refreshInfo();
    expect(store.state.info?.ipAddress).toBe('192.168.1.42');
  });

  it('collects devices while scanning and keeps the final list', async () => {
    const fake = fakePlugin({ connected: true, locationPermission: false });
    const store = new NetworkStore(fake.plugin);
    const changed = vi.fn();
    store.subscribe(changed);

    const scan = store.scan();
    await flush();
    expect(store.state.scanning).toBe(true);
    fake.emitProgress({ phase: 'sweep', done: 10, total: 254 });
    fake.emitDevice(device('192.168.1.1'));
    fake.emitDevice(device('192.168.1.1'));
    expect(store.state.devices.size).toBe(1);
    expect(store.state.progress?.done).toBe(10);

    fake.finish({ devices: [device('192.168.1.1'), device('192.168.1.7')], scannedHosts: 254, subnet: 'a – b', durationMs: 9000 });
    await scan;
    expect(store.state.scanning).toBe(false);
    expect([...store.state.devices.keys()]).toEqual(['192.168.1.1', '192.168.1.7']);
    expect(store.state.lastScan?.scannedHosts).toBe(254);
    expect(changed).toHaveBeenCalled();
  });

  it('reports scan errors and ignores a second scan while one runs', async () => {
    const fake = fakePlugin({ connected: false, locationPermission: false });
    const store = new NetworkStore(fake.plugin);
    const scan = store.scan();
    await flush();
    void store.scan();
    expect(fake.plugin.scan).toHaveBeenCalledOnce();
    fake.fail(new Error('Not connected to a Wi-Fi or Ethernet network'));
    await scan;
    expect(store.state.scanError).toBe('Not connected to a Wi-Fi or Ethernet network');
    expect(store.state.scanning).toBe(false);
  });
});
