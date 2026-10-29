import { afterEach, describe, expect, it, vi } from 'vitest';
import { sources, type Reading } from './sources';

const source = (id: string) => sources.find((s) => s.id === id)!;

async function collect(id: string, fire: () => void): Promise<Reading[]> {
  let readings: Reading[] = [];
  const stop = await source(id).start({ reading: (r) => (readings = r), error: (e) => { throw new Error(e); } });
  fire();
  stop();
  return readings;
}

const values = (readings: Reading[]) => Object.fromEntries(readings.map((r) => [r.label, r.value]));

describe('sensor sources', () => {
  afterEach(() => {
    delete (DeviceOrientationEvent as { requestPermission?: unknown }).requestPermission;
    vi.restoreAllMocks();
  });

  const permissionState = (state: PermissionState | Error) =>
    vi
      .spyOn(navigator.permissions, 'query')
      .mockImplementation(async () => {
        if (state instanceof Error) throw state;
        return { state } as PermissionStatus;
      });

  it('reads acceleration from device motion events', async () => {
    const readings = await collect('acceleration', () => {
      const event = new Event('devicemotion') as DeviceMotionEvent;
      Object.assign(event, { accelerationIncludingGravity: { x: 3, y: 0, z: 4 } });
      window.dispatchEvent(event);
    });
    expect(values(readings)).toEqual({ X: 3, Y: 0, Z: 4, Total: 5 });
  });

  it('derives the compass heading from absolute orientation', async () => {
    const type = 'ondeviceorientationabsolute' in window ? 'deviceorientationabsolute' : 'deviceorientation';
    const readings = await collect('orientation', () => {
      const event = new Event(type);
      Object.assign(event, { absolute: true, alpha: 90, beta: 10, gamma: -5 });
      window.dispatchEvent(event);
    });
    expect(values(readings)).toMatchObject({ Heading: 270, 'β (X)': 10, 'γ (Y)': -5 });
  });

  it('asks for permission where the browser requires it (iOS)', async () => {
    Object.assign(DeviceOrientationEvent, { requestPermission: async () => 'denied' });
    permissionState(new TypeError('Unknown permission name'));
    expect(await source('orientation').requiresGesture()).toBe(true);
    await expect(source('orientation').start({ reading() {}, error() {} })).rejects.toThrow(/Permission denied/);
  });

  it('starts without a tap when motion access is already granted (Chrome)', async () => {
    Object.assign(DeviceOrientationEvent, { requestPermission: async () => 'granted' });
    permissionState('granted');
    expect(await source('orientation').requiresGesture()).toBe(false);
    permissionState('prompt');
    expect(await source('orientation').requiresGesture()).toBe(true);
  });

  it('marks temperature as unsupported', () => {
    expect(source('temperature').isSupported()).toBe(false);
  });
});
