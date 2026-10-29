import { describe, expect, it } from 'vitest';
import { classify, displayName, model, portLabel, serviceLabels } from './classify';
import type { ScannedDevice } from './plugin';

const device = (overrides: Partial<ScannedDevice> = {}): ScannedDevice => ({
  ip: '192.168.1.50',
  self: false,
  gateway: false,
  sources: ['ping'],
  openPorts: [],
  services: [],
  ...overrides,
});

const service = (type: string, name = 'Thing', txt: Record<string, string> = {}) => ({ name, type, port: 1, txt });

describe('classify', () => {
  it.each([
    [device({ self: true }), 'this-phone'],
    [device({ gateway: true, openPorts: [80, 443] }), 'router'],
    [device({ services: [service('_googlecast._tcp', 'Living Room TV')] }), 'tv'],
    [device({ services: [service('_ipp._tcp')] }), 'printer'],
    [device({ openPorts: [9100] }), 'printer'],
    [device({ services: [service('_sonos._tcp')] }), 'speaker'],
    [device({ services: [service('_hap._tcp')] }), 'smart-home'],
    [device({ openPorts: [62078] }), 'phone'],
    [device({ mdnsName: 'Annas-iPhone.local' }), 'phone'],
    [device({ hostname: 'macbook-pro.fritz.box' }), 'computer'],
    [device({ openPorts: [445, 139] }), 'computer'],
    [device({ services: [service('_adisk._tcp')] }), 'nas'],
    [device({ openPorts: [554] }), 'camera'],
    [device({ upnp: { deviceType: 'urn:schemas-upnp-org:device:InternetGatewayDevice:1' } }), 'router'],
    [device(), 'unknown'],
  ])('classifies %#', (input, kind) => {
    expect(classify(input)).toBe(kind);
  });
});

describe('displayName', () => {
  it('prefers the most descriptive name', () => {
    expect(displayName(device({ self: true, selfName: 'Pixel 9' }))).toBe('Pixel 9');
    expect(displayName(device({ upnp: { friendlyName: 'FRITZ!Box 7590' }, hostname: 'fritz.box' }))).toBe('FRITZ!Box 7590');
    expect(displayName(device({ services: [service('_device-info._tcp', 'x'), service('_airplay._tcp', 'Kitchen')] }))).toBe('Kitchen');
    expect(displayName(device({ services: [service('_raop._tcp', 'A1B2C3D4E5F6@Bedroom')] }))).toBe('Bedroom');
    expect(displayName(device({ mdnsName: 'raspberrypi.local' }))).toBe('raspberrypi');
    expect(displayName(device({ httpTitle: 'Printer admin' }))).toBe('Printer admin');
    expect(displayName(device())).toBe('192.168.1.50');
  });
});

describe('model', () => {
  it('reads UPnP data and TXT records', () => {
    expect(model(device({ upnp: { manufacturer: 'Samsung', modelName: 'QE55' } }))).toBe('Samsung QE55');
    expect(model(device({ services: [service('_googlecast._tcp', 'TV', { md: 'Chromecast Ultra' })] }))).toBe('Chromecast Ultra');
    expect(model(device())).toBeUndefined();
  });
});

describe('labels', () => {
  it('names ports and services', () => {
    expect(portLabel(22)).toBe('22 · SSH');
    expect(portLabel(12345)).toBe('12345');
    expect(serviceLabels(device({ services: [service('_ipp._tcp'), service('_ipps._tcp'), service('_custom._tcp')] }))).toEqual(['Printer', '_custom._tcp']);
  });
});
