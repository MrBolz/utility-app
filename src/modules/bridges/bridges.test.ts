import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakePlugin, sampleState } from './fake-plugin';

const fake = fakePlugin();
let native = false;

vi.mock('./plugin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./plugin')>()),
  isBridgesAvailable: () => native,
  Bridges: fake.plugin,
}));

const { bridges } = await import('.');

function button(container: HTMLElement, label: string): HTMLButtonElement {
  const found = [...container.querySelectorAll('button')].find((b) => b.textContent === label);
  if (!found) throw new Error(`No button "${label}"`);
  return found;
}

function input(container: HTMLElement, name: string): HTMLInputElement {
  return container.querySelector<HTMLInputElement>(`[name="${name}"]`)!;
}

describe('bridges module', () => {
  let cleanup: (() => void) | void;
  let container: HTMLElement;

  beforeEach(() => {
    native = true;
    fake.setState(sampleState());
    vi.clearAllMocks();
    container = document.createElement('div');
    document.body.append(container);
  });

  afterEach(() => {
    cleanup?.();
    container.remove();
  });

  /** Mounts and waits until the state loaded on mount is rendered (the store outlives the view, like in the app). */
  async function mount() {
    cleanup = bridges.mount(container);
    await fake.plugin.getState.mock.results.at(-1)?.value;
    await new Promise((resolve) => requestAnimationFrame(resolve));
  }

  it('offers the Android app in the browser', () => {
    native = false;
    cleanup = bridges.mount(container);
    expect(container.textContent).toContain('Android app needed');
    expect(container.querySelector('a.button')?.getAttribute('href')).toMatch(/brutus\.apk$/);
    expect(fake.plugin.getState).not.toHaveBeenCalled();
  });

  it('shows the bridge with the next alarm and sockets first in the checklist', async () => {
    await mount();
    const card = container.querySelector('.card')!;
    const time = new Intl.DateTimeFormat(undefined, { weekday: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(2026, 10, 3, 6, 30));
    expect(card.querySelector('.status')?.textContent).toBe('Active');
    expect(card.querySelector('.switch')).toHaveProperty('checked', true);
    expect(card.querySelector('.next-alarm')?.textContent).toContain(`Next alarm: ${time} · Clock`);
    expect(card.querySelector('.next-alarm')?.textContent).toContain('Will switch on Coffee machine.');
    expect([...card.querySelectorAll('.section-label')].map((label) => label.textContent)).toEqual(['Sockets', 'Other devices']);
    const items = [...card.querySelectorAll<HTMLLabelElement>('.check')];
    expect(items.map((item) => item.querySelector('.device-name')?.textContent)).toEqual(['Bedroom heater', 'Coffee machine', 'Desk lamp']);
    expect(items.map((item) => item.querySelector('input')!.checked)).toEqual([false, true, false]);
    expect(items[0].textContent).toContain('IP unknown · no local key');
  });

  it('saves the ticked devices', async () => {
    await mount();
    const heater = [...container.querySelectorAll<HTMLLabelElement>('.check')].find((item) => item.textContent?.includes('Bedroom heater'))!;
    heater.querySelector('input')!.click();
    expect(fake.plugin.saveBridge).toHaveBeenCalledWith({ enabled: true, deviceIds: ['bf02', 'bf03'], clockPackages: [] });
    // The heater has neither key nor IP, so the bridge needs setup now.
    await vi.waitFor(() => expect(container.querySelector('.status')?.textContent).toBe('Needs setup'));
    expect(container.querySelector('.hint-list')?.textContent).toContain('Bedroom heater has no local key');

    const coffee = [...container.querySelectorAll<HTMLLabelElement>('.check')].find((item) => item.textContent?.includes('Coffee machine'))!;
    coffee.querySelector('input')!.click();
    expect(fake.plugin.saveBridge).toHaveBeenLastCalledWith({ enabled: true, deviceIds: ['bf03'], clockPackages: [] });
  });

  it('runs a test and shows the result per device', async () => {
    await mount();
    fake.plugin.runNow.mockImplementationOnce(async () => {
      const run = { at: Date.now(), trigger: 'test' as const, results: [{ id: 'bf02', name: 'Coffee machine', ok: false, message: 'Plug not reachable at 192.168.1.20' }] };
      return { state: { ...sampleState(), runs: [run] }, run };
    });
    button(container, 'Test now').click();
    expect(fake.plugin.runNow).toHaveBeenCalledOnce();
    await vi.waitFor(() => expect(container.querySelector('.run-result')?.textContent).toContain('Test: 0 of 1 switched on'));
    expect(container.querySelector('.run-result')?.textContent).toContain('✗Coffee machine · Plug not reachable at 192.168.1.20');
    expect(container.querySelector('.disclosure:not(.form-section) summary')?.textContent).toBe('Recent runs1');
  });

  it('shows plugin errors in the card that caused them', async () => {
    await mount();
    fake.plugin.setSwitch.mockRejectedValueOnce(new Error('The plug is busy (another app is connected to it)'));
    const row = [...container.querySelectorAll('.row')].find((item) => item.textContent?.includes('Coffee machine'))!;
    button(row as HTMLElement, 'On').click();
    expect(fake.plugin.setSwitch).toHaveBeenCalledWith({ id: 'bf02', on: true });
    await vi.waitFor(() => expect(container.querySelector('.row .feedback')?.textContent).toBe('The plug is busy (another app is connected to it)'));
    expect(container.querySelector('.feedback')?.closest('.row')?.textContent).toContain('Coffee machine');
  });

  it('saves the cloud settings, imports the devices and clears the secret', async () => {
    await mount();
    const form = input(container, 'accessSecret').form!;
    expect(input(container, 'accessSecret').placeholder).toBe('');
    input(container, 'accessId').value = 'p4x7';
    input(container, 'accessSecret').value = 'very-secret';
    container.querySelector<HTMLSelectElement>('[name="region"]')!.value = 'western-europe';
    form.requestSubmit();

    await vi.waitFor(() => expect(fake.plugin.importFromCloud).toHaveBeenCalledOnce());
    expect(fake.plugin.saveCloud).toHaveBeenCalledWith({ accessId: 'p4x7', accessSecret: 'very-secret', region: 'western-europe' });
    expect(fake.plugin.saveCloud.mock.invocationCallOrder[0]).toBeLessThan(fake.plugin.importFromCloud.mock.invocationCallOrder[0]);
    expect(input(container, 'accessSecret').value).toBe('');
    await vi.waitFor(() => expect(form.textContent).toContain('Imported 3 devices'));
    expect(input(container, 'accessSecret').placeholder).toBe('saved');
    expect(container.innerHTML).not.toContain('very-secret');

    // With a saved secret the field may stay empty: the plugin keeps the secret.
    form.requestSubmit();
    await vi.waitFor(() => expect(fake.plugin.importFromCloud).toHaveBeenCalledTimes(2));
    expect(fake.plugin.saveCloud).toHaveBeenLastCalledWith({ accessId: 'p4x7', region: 'western-europe' });
  });

  it('adds and edits devices without showing their keys again', async () => {
    await mount();
    const form = input(container, 'localKey').form!;
    input(container, 'id').value = 'bf77';
    input(container, 'localKey').value = 'k3y!k3y!k3y!k3y!';
    input(container, 'ip').value = '192.168.1.77';
    form.requestSubmit();
    await vi.waitFor(() => expect(fake.plugin.saveDevice).toHaveBeenCalledWith({ id: 'bf77', dpId: '1', ip: '192.168.1.77', localKey: 'k3y!k3y!k3y!k3y!' }));
    await vi.waitFor(() => expect(container.querySelectorAll('.check')).toHaveLength(4));
    expect(input(container, 'localKey').value).toBe('');
    expect(input(container, 'id').value).toBe('');

    const row = [...container.querySelectorAll<HTMLElement>('.row')].find((item) => item.textContent?.includes('Coffee machine'))!;
    button(row, 'Edit').click();
    expect(input(container, 'id').value).toBe('bf02');
    expect(input(container, 'id').readOnly).toBe(true);
    expect(input(container, 'localKey').value).toBe('');
    expect(input(container, 'localKey').placeholder).toBe('saved');
    input(container, 'ip').value = '192.168.1.21';
    form.requestSubmit();
    await vi.waitFor(() => expect(fake.plugin.saveDevice).toHaveBeenLastCalledWith({ id: 'bf02', dpId: '1', name: 'Coffee machine', ip: '192.168.1.21', version: '3.4' }));

    // Emptying the IP field clears the stored address.
    button(row, 'Edit').click();
    input(container, 'ip').value = '';
    form.requestSubmit();
    await vi.waitFor(() => expect(fake.plugin.saveDevice).toHaveBeenLastCalledWith({ id: 'bf02', dpId: '1', name: 'Coffee machine', ip: '', version: '3.4' }));
  });

  it('only lists found plugs that are not in the device list yet', async () => {
    fake.plugin.discover.mockImplementationOnce(async () => ({
      state: sampleState(),
      found: [
        { id: 'bf77', ip: '192.168.1.77', version: '3.5', known: false },
        { id: 'bf02', ip: '192.168.178.52', version: '3.4', known: true },
      ],
    }));
    await mount();
    button(container, 'Find on network').click();
    await vi.waitFor(() => expect(container.textContent).toContain('bf77'));

    // A cloud import adds the new plug: it disappears from "Not added yet".
    const imported = sampleState();
    imported.devices.push({ id: 'bf77', name: 'Hall plug', ip: '192.168.1.77', version: '3.5', dpId: '1', category: 'cz', hasKey: true });
    fake.setState(imported);
    const form = input(container, 'accessId').form!;
    input(container, 'accessId').value = 'p4x7';
    input(container, 'accessSecret').value = 's3cr3t';
    form.requestSubmit();
    await vi.waitFor(() => expect(fake.plugin.importFromCloud).toHaveBeenCalled());
    await vi.waitFor(() => expect(container.textContent).toContain('Hall plug'));
    expect(container.textContent).not.toContain('Not added yet');
  });

  it('opens Android settings from the setup checklist and refreshes when the app is visible again', async () => {
    await mount();
    const setup = [...container.querySelectorAll('.card')][1] as HTMLElement;
    expect(setup.querySelector('.status')?.textContent).toBe('1 to fix');
    button(setup, 'Allow').click();
    expect(fake.plugin.openSettings).toHaveBeenCalledWith({ target: 'battery' });

    fake.setState(sampleState({ permissions: { exactAlarms: true, batteryUnrestricted: true, notifications: true, unusedAppsExempt: true } }));
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.waitFor(() => expect(setup.querySelector('.status')?.textContent).toBe('All set'));

    // System dialogs leave the page visible; Capacitor's resume event still refreshes.
    fake.setState(sampleState({ permissions: { exactAlarms: true, batteryUnrestricted: false, notifications: true, unusedAppsExempt: true } }));
    document.dispatchEvent(new Event('resume'));
    await vi.waitFor(() => expect(setup.querySelector('.status')?.textContent).toBe('1 to fix'));

    // Hibernation of unused apps is turned off on the app's settings page.
    fake.setState(sampleState({ permissions: { exactAlarms: true, batteryUnrestricted: true, notifications: true, unusedAppsExempt: false } }));
    document.dispatchEvent(new Event('resume'));
    const row = () => [...setup.querySelectorAll<HTMLElement>('li')].find((item) => item.textContent?.includes('Keep running when unused'))!;
    await vi.waitFor(() => expect(row().dataset.ok).toBe('false'));
    expect(row().textContent).toContain('Pause app activity if unused');
    button(row(), 'Allow').click();
    expect(fake.plugin.openSettings).toHaveBeenLastCalledWith({ target: 'appInfo' });
  });
});
