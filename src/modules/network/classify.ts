import type { ScannedDevice } from './plugin';

export type DeviceKind =
  | 'this-phone'
  | 'router'
  | 'phone'
  | 'computer'
  | 'printer'
  | 'tv'
  | 'speaker'
  | 'nas'
  | 'camera'
  | 'smart-home'
  | 'unknown';

export const KINDS: Record<DeviceKind, { label: string; icon: string }> = {
  'this-phone': { label: 'This phone', icon: '📱' },
  router: { label: 'Router', icon: '🛜' },
  phone: { label: 'Phone or tablet', icon: '📱' },
  computer: { label: 'Computer', icon: '💻' },
  printer: { label: 'Printer', icon: '🖨️' },
  tv: { label: 'TV or streaming device', icon: '📺' },
  speaker: { label: 'Speaker', icon: '🔊' },
  nas: { label: 'Storage / server', icon: '🗄️' },
  camera: { label: 'Camera', icon: '📷' },
  'smart-home': { label: 'Smart home', icon: '💡' },
  unknown: { label: 'Device', icon: '❔' },
};

export const PORT_NAMES: Record<number, string> = {
  21: 'FTP',
  22: 'SSH',
  23: 'Telnet',
  53: 'DNS',
  80: 'Web (HTTP)',
  139: 'NetBIOS',
  443: 'Web (HTTPS)',
  445: 'File sharing (SMB)',
  515: 'Printer (LPD)',
  548: 'Apple file sharing',
  554: 'Video stream (RTSP)',
  631: 'Printer (IPP)',
  1883: 'MQTT',
  3389: 'Remote desktop',
  5000: 'Web / UPnP (5000)',
  5001: 'Web (5001)',
  5900: 'Screen sharing (VNC)',
  7000: 'AirPlay',
  8008: 'Google Cast',
  8009: 'Google Cast',
  8080: 'Web (8080)',
  8123: 'Home Assistant',
  8443: 'Web (8443)',
  9100: 'Printer (raw)',
  32400: 'Plex',
  49152: 'UPnP',
  62078: 'Apple device sync',
};

export const SERVICE_NAMES: Record<string, string> = {
  '_http._tcp': 'Web page',
  '_https._tcp': 'Web page',
  '_googlecast._tcp': 'Google Cast',
  '_airplay._tcp': 'AirPlay',
  '_raop._tcp': 'AirPlay audio',
  '_companion-link._tcp': 'Apple device',
  '_hap._tcp': 'HomeKit',
  '_homekit._tcp': 'HomeKit',
  '_ipp._tcp': 'Printer',
  '_ipps._tcp': 'Printer',
  '_printer._tcp': 'Printer',
  '_pdl-datastream._tcp': 'Printer',
  '_uscan._tcp': 'Scanner',
  '_scanner._tcp': 'Scanner',
  '_smb._tcp': 'File sharing',
  '_afpovertcp._tcp': 'Apple file sharing',
  '_adisk._tcp': 'Time Machine',
  '_ssh._tcp': 'SSH',
  '_sftp-ssh._tcp': 'SFTP',
  '_workstation._tcp': 'Workstation',
  '_device-info._tcp': 'Device info',
  '_spotify-connect._tcp': 'Spotify Connect',
  '_sonos._tcp': 'Sonos',
  '_amzn-wplay._tcp': 'Amazon Fire TV',
  '_androidtvremote2._tcp': 'Android TV',
  '_matter._tcp': 'Matter',
  '_matterc._udp': 'Matter (pairing)',
  '_meshcop._udp': 'Thread border router',
  '_hue._tcp': 'Philips Hue',
  '_home-assistant._tcp': 'Home Assistant',
  '_esphomelib._tcp': 'ESPHome',
  '_mqtt._tcp': 'MQTT',
  '_rfb._tcp': 'Screen sharing',
  '_daap._tcp': 'iTunes sharing',
  '_sleep-proxy._udp': 'Sleep proxy',
  '_apple-mobdev2._tcp': 'Apple device',
  '_nfs._tcp': 'NFS',
  '_ftp._tcp': 'FTP',
};

const has = (device: ScannedDevice, ...types: string[]) => device.services.some((s) => types.includes(s.type));
const port = (device: ScannedDevice, ...ports: number[]) => ports.some((p) => device.openPorts.includes(p));

function names(device: ScannedDevice): string {
  return [device.hostname, device.mdnsName, device.upnp?.friendlyName, device.upnp?.modelName, ...device.services.map((s) => s.name)]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

export function classify(device: ScannedDevice): DeviceKind {
  if (device.self) return 'this-phone';
  if (device.gateway) return 'router';
  const name = names(device);
  const upnpType = device.upnp?.deviceType?.toLowerCase() ?? '';

  if (/iphone|ipad|android|galaxy|pixel|oneplus|xiaomi|redmi|huawei/.test(name)) return 'phone';
  if (/macbook|imac|mac-?mini|mac-?pro|desktop|laptop|-pc\b|thinkpad/.test(name)) return 'computer';
  if (has(device, '_googlecast._tcp', '_amzn-wplay._tcp', '_androidtvremote2._tcp') || /appletv|apple-tv|\btv\b|bravia|roku/.test(name)) return 'tv';
  if (upnpType.includes('mediarenderer') && /tv/.test(name)) return 'tv';
  if (has(device, '_ipp._tcp', '_ipps._tcp', '_printer._tcp', '_pdl-datastream._tcp') || port(device, 9100, 515)) return 'printer';
  if (has(device, '_sonos._tcp') || (has(device, '_raop._tcp', '_spotify-connect._tcp') && !has(device, '_airplay._tcp'))) return 'speaker';
  if (has(device, '_hap._tcp', '_homekit._tcp', '_matter._tcp', '_hue._tcp', '_esphomelib._tcp', '_home-assistant._tcp', '_meshcop._udp') || port(device, 1883, 8123)) {
    return 'smart-home';
  }
  if (has(device, '_adisk._tcp', '_nfs._tcp') || /synology|diskstation|qnap|nas\b/.test(name) || upnpType.includes('mediaserver')) return 'nas';
  if (port(device, 554) || /camera|cam\b/.test(name)) return 'camera';
  if (upnpType.includes('internetgatewaydevice')) return 'router';
  if (port(device, 62078) || has(device, '_companion-link._tcp', '_apple-mobdev2._tcp')) return 'phone';
  if (has(device, '_workstation._tcp', '_smb._tcp', '_afpovertcp._tcp', '_rfb._tcp') || port(device, 3389, 445, 139, 5900)) return 'computer';
  return 'unknown';
}

const stripLocal = (name: string) => name.replace(/\.local\.?$/i, '');

/** The most human-friendly name known for a device. */
export function displayName(device: ScannedDevice): string {
  if (device.self && device.selfName) return device.selfName;
  const service = device.services.find((s) => s.type !== '_device-info._tcp' && s.type !== '_sleep-proxy._udp' && !/^[0-9a-f-]{16,}$/i.test(s.name));
  return (
    device.upnp?.friendlyName ??
    (service ? service.name.replace(/^[0-9A-F]{12}@/, '') : undefined) ??
    (device.mdnsName ? stripLocal(device.mdnsName) : undefined) ??
    device.hostname ??
    device.httpTitle ??
    device.ip
  );
}

/** Manufacturer and model, from UPnP or from mDNS TXT records. */
export function model(device: ScannedDevice): string | undefined {
  const upnp = [device.upnp?.manufacturer, device.upnp?.modelName].filter(Boolean).join(' ');
  if (upnp) return upnp;
  for (const service of device.services) {
    const value = service.txt['md'] ?? service.txt['ty'] ?? service.txt['model'] ?? service.txt['usb_MDL'];
    if (value) return value;
  }
  return undefined;
}

export function serviceLabels(device: ScannedDevice): string[] {
  return [...new Set(device.services.map((s) => SERVICE_NAMES[s.type] ?? s.type))];
}

export function portLabel(port: number): string {
  return PORT_NAMES[port] ? `${port} · ${PORT_NAMES[port]}` : String(port);
}
