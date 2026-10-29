import { appRequiredCard, cardHeader } from '../../core/cards';
import { h } from '../../core/dom';
import type { UtilityModule } from '../../core/types';
import {
  Bridges,
  isBridgesAvailable,
  isSocket,
  REGIONS,
  type BridgePermissions,
  type BridgeRun,
  type BridgeRunResult,
  type BridgesState,
  type DeviceChanges,
  type DiscoveredDevice,
  type SettingsTarget,
  type TuyaDeviceInfo,
  type TuyaRegion,
} from './plugin';
import { BridgesStore, count, type BridgesStoreState, type Scope } from './store';

let store: BridgesStore | undefined;

export const bridges: UtilityModule = {
  id: 'bridges',
  title: 'Bridges',
  icon: '🌉',
  mount(container) {
    if (!isBridgesAvailable()) {
      container.append(
        h('div', { class: 'card-grid' }, appRequiredCard('Bridges switch on smart plugs on your Wi-Fi when your alarm rings. Browsers cannot wake up for an alarm or talk to plugs; the Android version of this app can.')),
      );
      return;
    }
    const current = (store ??= new BridgesStore(Bridges));
    const view = new BridgesView(container, current);
    // Coming back from Android settings (or from the background) may have changed permissions and the next alarm.
    const onVisible = () => {
      if (document.visibilityState === 'visible') void current.refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    void current.refresh();
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      view.destroy();
    };
  },
};

const CATEGORIES: Record<string, { icon: string; label: string }> = {
  cz: { icon: '🔌', label: 'Socket' },
  pc: { icon: '🔌', label: 'Power strip' },
  kg: { icon: '🔘', label: 'Switch' },
  dj: { icon: '💡', label: 'Light' },
  dd: { icon: '💡', label: 'Light strip' },
};
const OTHER = { icon: '📟', label: 'Device' };

const SETUP: { target: SettingsTarget; key: keyof BridgePermissions; title: string; why: string }[] = [
  { target: 'exactAlarms', key: 'exactAlarms', title: 'Exact alarms', why: 'Starts the bridge the moment your alarm rings.' },
  { target: 'battery', key: 'batteryUnrestricted', title: 'Unrestricted battery use', why: 'Keeps Android from delaying the bridge to save power.' },
  { target: 'notifications', key: 'notifications', title: 'Notifications', why: 'Tells you what the bridge switched on, or what went wrong.' },
];

const VERSIONS = ['3.1', '3.3', '3.4', '3.5'];
const DEFAULT_VERSION = '3.3';
/** Option value of a clock filter with several apps, which the select can only keep. */
const SEVERAL_APPS = '*';

const alarmTime = new Intl.DateTimeFormat(undefined, { weekday: 'short', hour: 'numeric', minute: '2-digit' });
const runTime = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
const nameList = new Intl.ListFormat('en', { type: 'conjunction' });

class BridgesView {
  private readonly bridgeCard = h('section', { class: 'card' });
  private readonly setupCard = h('section', { class: 'card' });
  private readonly devicesTop = h('div');
  private readonly devicesCard: HTMLElement;
  private readonly cloudForm: CloudForm;
  private readonly deviceForm: DeviceForm;
  /** Whether "Recent runs" is open; kept across re-renders. */
  private runsOpen = false;
  private frame?: number;
  private readonly unsubscribe: () => void;

  constructor(container: HTMLElement, private readonly store: BridgesStore) {
    this.cloudForm = new CloudForm(store);
    this.deviceForm = new DeviceForm(store);
    // The cards stay and only their content is rebuilt, so the forms keep focus and what the user typed.
    this.devicesCard = h('section', { class: 'card' }, this.devicesTop, this.cloudForm.element, this.deviceForm.element);
    container.append(h('div', { class: 'card-grid bridges' }, this.bridgeCard, this.setupCard, this.devicesCard));
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
    const data = state.data;
    this.setupCard.hidden = this.devicesCard.hidden = !data;
    if (!data) {
      this.renderLoading(state);
      return;
    }
    this.renderBridge(state, data);
    this.renderSetup(state, data);
    this.renderDevices(state, data);
    this.cloudForm.update(state, data);
    this.deviceForm.update(state);
  }

  private renderLoading(state: BridgesStoreState): void {
    const error = state.errors.load;
    delete this.bridgeCard.dataset.state;
    this.bridgeCard.replaceChildren(
      cardHeader('⏰', 'Alarm → Sockets'),
      h(
        'div',
        { class: 'card-body' },
        h('p', { class: 'card-message' }, error ?? 'Loading…'),
        error ? h('div', { class: 'card-actions' }, h('button', { type: 'button', class: 'button', onClick: () => void this.store.refresh() }, 'Try again')) : null,
      ),
    );
  }

  private renderBridge(state: BridgesStoreState, data: BridgesState): void {
    const { bridge, devices } = data;
    const selected = devices.filter((device) => bridge.deviceIds.includes(device.id));
    const problems = bridge.enabled ? setupProblems(data, selected) : [];
    const sockets = byName(devices.filter(isSocket));
    const others = byName(devices.filter((device) => !isSocket(device)));
    const testing = this.store.isBusy('run');
    this.bridgeCard.dataset.state = !bridge.enabled ? 'idle' : problems.length ? 'starting' : 'ok';
    this.bridgeCard.replaceChildren(
      cardHeader('⏰', 'Alarm → Sockets', !bridge.enabled ? 'Off' : problems.length ? 'Needs setup' : 'Active'),
      h(
        'div',
        { class: 'card-body' },
        h(
          'label',
          { class: 'switch-row' },
          h('span', null, 'Switch sockets on when an alarm rings'),
          h('input', { type: 'checkbox', role: 'switch', class: 'switch', checked: bridge.enabled, onChange: (event) => void this.store.updateBridge({ enabled: checked(event) }) }),
        ),
        nextAlarm(data, selected),
        problems.length ? h('ul', { class: 'hint-list' }, ...problems.map((problem) => h('li', null, problem))) : null,
        this.clockFilter(data),
        devices.length === 0 ? h('div', { class: 'check-group' }, h('p', { class: 'card-message' }, 'No devices yet: import or add your plugs under Tuya devices.')) : null,
        sockets.length ? this.checklist('Sockets', sockets, bridge.deviceIds) : null,
        others.length ? this.checklist(sockets.length ? 'Other devices' : 'Devices', others, bridge.deviceIds) : null,
        feedback(state, 'bridge'),
        feedback(state, 'load'),
        h(
          'div',
          { class: 'card-actions' },
          h('button', { type: 'button', class: 'button', disabled: testing || selected.length === 0, onClick: () => void this.store.runNow() }, testing ? 'Testing…' : 'Test now'),
          h('span', { class: 'card-message' }, 'Switches the ticked devices on now.'),
        ),
        feedback(state, 'run'),
        state.lastTest ? testResult(state.lastTest) : null,
        data.runs.length ? this.recentRuns(data.runs) : null,
      ),
    );
  }

  private clockFilter({ bridge, nextAlarm }: BridgesState): HTMLElement | null {
    const packages = [...new Set([...bridge.clockPackages, ...(nextAlarm?.packageName ? [nextAlarm.packageName] : [])])];
    if (packages.length === 0) return null;
    const label = (pkg: string) => (pkg === nextAlarm?.packageName && nextAlarm.appLabel) || pkg;
    const select = h(
      'select',
      {
        class: 'input',
        onChange: (event) => {
          const value = (event.target as HTMLSelectElement).value;
          if (value !== SEVERAL_APPS) void this.store.updateBridge({ clockPackages: value ? [value] : [] });
        },
      },
      h('option', { value: '' }, 'Any clock app'),
      ...packages.map((pkg) => h('option', { value: pkg }, `Only ${label(pkg)}`)),
      bridge.clockPackages.length > 1 ? h('option', { value: SEVERAL_APPS }, `Only ${bridge.clockPackages.map(label).join(', ')}`) : null,
    );
    select.value = bridge.clockPackages.length > 1 ? SEVERAL_APPS : (bridge.clockPackages[0] ?? '');
    return field('Alarms from', selectBox(select));
  }

  private checklist(title: string, devices: TuyaDeviceInfo[], deviceIds: string[]): HTMLElement {
    return h(
      'div',
      { class: 'check-group' },
      h('p', { class: 'section-label' }, title),
      h(
        'ul',
        { class: 'check-list' },
        ...devices.map((device) =>
          h(
            'li',
            null,
            h(
              'label',
              { class: 'check' },
              h('input', { type: 'checkbox', checked: deviceIds.includes(device.id), onChange: (event) => this.select(device.id, checked(event)) }),
              deviceText(device.name, [device.productName ?? kind(device).label, device.ip ?? 'IP unknown', device.hasKey ? null : 'no local key']),
            ),
          ),
        ),
      ),
    );
  }

  private select(id: string, selected: boolean): void {
    const others = this.store.state.data!.bridge.deviceIds.filter((deviceId) => deviceId !== id);
    void this.store.updateBridge({ deviceIds: selected ? [...others, id] : others });
  }

  private recentRuns(runs: BridgeRun[]): HTMLElement {
    return h(
      'details',
      { class: 'disclosure', open: this.runsOpen, onToggle: (event) => (this.runsOpen = (event.target as HTMLDetailsElement).open) },
      h('summary', null, 'Recent runs', h('span', { class: 'badge' }, runs.length)),
      h(
        'ul',
        { class: 'plain-list' },
        ...runs.map((run) => h('li', null, h('p', { class: 'run-head' }, `${runTime.format(run.at)} · ${run.trigger === 'alarm' ? 'Alarm' : 'Test'}`), resultList(run.results))),
      ),
    );
  }

  private renderSetup(state: BridgesStoreState, { permissions }: BridgesState): void {
    const missing = SETUP.filter((item) => !permissions[item.key]).length;
    this.setupCard.dataset.state = missing ? 'starting' : 'ok';
    this.setupCard.replaceChildren(
      cardHeader('🛠️', 'Setup checklist', missing ? `${missing} to fix` : 'All set'),
      h(
        'div',
        { class: 'card-body' },
        h(
          'ul',
          { class: 'row-list' },
          ...SETUP.map((item) => {
            const ok = permissions[item.key];
            return h(
              'li',
              { class: 'row', 'data-ok': String(ok) },
              h('span', { class: 'mark', role: 'img', 'aria-label': ok ? 'Done' : 'Missing' }, ok ? '✓' : '✗'),
              deviceText(item.title, [item.why]),
              h(
                'button',
                { type: 'button', class: `button button-small${ok ? ' button-secondary' : ''}`, onClick: () => void this.store.openSettings(item.target) },
                ok ? 'Settings' : 'Allow',
              ),
            );
          }),
        ),
        feedback(state, 'setup'),
        h(
          'p',
          { class: 'card-note' },
          'Some phones also need the app allowed to autostart or kept out of "sleeping apps" in their own battery settings. ',
          h('button', { type: 'button', class: 'link-button', onClick: () => void this.store.openSettings('appInfo') }, 'App settings'),
        ),
      ),
    );
  }

  private renderDevices(state: BridgesStoreState, { devices }: BridgesState): void {
    const searching = this.store.isBusy('discover');
    const unknown = state.found?.filter((device) => !device.known) ?? [];
    this.devicesTop.replaceChildren(
      cardHeader('🔌', 'Tuya devices', devices.length ? String(devices.length) : undefined),
      h(
        'div',
        { class: 'card-body' },
        devices.length
          ? h('ul', { class: 'row-list' }, ...[...byName(devices.filter(isSocket)), ...byName(devices.filter((device) => !isSocket(device)))].map((device) => this.deviceRow(state, device)))
          : h('p', { class: 'card-message' }, 'Import your plugs from the Tuya cloud to get their local keys (needed once), or add them manually. Then find them on your Wi-Fi.'),
        h(
          'div',
          { class: 'card-actions' },
          h('button', { type: 'button', class: 'button button-secondary', disabled: searching, onClick: () => void this.store.discover() }, searching ? 'Searching…' : 'Find on network'),
          searching ? h('span', { class: 'card-message' }, 'Listening for 6 s') : state.found ? h('span', { class: 'card-message' }, foundSummary(state.found)) : null,
        ),
        feedback(state, 'discover'),
        unknown.length
          ? h(
              'div',
              { class: 'check-group' },
              h('p', { class: 'section-label' }, 'Not added yet'),
              h(
                'ul',
                { class: 'row-list' },
                ...unknown.map((device) =>
                  h(
                    'li',
                    { class: 'row' },
                    deviceText(device.id, [device.ip, device.version ? `v${device.version}` : null]),
                    h('button', { type: 'button', class: 'button button-small button-secondary', onClick: () => this.deviceForm.add(device) }, 'Add'),
                  ),
                ),
              ),
            )
          : null,
        h('p', { class: 'card-note' }, 'Brutus switches the plugs directly on your Wi-Fi. Many plugs accept only one local connection: if one reports it is busy, close other apps that control it locally.'),
      ),
    );
  }

  private deviceRow(state: BridgesStoreState, device: TuyaDeviceInfo): HTMLElement {
    const scope: Scope = `switch:${device.id}`;
    const busy = this.store.isBusy(scope);
    const button = (on: boolean) =>
      h('button', { type: 'button', class: 'button button-small button-secondary', disabled: busy || !device.hasKey, onClick: () => void this.store.setSwitch(device.id, on) }, on ? 'On' : 'Off');
    return h(
      'li',
      { class: 'row device-row' },
      h('span', { class: 'card-icon', 'aria-hidden': 'true' }, kind(device).icon),
      h(
        'span',
        { class: 'device-text' },
        h('span', { class: 'device-name' }, device.name),
        h('span', { class: 'device-sub' }, [device.ip ?? 'IP unknown', device.version ? `v${device.version}` : 'version unknown', device.hasKey ? 'key ✓' : 'key ✗'].join(' · ')),
        feedback(state, scope, 'span'),
        h(
          'span',
          { class: 'button-group' },
          button(true),
          button(false),
          h('button', { type: 'button', class: 'link-button', onClick: () => this.deviceForm.edit(device) }, 'Edit'),
        ),
      ),
    );
  }
}

/** "Import from Tuya cloud": Access ID, Access Secret and data center. The secret is never shown again once saved. */
class CloudForm {
  readonly element: HTMLDetailsElement;
  private readonly accessId = h('input', { class: 'input', name: 'accessId', required: true, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
  private readonly secret = h('input', { class: 'input', name: 'accessSecret', type: 'password', autocomplete: 'off' });
  private readonly region = h('select', { class: 'input', name: 'region' }, ...REGIONS.map((region) => h('option', { value: region.id }, region.label)));
  private readonly submit = h('button', { type: 'submit', class: 'button' });
  private readonly feedback = h('div');
  /** Saved settings only fill the fields until the user edits them. */
  private edited = false;
  private filled = false;

  constructor(private readonly store: BridgesStore) {
    const edited = () => (this.edited = true);
    this.accessId.addEventListener('input', edited);
    this.region.addEventListener('change', edited);
    this.element = h(
      'details',
      { class: 'disclosure form-section' },
      h('summary', null, 'Import from Tuya cloud'),
      h(
        'form',
        {
          class: 'form',
          onSubmit: (event) => {
            event.preventDefault();
            void this.save();
          },
        },
        h('p', { class: 'card-message' }, 'Fetches the local keys of your plugs once. After that Brutus switches them on your Wi-Fi without the cloud.'),
        h(
          'ol',
          { class: 'steps' },
          h('li', null, 'Sign up at ', h('a', { href: 'https://platform.tuya.com', target: '_blank', rel: 'noopener' }, 'platform.tuya.com'), ' with any account.'),
          h('li', null, 'Cloud → Development → Create Cloud Project. Pick the data center of your Smart Life account (Central Europe for Switzerland).'),
          h('li', null, 'In the project: Devices → Link App Account, then scan the QR code with the Smart Life app (Me → scan icon).'),
          h('li', null, 'Copy Access ID and Access Secret from the project overview into the fields below.'),
        ),
        field('Access ID', this.accessId),
        field('Access Secret', this.secret),
        field('Data center', selectBox(this.region)),
        this.feedback,
        h('div', { class: 'form-actions' }, this.submit),
      ),
    );
  }

  update(state: BridgesStoreState, { cloud, devices }: BridgesState): void {
    if (!this.filled) {
      this.filled = true;
      if (devices.length === 0) this.element.open = true;
    }
    if (!this.edited) {
      this.accessId.value = cloud.accessId ?? '';
      this.region.value = cloud.region;
    }
    this.secret.placeholder = cloud.hasSecret ? 'saved' : '';
    this.secret.required = !cloud.hasSecret;
    const busy = this.store.isBusy('cloud');
    this.submit.disabled = busy;
    this.submit.textContent = busy ? 'Importing…' : 'Import devices';
    this.feedback.replaceChildren(...present(feedback(state, 'cloud')));
  }

  private async save(): Promise<void> {
    const secret = this.secret.value.trim();
    const saved = await this.store.saveCloud({ accessId: this.accessId.value.trim(), ...(secret ? { accessSecret: secret } : {}), region: this.region.value as TuyaRegion });
    if (!saved) return;
    // From here on the secret only lives in native storage.
    this.secret.value = '';
    this.edited = false;
    await this.store.importFromCloud();
  }
}

/** "Add manually", which also edits a device when the user taps its Edit link. */
class DeviceForm {
  readonly element: HTMLDetailsElement;
  private readonly form: HTMLFormElement;
  private readonly title = h('span');
  private readonly id = h('input', { class: 'input', name: 'id', required: true, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
  private readonly localKey = h('input', { class: 'input', name: 'localKey', type: 'password', autocomplete: 'off' });
  private readonly name = h('input', { class: 'input', name: 'name', autocomplete: 'off', placeholder: 'e.g. Coffee machine' });
  private readonly ip = h('input', { class: 'input', name: 'ip', inputmode: 'decimal', autocomplete: 'off', placeholder: '192.168.1.20', pattern: '(\\d{1,3}\\.){3}\\d{1,3}' });
  private readonly version = h('select', { class: 'input', name: 'version' });
  private readonly dpId = h('input', { class: 'input', name: 'dpId', inputmode: 'numeric', required: true, pattern: '\\d+', value: '1' });
  private readonly submit = h('button', { type: 'submit', class: 'button' });
  private readonly cancel = h('button', { type: 'button', class: 'button button-secondary', onClick: () => this.reset() }, 'Cancel');
  private readonly remove = h('button', { type: 'button', class: 'button button-danger', onClick: () => void this.removeEditing() }, 'Remove');
  private readonly feedback = h('div');
  /** The device being edited; undefined while adding one. */
  private editing?: TuyaDeviceInfo;

  constructor(private readonly store: BridgesStore) {
    this.form = h(
      'form',
      {
        class: 'form',
        onSubmit: (event) => {
          event.preventDefault();
          void this.save();
        },
      },
      field('Device ID', this.id, 'Shown in the Tuya cloud project and by Find on network.'),
      field('Local key', this.localKey),
      field('Name', this.name),
      field('IP address', this.ip),
      h('div', { class: 'field-row' }, field('Protocol version', selectBox(this.version)), field('DP id', this.dpId, '1 for most plugs')),
      this.feedback,
      h('div', { class: 'form-actions' }, this.submit, this.cancel, this.remove),
    );
    this.element = h('details', { class: 'disclosure form-section' }, h('summary', null, this.title), this.form);
    this.reset();
  }

  /** Opens the form to add a device, filled in from "Find on network" if given. */
  add(found?: DiscoveredDevice): void {
    this.reset();
    if (found) {
      this.id.value = found.id;
      this.ip.value = found.ip;
      this.setVersion(found.version ?? DEFAULT_VERSION);
    }
    this.show(found ? this.localKey : this.id);
  }

  edit(device: TuyaDeviceInfo): void {
    this.reset();
    this.editing = device;
    this.id.value = device.id;
    this.id.readOnly = true;
    this.name.value = device.name;
    this.ip.value = device.ip ?? '';
    this.dpId.value = device.dpId;
    this.setVersion(device.version ?? '');
    this.localKey.required = false;
    this.localKey.placeholder = device.hasKey ? 'saved' : '';
    this.sync();
    this.show(this.name);
  }

  update(state: BridgesStoreState): void {
    // A device that is gone (e.g. just removed) can no longer be edited.
    if (this.editing && !state.data?.devices.some((device) => device.id === this.editing!.id)) this.reset();
    this.feedback.replaceChildren(...present(feedback(state, 'device-form')));
    this.sync();
  }

  private show(focus: HTMLElement): void {
    this.element.open = true;
    this.element.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
    focus.focus({ preventScroll: true });
  }

  private reset(): void {
    this.editing = undefined;
    this.form.reset();
    this.id.readOnly = false;
    this.localKey.required = true;
    this.localKey.placeholder = '';
    this.setVersion(DEFAULT_VERSION);
    this.sync();
  }

  private sync(): void {
    const busy = this.store.isBusy('device-form');
    this.title.textContent = this.editing ? `Edit ${this.editing.name}` : 'Add manually';
    this.submit.textContent = busy ? 'Saving…' : this.editing ? 'Save' : 'Add device';
    this.submit.disabled = this.remove.disabled = busy;
    this.cancel.hidden = this.remove.hidden = !this.editing;
  }

  /** Fills the version select; '' (only offered then) means the version is not known yet. */
  private setVersion(version: string): void {
    const versions = [...new Set([...VERSIONS, ...(version ? [version] : [])])].sort();
    this.version.replaceChildren(...(version ? [] : [h('option', { value: '' }, 'Unknown')]), ...versions.map((v) => h('option', { value: v }, v)));
    this.version.value = version;
  }

  private async save(): Promise<void> {
    const changes: DeviceChanges = { id: this.id.value.trim(), dpId: this.dpId.value.trim() };
    const optional = { name: this.name.value.trim(), ip: this.ip.value.trim(), version: this.version.value, localKey: this.localKey.value.trim() };
    for (const [key, value] of Object.entries(optional) as [keyof typeof optional, string][]) if (value) changes[key] = value;
    // Clears the local key field as well.
    if (await this.store.saveDevice(changes)) this.reset();
  }

  private async removeEditing(): Promise<void> {
    if (this.editing) await this.store.removeDevice(this.editing.id);
  }
}

function nextAlarm(data: BridgesState, selected: TuyaDeviceInfo[]): HTMLElement {
  const alarm = data.nextAlarm;
  if (!alarm) {
    return h('div', { class: 'next-alarm' }, h('p', null, 'No alarm set'), h('p', { class: 'card-message' }, 'Set one in your clock app; the bridge follows the next alarm.'));
  }
  const will = data.bridge.enabled && alarm.matches && data.scheduledAt !== undefined && selected.length > 0;
  let outcome: string;
  if (!data.bridge.enabled) outcome = 'The bridge is off, so this alarm switches nothing.';
  else if (!alarm.matches) outcome = 'From another app than the one selected below, so the bridge skips it.';
  else if (selected.length === 0) outcome = 'Nothing ticked to switch on.';
  else if (data.scheduledAt === undefined) outcome = 'Not scheduled yet.';
  else outcome = `Will switch on ${deviceNames(selected)}.`;
  return h(
    'div',
    { class: 'next-alarm' },
    h('p', null, 'Next alarm: ', h('strong', null, alarmTime.format(alarm.triggerAt)), ` · ${alarm.appLabel ?? alarm.packageName ?? 'unknown app'}`),
    h('p', { class: 'outcome', 'data-ok': String(will) }, h('span', { class: 'mark', 'aria-hidden': 'true' }, will ? '✓' : '✗'), outcome),
  );
}

/** What keeps an enabled bridge from switching everything on. */
function setupProblems({ permissions }: BridgesState, selected: TuyaDeviceInfo[]): string[] {
  const noKey = selected.filter((device) => !device.hasKey);
  const noIp = selected.filter((device) => !device.ip);
  const problems = [
    selected.length === 0 ? 'Tick the sockets to switch on.' : null,
    noKey.length ? `${deviceNames(noKey)} ${noKey.length === 1 ? 'has' : 'have'} no local key: import it from the Tuya cloud or add it manually.` : null,
    noIp.length ? `IP of ${deviceNames(noIp)} unknown: use Find on network.` : null,
    permissions.exactAlarms ? null : 'Allow exact alarms in the setup checklist.',
  ];
  return problems.filter((problem) => problem !== null);
}

function testResult(run: BridgeRun): HTMLElement {
  const ok = run.results.filter((result) => result.ok).length;
  return h(
    'div',
    { class: 'run-result' },
    h('p', { class: 'run-head' }, run.results.length ? `Test: ${ok} of ${run.results.length} switched on` : 'Test: nothing to switch on'),
    resultList(run.results),
  );
}

function resultList(results: BridgeRunResult[]): HTMLElement {
  return h(
    'ul',
    { class: 'result-list' },
    ...results.map((result) =>
      h(
        'li',
        { 'data-ok': String(result.ok) },
        h('span', { class: 'mark', role: 'img', 'aria-label': result.ok ? 'Switched on' : 'Failed' }, result.ok ? '✓' : '✗'),
        h('span', null, result.name, result.message ? h('span', { class: 'result-message' }, ` · ${result.message}`) : null),
      ),
    ),
  );
}

function foundSummary(found: DiscoveredDevice[]): string {
  if (found.length === 0) return 'No device answered';
  const known = found.filter((device) => device.known).length;
  return `Found ${count(found.length, 'device')}${known ? ` · ${known} updated` : ''}`;
}

/** The error or success note of the last action of a scope, if any. */
function feedback(state: BridgesStoreState, scope: Scope, tag: 'p' | 'span' = 'p'): HTMLElement | null {
  const error = state.errors[scope];
  const note = state.notes[scope];
  if (error) return h(tag, { class: 'feedback', 'data-kind': 'error' }, error);
  return note ? h(tag, { class: 'feedback', 'data-kind': 'ok' }, note) : null;
}

function deviceText(name: string, details: (string | null | undefined)[]): HTMLElement {
  return h('span', { class: 'device-text' }, h('span', { class: 'device-name' }, name), h('span', { class: 'device-sub' }, details.filter(Boolean).join(' · ')));
}

function field(label: string, control: HTMLElement, hint?: string): HTMLElement {
  return h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), control, hint ? h('span', { class: 'field-hint' }, hint) : null);
}

function selectBox(select: HTMLSelectElement): HTMLElement {
  return h('span', { class: 'select' }, select);
}

function kind(device: TuyaDeviceInfo): { icon: string; label: string } {
  return (device.category && CATEGORIES[device.category]) || OTHER;
}

function byName(devices: TuyaDeviceInfo[]): TuyaDeviceInfo[] {
  return [...devices].sort((a, b) => a.name.localeCompare(b.name));
}

function deviceNames(devices: TuyaDeviceInfo[]): string {
  return devices.length > 3 ? count(devices.length, 'device') : nameList.format(devices.map((device) => device.name));
}

function checked(event: Event): boolean {
  return (event.target as HTMLInputElement).checked;
}

function present<T>(value: T | null): T[] {
  return value === null ? [] : [value];
}
