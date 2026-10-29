import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakePlugin } from './fake-plugin';

const fake = fakePlugin({
  connected: true,
  locationPermission: false,
  transport: 'wifi',
  internet: true,
  ipAddress: '192.168.1.42',
  prefixLength: 24,
  gateway: '192.168.1.1',
  dnsServers: ['192.168.1.1'],
  internetLatencyMs: 14,
  wifi: { rssi: -55, signalPercent: 75, frequencyMhz: 5180, channel: 36, linkSpeedMbps: 866 },
});
let native = false;

vi.mock('./plugin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./plugin')>()),
  isScannerAvailable: () => native,
  NetworkScanner: fake.plugin,
}));

const { network } = await import('.');

describe('network module', () => {
  let cleanup: (() => void) | void;
  afterEach(() => cleanup?.());

  it('offers the Android app in the browser', () => {
    native = false;
    const container = document.createElement('div');
    cleanup = network.mount(container);
    expect(container.textContent).toContain('Android app needed');
    expect(container.querySelector('a.button')?.getAttribute('href')).toMatch(/brutus\.apk$/);
  });

  it('shows the connection and the devices found by a scan', async () => {
    native = true;
    const container = document.createElement('div');
    cleanup = network.mount(container);
    await vi.waitFor(() => expect(container.textContent).toContain('192.168.1.42/24'));
    expect(fake.plugin.scan).toHaveBeenCalledOnce();
    expect(container.textContent).toContain('5 GHz · channel 36');
    expect(container.textContent).toContain('Show name (needs location)');

    fake.emitProgress({ phase: 'sweep', done: 100, total: 254 });
    fake.emitDevice({ ip: '192.168.1.1', self: false, gateway: true, sources: ['ping'], openPorts: [80], services: [], latencyMs: 3, upnp: { friendlyName: 'FRITZ!Box 7590' } });
    await vi.waitFor(() => expect(container.textContent).toContain('Checking addresses · 100/254'));
    expect(container.querySelector('.device-name')?.textContent).toBe('FRITZ!Box 7590');
    expect(container.querySelector('.device-sub')?.textContent).toBe('Router · 192.168.1.1');

    // Switching tabs and back keeps the results and does not start another scan.
    cleanup?.();
    const again = document.createElement('div');
    cleanup = network.mount(again);
    await vi.waitFor(() => expect(again.querySelectorAll('.device')).toHaveLength(1));
    expect(fake.plugin.scan).toHaveBeenCalledOnce();
  });

  it('asks for location to show the Wi-Fi name', async () => {
    native = true;
    const container = document.createElement('div');
    cleanup = network.mount(container);
    await vi.waitFor(() => expect(container.textContent).toContain('needs location'));
    [...container.querySelectorAll('button')].find((b) => b.textContent?.includes('needs location'))!.click();
    expect(fake.plugin.requestPermissions).toHaveBeenCalledWith({ permissions: ['location'] });
  });
});
