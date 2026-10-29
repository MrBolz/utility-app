import { h } from '../../core/dom';
import type { UtilityModule } from '../../core/types';
import { classify, displayName, KINDS, model, portLabel, serviceLabels } from './classify';
import { APK_URL, isScannerAvailable, NetworkScanner, type NetworkInfo, type ScannedDevice, type WifiInfo } from './plugin';
import { NetworkStore, type NetworkState } from './store';

let store: NetworkStore | undefined;

export const network: UtilityModule = {
  id: 'network',
  title: 'Network',
  icon: '🛜',
  mount(container) {
    if (!isScannerAvailable()) {
      container.append(h('div', { class: 'card-grid' }, appRequiredCard()));
      return;
    }
    const firstVisit = !store;
    store ??= new NetworkStore(NetworkScanner);
    const view = new NetworkView(container, store);
    void store.refreshInfo();
    if (firstVisit) void store.scan();
    return () => view.destroy();
  },
};

function appRequiredCard(): HTMLElement {
  return h(
    'section',
    { class: 'card' },
    cardHeader('📲', 'Android app needed'),
    h(
      'div',
      { class: 'card-body' },
      h('p', { class: 'card-message' }, 'Browsers cannot look into your local network. The Android version of this app can list the devices on your Wi-Fi and show details about the connection.'),
      h('p', null, h('a', { class: 'button', href: APK_URL }, 'Download the Android app')),
      h('p', { class: 'card-message' }, 'Open the downloaded file and allow installing from your browser when Android asks. Updates install the same way.'),
    ),
  );
}

class NetworkView {
  private readonly grid = h('div', { class: 'card-grid network' });
  /** Device rows the user expanded; kept across re-renders. */
  private readonly expanded = new Set<string>();
  private frame?: number;
  private readonly unsubscribe: () => void;

  constructor(container: HTMLElement, private readonly store: NetworkStore) {
    container.append(this.grid);
    this.unsubscribe = store.subscribe(() => (this.frame ??= requestAnimationFrame(() => this.render())));
    this.render();
  }

  destroy(): void {
    this.unsubscribe();
    if (this.frame !== undefined) cancelAnimationFrame(this.frame);
  }

  private render(): void {
    this.frame = undefined;
    const { state } = this.store;
    const nearby = state.info?.wifi?.nearby;
    this.grid.replaceChildren(this.devicesCard(state), this.connectionCard(state));
    if (nearby && nearby.length > 0) this.grid.append(this.nearbyCard(state.info!.wifi!));
  }

  private connectionCard({ info, infoError }: NetworkState): HTMLElement {
    if (!info) {
      return h('section', { class: 'card' }, cardHeader('🌐', 'Connection'), h('div', { class: 'card-body' }, h('p', { class: 'card-message' }, infoError ?? 'Loading…')));
    }
    const wifi = info.wifi;
    const ssid = wifi?.ssid ?? (info.locationPermission ? 'Hidden (turn on location)' : undefined);
    const rows: [string, string | Node | undefined][] = [
      ['Type', transportLabel(info)],
      wifi ? ['Wi-Fi', ssid ?? h('button', { type: 'button', class: 'link-button', onClick: () => void this.store.requestLocation() }, 'Show name (needs location)')] : ['Wi-Fi', undefined],
      ['Signal', wifi?.rssi !== undefined ? `${wifi.signalPercent ?? '?'} % (${wifi.rssi} dBm)` : undefined],
      ['Band', wifi?.frequencyMhz ? `${band(wifi.frequencyMhz)} · channel ${wifi.channel}` : undefined],
      ['Standard', wifi?.standard],
      ['Link speed', wifi?.linkSpeedMbps ? linkSpeed(wifi) : undefined],
      ['IP address', info.ipAddress ? `${info.ipAddress}/${info.prefixLength}` : undefined],
      ['Router', info.gateway],
      ['DNS', info.dnsServers?.length ? info.dnsServers.join(', ') : undefined],
      ['Private DNS', info.privateDns ? (info.privateDnsServer ?? 'Automatic') : undefined],
      ['Domain', info.domain],
      ['Internet latency', info.internetLatencyMs !== undefined ? `${info.internetLatencyMs} ms` : undefined],
      ['VPN', info.vpn ? 'Active' : undefined],
      ['Metered', info.metered ? 'Yes' : undefined],
    ];
    return h(
      'section',
      { class: 'card', 'data-state': info.internet ? 'ok' : 'error' },
      cardHeader('🌐', 'Connection', connectionStatus(info)),
      h('div', { class: 'card-body' }, readings(rows)),
    );
  }

  private nearbyCard(wifi: WifiInfo): HTMLElement {
    const networks = wifi.nearby!;
    const sameChannel = networks.filter((n) => n.channel === wifi.channel && n.bssid !== wifi.bssid).length;
    return h(
      'section',
      { class: 'card' },
      cardHeader('📶', 'Nearby Wi-Fi', `${networks.length}`),
      h(
        'div',
        { class: 'card-body' },
        h('p', { class: 'card-message' }, `${sameChannel} other network${sameChannel === 1 ? '' : 's'} on your channel ${wifi.channel}${sameChannel > 3 ? ' — a crowded channel can slow Wi-Fi down.' : '.'}`),
        h(
          'ul',
          { class: 'plain-list' },
          ...networks.slice(0, 12).map((n) =>
            h(
              'li',
              { class: 'reading' },
              h('span', null, n.ssid ?? 'Hidden network', n.bssid === wifi.bssid ? h('span', { class: 'badge' }, 'Connected') : null),
              h('span', { class: 'reading-value' }, `${n.rssi} dBm`),
              h('span', { class: 'reading-sub' }, `${band(n.frequencyMhz)} · channel ${n.channel}`),
              bar(n.rssi, -95, -30),
            ),
          ),
        ),
      ),
    );
  }

  private devicesCard(state: NetworkState): HTMLElement {
    const devices = [...state.devices.values()].sort((a, b) => ipKey(a.ip) - ipKey(b.ip));
    const status = state.scanning ? 'Scanning' : `${devices.length} found`;
    const progress = state.progress;
    return h(
      'section',
      { class: 'card devices-card', 'data-state': state.scanning ? 'starting' : state.scanError ? 'error' : 'ok' },
      cardHeader('🔎', 'Devices', status),
      h(
        'div',
        { class: 'card-body' },
        state.scanning
          ? h(
              'div',
              { class: 'scan-progress' },
              h('p', { class: 'card-message' }, progress ? `${progress.phase === 'sweep' ? 'Checking addresses' : 'Reading device details'} · ${progress.done}/${progress.total}` : 'Starting scan…'),
              progress ? bar(progress.done, 0, progress.total) : null,
            )
          : null,
        state.scanError ? h('p', { class: 'card-message' }, state.scanError) : null,
        devices.length > 0 ? h('ul', { class: 'device-list' }, ...devices.map((device) => this.deviceRow(device))) : null,
        !state.scanning
          ? h(
              'div',
              { class: 'card-actions' },
              h('button', { type: 'button', class: 'button', onClick: () => void this.store.scan() }, devices.length ? 'Scan again' : 'Scan'),
              state.lastScan ? h('span', { class: 'card-message' }, `${state.lastScan.subnet} · ${(state.lastScan.durationMs / 1000).toFixed(1)} s`) : null,
            )
          : null,
        h('p', { class: 'card-note' }, 'Devices that ignore pings and offer no services (often phones and laptops with a firewall) may not show up. Android does not let apps read MAC addresses.'),
      ),
    );
  }

  private deviceRow(device: ScannedDevice): HTMLElement {
    const kind = KINDS[classify(device)];
    const name = displayName(device);
    const details = h(
      'details',
      { class: 'device', open: this.expanded.has(device.ip), onToggle: (event) => this.onToggle(device.ip, event) },
      h(
        'summary',
        null,
        h('span', { class: 'card-icon', 'aria-hidden': 'true' }, kind.icon),
        h(
          'span',
          { class: 'device-text' },
          h('span', { class: 'device-name' }, name),
          h('span', { class: 'device-sub' }, [kind.label, name === device.ip ? null : device.ip].filter(Boolean).join(' · ')),
        ),
        device.latencyMs !== undefined ? h('span', { class: 'device-latency' }, `${device.latencyMs} ms`) : null,
      ),
      readings([
        ['IP address', device.ip],
        ['Model', model(device)],
        ['Hostname', device.hostname],
        ['mDNS name', device.mdnsName],
        ['Web page', device.httpTitle],
        ['Web server', device.httpServer ?? device.upnp?.server],
        ['UPnP type', device.upnp?.deviceType?.replace(/^urn:[^:]+:device:/, '')],
        ['Services', chips(serviceLabels(device))],
        ['Open ports', chips(device.openPorts.map(portLabel))],
        ['Found via', device.sources.join(', ')],
      ]),
    );
    return h('li', null, details);
  }

  private onToggle(ip: string, event: Event): void {
    if ((event.target as HTMLDetailsElement).open) this.expanded.add(ip);
    else this.expanded.delete(ip);
  }
}

function cardHeader(icon: string, title: string, status?: string): HTMLElement {
  return h(
    'header',
    { class: 'card-header' },
    h('span', { class: 'card-icon', 'aria-hidden': 'true' }, icon),
    h('h2', { class: 'card-title' }, title),
    status ? h('span', { class: 'status' }, status) : null,
  );
}

function readings(rows: [string, string | Node | undefined][]): HTMLElement {
  return h(
    'dl',
    { class: 'readings' },
    ...rows
      .filter(([, value]) => value !== undefined && value !== '')
      .map(([label, value]) => h('div', { class: 'reading' }, h('dt', null, label), h('dd', { class: 'reading-value' }, value!))),
  );
}

function chips(labels: string[]): HTMLElement | undefined {
  return labels.length ? h('span', { class: 'chips' }, ...labels.map((label) => h('span', { class: 'chip' }, label))) : undefined;
}

function bar(value: number, min: number, max: number): HTMLElement {
  const fraction = Math.min(1, Math.max(0, (value - min) / (max - min)));
  const fill = h('div', { class: 'bar-fill' });
  fill.style.left = '0';
  fill.style.width = `${fraction * 100}%`;
  return h('div', { class: 'bar' }, fill);
}

function connectionStatus(info: NetworkInfo): string {
  if (!info.connected) return 'Offline';
  if (info.captivePortal) return 'Sign-in needed';
  return info.internet ? 'Online' : 'No internet';
}

function transportLabel(info: NetworkInfo): string | undefined {
  const labels = { wifi: 'Wi-Fi', ethernet: 'Ethernet', cellular: 'Mobile data', vpn: 'VPN', other: 'Other' };
  return info.transport ? labels[info.transport] : undefined;
}

function band(frequencyMhz: number): string {
  if (frequencyMhz >= 5925) return '6 GHz';
  if (frequencyMhz >= 4900) return '5 GHz';
  return '2.4 GHz';
}

function linkSpeed(wifi: WifiInfo): string {
  if (wifi.txLinkSpeedMbps && wifi.rxLinkSpeedMbps && wifi.txLinkSpeedMbps > 0 && wifi.rxLinkSpeedMbps > 0) {
    return `↑ ${wifi.txLinkSpeedMbps} / ↓ ${wifi.rxLinkSpeedMbps} Mbps`;
  }
  return `${wifi.linkSpeedMbps} Mbps`;
}

function ipKey(ip: string): number {
  return ip.split('.').reduce((key, part) => key * 256 + Number(part), 0);
}
