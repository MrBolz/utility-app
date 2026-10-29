/** One value shown in a sensor card, e.g. the x axis of the accelerometer. */
export interface Reading {
  label: string;
  value: number | null;
  unit?: string;
  decimals?: number;
  /** When set, a bar shows where `value` lies between `min` and `max`. */
  min?: number;
  max?: number;
  /** Shown instead of the number, e.g. "Charging". */
  text?: string;
}

export interface SensorListener {
  reading(readings: Reading[]): void;
  error(message: string): void;
}

export interface SensorSource {
  id: string;
  title: string;
  icon: string;
  isSupported(): boolean;
  /** Shown when the sensor is not supported. */
  unsupportedReason?: string;
  /** How long to wait for a first reading before showing "No data"; defaults to 3 s. */
  noDataTimeoutMs?: number;
  /** True when start() must be called from a tap because it may show a permission prompt. */
  requiresGesture(): Promise<boolean>;
  /**
   * Start delivering readings. Throws when access is denied. Must not await
   * anything before asking for permission, so the tap still counts as user activation.
   */
  start(listener: SensorListener): Promise<() => void>;
}

// Browser APIs that are missing from TypeScript's DOM typings.
type PermissionRequester = { requestPermission?: () => Promise<PermissionState> };
interface IosOrientationEvent extends DeviceOrientationEvent {
  webkitCompassHeading?: number;
}
interface BatteryManager extends EventTarget {
  level: number;
  charging: boolean;
  chargingTime: number;
  dischargingTime: number;
}
interface GenericSensor extends EventTarget {
  start(): void;
  stop(): void;
}
type GenericSensorConstructor = new (options?: { frequency?: number }) => GenericSensor;

const G = 9.81;

/** iOS 13+ needs an explicit permission request for motion and orientation events. */
function permissionRequester(event: 'DeviceMotionEvent' | 'DeviceOrientationEvent') {
  const ctor = (globalThis as Record<string, unknown>)[event] as PermissionRequester | undefined;
  return typeof ctor?.requestPermission === 'function' ? ctor.requestPermission.bind(ctor) : undefined;
}

/**
 * A tap is only needed when the browser has requestPermission() and access is not granted yet.
 * Chrome grants motion sensors by default and reports that via the Permissions API; Safari has
 * no such query, so it always needs the tap.
 */
async function motionNeedsGesture(event: 'DeviceMotionEvent' | 'DeviceOrientationEvent') {
  if (!permissionRequester(event)) return false;
  try {
    const status = await navigator.permissions.query({ name: 'accelerometer' as PermissionName });
    return status.state !== 'granted';
  } catch {
    return true;
  }
}

async function requestIfNeeded(event: 'DeviceMotionEvent' | 'DeviceOrientationEvent') {
  const request = permissionRequester(event);
  if (request && (await request()) !== 'granted') {
    throw new Error('Permission denied. Allow motion sensors in the site settings of your browser.');
  }
}

function listen<K extends keyof WindowEventMap>(type: K, handler: (event: WindowEventMap[K]) => void) {
  window.addEventListener(type, handler);
  return () => window.removeEventListener(type, handler);
}

function motionSource(
  id: string,
  title: string,
  icon: string,
  toReadings: (event: DeviceMotionEvent) => Reading[],
): SensorSource {
  return {
    id,
    title,
    icon,
    isSupported: () => 'DeviceMotionEvent' in window,
    requiresGesture: () => motionNeedsGesture('DeviceMotionEvent'),
    async start(listener) {
      await requestIfNeeded('DeviceMotionEvent');
      return listen('devicemotion', (event) => listener.reading(toReadings(event)));
    },
  };
}

const axis = (label: string, value: number | null | undefined, unit: string, limit: number, decimals = 2): Reading => ({
  label,
  value: value ?? null,
  unit,
  decimals,
  min: -limit,
  max: limit,
});

const acceleration = motionSource('acceleration', 'Acceleration', '📈', (event) => {
  const a = event.accelerationIncludingGravity;
  const total = a?.x != null && a.y != null && a.z != null ? Math.hypot(a.x, a.y, a.z) : null;
  return [
    axis('X', a?.x, 'm/s²', 2 * G),
    axis('Y', a?.y, 'm/s²', 2 * G),
    axis('Z', a?.z, 'm/s²', 2 * G),
    { label: 'Total', value: total, unit: 'm/s²', min: 0, max: 2 * G },
  ];
});

const gyroscope = motionSource('gyroscope', 'Rotation rate', '🌀', (event) => {
  const r = event.rotationRate;
  return [
    axis('α (Z)', r?.alpha, '°/s', 360, 1),
    axis('β (X)', r?.beta, '°/s', 360, 1),
    axis('γ (Y)', r?.gamma, '°/s', 360, 1),
  ];
});

const orientation: SensorSource = {
  id: 'orientation',
  title: 'Orientation',
  icon: '🧭',
  isSupported: () => 'DeviceOrientationEvent' in window,
  requiresGesture: () => motionNeedsGesture('DeviceOrientationEvent'),
  async start(listener) {
    await requestIfNeeded('DeviceOrientationEvent');
    // Chrome on Android reports the earth-referenced orientation in a separate event.
    const type = 'ondeviceorientationabsolute' in window ? 'deviceorientationabsolute' : 'deviceorientation';
    const handler = (event: Event) => {
      const e = event as IosOrientationEvent;
      const heading = e.webkitCompassHeading ?? (e.absolute && e.alpha != null ? (360 - e.alpha) % 360 : null);
      listener.reading([
        { label: 'Heading', value: heading, unit: '°', decimals: 0, min: 0, max: 360 },
        { label: 'α (Z)', value: e.alpha, unit: '°', decimals: 1, min: 0, max: 360 },
        { label: 'β (X)', value: e.beta, unit: '°', decimals: 1, min: -180, max: 180 },
        { label: 'γ (Y)', value: e.gamma, unit: '°', decimals: 1, min: -90, max: 90 },
      ]);
    };
    window.addEventListener(type, handler);
    return () => window.removeEventListener(type, handler);
  },
};

function genericSensor(
  id: string,
  title: string,
  icon: string,
  constructorName: string,
  toReadings: (sensor: GenericSensor) => Reading[],
): SensorSource {
  const ctor = () => (globalThis as Record<string, unknown>)[constructorName] as GenericSensorConstructor | undefined;
  return {
    id,
    title,
    icon,
    isSupported: () => typeof ctor() === 'function',
    requiresGesture: async () => false,
    async start(listener) {
      const Sensor = ctor()!;
      const sensor = new Sensor({ frequency: 10 });
      sensor.addEventListener('reading', () => listener.reading(toReadings(sensor)));
      sensor.addEventListener('error', (event) => {
        const error = (event as Event & { error?: DOMException }).error;
        listener.error(error?.name === 'NotAllowedError' ? 'Permission denied' : (error?.message ?? 'Sensor error'));
      });
      sensor.start();
      return () => sensor.stop();
    },
  };
}

const light = genericSensor('light', 'Ambient light', '💡', 'AmbientLightSensor', (sensor) => [
  { label: 'Illuminance', value: (sensor as GenericSensor & { illuminance: number }).illuminance, unit: 'lx', decimals: 0, min: 0, max: 1000 },
]);

const magnetometer = genericSensor('magnetometer', 'Magnetic field', '🧲', 'Magnetometer', (sensor) => {
  const m = sensor as GenericSensor & { x: number; y: number; z: number };
  return [axis('X', m.x, 'µT', 100), axis('Y', m.y, 'µT', 100), axis('Z', m.z, 'µT', 100)];
});

const location: SensorSource = {
  id: 'location',
  title: 'Location',
  icon: '📍',
  isSupported: () => 'geolocation' in navigator,
  // A first GPS fix can take a while, especially indoors.
  noDataTimeoutMs: 60_000,
  async requiresGesture() {
    try {
      return (await navigator.permissions.query({ name: 'geolocation' })).state !== 'granted';
    } catch {
      return true;
    }
  },
  async start(listener) {
    const id = navigator.geolocation.watchPosition(
      ({ coords }) =>
        listener.reading([
          { label: 'Latitude', value: coords.latitude, unit: '°', decimals: 6 },
          { label: 'Longitude', value: coords.longitude, unit: '°', decimals: 6 },
          { label: 'Accuracy', value: coords.accuracy, unit: 'm', decimals: 0 },
          { label: 'Altitude', value: coords.altitude, unit: 'm', decimals: 0 },
          { label: 'Speed', value: coords.speed == null ? null : coords.speed * 3.6, unit: 'km/h', decimals: 1 },
          { label: 'Heading', value: coords.heading, unit: '°', decimals: 0 },
        ]),
      (error) => listener.error(error.code === error.PERMISSION_DENIED ? 'Permission denied' : error.message),
      { enableHighAccuracy: true },
    );
    return () => navigator.geolocation.clearWatch(id);
  },
};

const battery: SensorSource = {
  id: 'battery',
  title: 'Battery',
  icon: '🔋',
  isSupported: () => 'getBattery' in navigator,
  requiresGesture: async () => false,
  async start(listener) {
    const manager = await (navigator as Navigator & { getBattery(): Promise<BatteryManager> }).getBattery();
    const update = () =>
      listener.reading([
        { label: 'Level', value: manager.level * 100, unit: '%', decimals: 0, min: 0, max: 100 },
        { label: 'State', value: null, text: manager.charging ? 'Charging' : 'On battery' },
      ]);
    const events = ['levelchange', 'chargingchange'];
    events.forEach((type) => manager.addEventListener(type, update));
    update();
    return () => events.forEach((type) => manager.removeEventListener(type, update));
  },
};

const temperature: SensorSource = {
  id: 'temperature',
  title: 'Temperature',
  icon: '🌡️',
  isSupported: () => false,
  unsupportedReason: 'Browsers do not let web apps read temperature sensors.',
  requiresGesture: async () => false,
  start: () => Promise.reject(new Error('Not supported')),
};

export const sources: SensorSource[] = [
  acceleration,
  gyroscope,
  orientation,
  magnetometer,
  light,
  location,
  battery,
  temperature,
];
