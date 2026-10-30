import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'io.github.mrbolz.utilityapp',
  appName: 'Brutus',
  webDir: 'dist',
  // CI ships a debug build, where Capacitor would otherwise log every plugin call's arguments,
  // including Tuya local keys and the cloud secret, to logcat.
  loggingBehavior: 'none',
};

export default config;
