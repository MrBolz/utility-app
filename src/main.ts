import { Capacitor } from '@capacitor/core';
import { registerSW } from 'virtual:pwa-register';
import { startApp } from './core/app';
import { ModuleRegistry } from './core/registry';
import { modules } from './modules';
import './styles.css';

const registry = new ModuleRegistry();
modules.forEach((module) => registry.register(module));

startApp(document.querySelector<HTMLElement>('#app')!, registry);
// The Android app ships its files inside the APK; a service worker would only serve stale copies there.
if (!Capacitor.isNativePlatform()) registerSW({ immediate: true });
