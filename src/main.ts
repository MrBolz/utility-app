import { registerSW } from 'virtual:pwa-register';
import { startApp } from './core/app';
import { ModuleRegistry } from './core/registry';
import { modules } from './modules';
import './styles.css';

const registry = new ModuleRegistry();
modules.forEach((module) => registry.register(module));

startApp(document.querySelector<HTMLElement>('#app')!, registry);
registerSW({ immediate: true });
