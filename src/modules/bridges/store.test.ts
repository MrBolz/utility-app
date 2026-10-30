import { describe, expect, it, vi } from 'vitest';
import { fakePlugin, sampleState } from './fake-plugin';
import { BridgesStore } from './store';

describe('BridgesStore', () => {
  it('loads the state from the plugin', async () => {
    const { plugin } = fakePlugin();
    const store = new BridgesStore(plugin);
    const changed = vi.fn();
    store.subscribe(changed);

    const loading = store.refresh();
    expect(store.isBusy('load')).toBe(true);
    await loading;
    expect(store.isBusy('load')).toBe(false);
    expect(store.state.data?.devices.map((device) => device.id)).toEqual(['bf01', 'bf02', 'bf03']);
    expect(changed).toHaveBeenCalled();
  });

  it('saves the bridge and builds quick changes on each other', async () => {
    const fake = fakePlugin();
    const store = new BridgesStore(fake.plugin);
    await store.refresh();

    const first = store.updateBridge({ deviceIds: ['bf02', 'bf03'] });
    // Shown at once, before the plugin answered.
    expect(store.state.data?.bridge.deviceIds).toEqual(['bf02', 'bf03']);
    const second = store.updateBridge({ enabled: false });
    await Promise.all([first, second]);

    expect(fake.plugin.saveBridge).toHaveBeenLastCalledWith({ enabled: false, deviceIds: ['bf02', 'bf03'], clockPackages: [] });
    expect(fake.state.bridge).toEqual({ enabled: false, deviceIds: ['bf02', 'bf03'], clockPackages: [] });
    expect(store.state.data?.bridge.enabled).toBe(false);
  });

  it('keeps errors per action and restores the bridge when saving fails', async () => {
    const fake = fakePlugin();
    const store = new BridgesStore(fake.plugin);
    await store.refresh();

    fake.plugin.saveBridge.mockRejectedValueOnce(new Error('Could not schedule the alarm'));
    await store.updateBridge({ enabled: false });
    expect(store.state.errors.bridge).toBe('Could not schedule the alarm');
    expect(store.state.data?.bridge.enabled).toBe(true);

    fake.plugin.setSwitch.mockRejectedValueOnce(new Error('The plug is busy (another app is connected to it)'));
    await store.setSwitch('bf02', true);
    expect(store.state.errors['switch:bf02']).toBe('The plug is busy (another app is connected to it)');
    expect(store.state.errors.bridge).toBe('Could not schedule the alarm');

    // A new attempt clears the old error.
    await store.setSwitch('bf02', true);
    expect(store.state.errors['switch:bf02']).toBeUndefined();
    expect(store.state.notes['switch:bf02']).toBe('Switched on');
  });

  it('reports a failed first load', async () => {
    const fake = fakePlugin();
    fake.plugin.getState.mockRejectedValueOnce(new Error('Bridges plugin not ready'));
    const store = new BridgesStore(fake.plugin);
    await store.refresh();
    expect(store.state.data).toBeUndefined();
    expect(store.state.errors.load).toBe('Bridges plugin not ready');
  });

  it('runs a test, imports from the cloud and remembers discovered devices', async () => {
    const fake = fakePlugin(sampleState({ devices: [], bridge: { enabled: false, deviceIds: [], clockPackages: [] } }));
    const store = new BridgesStore(fake.plugin);
    await store.refresh();

    expect(await store.saveCloud({ accessId: 'abc', accessSecret: 's3cret', region: 'central-europe' })).toBe(true);
    expect(store.state.data?.cloud).toEqual({ accessId: 'abc', region: 'central-europe', hasSecret: true });
    await store.importFromCloud();
    expect(store.state.notes.cloud).toBe('No devices found in the cloud project');

    fake.plugin.discover.mockResolvedValueOnce({ state: sampleState(), found: [{ id: 'bf09', ip: '192.168.1.40', version: '3.5', known: false }] });
    await store.discover();
    expect(store.state.found).toHaveLength(1);
    expect(store.state.data?.devices).toHaveLength(3);

    expect(await store.saveDevice({ id: 'bf09', ip: '192.168.1.40', version: '3.5', dpId: '1', localKey: 'k' })).toBe(true);
    expect(store.state.found?.[0].known).toBe(true);
    expect(store.state.notes['device-form']).toBe('Device added');

    await store.runNow();
    expect(store.state.lastTest?.trigger).toBe('test');
  });
});
