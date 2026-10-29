import { h } from './dom';

/** Latest Android build, published by CI (.github/workflows/ci.yml). */
export const APK_URL = 'https://github.com/MrBolz/utility-app/releases/download/android-latest/brutus.apk';

/** The header of a `.card`: icon, title and an optional status pill. */
export function cardHeader(icon: string, title: string, status?: string): HTMLElement {
  return h(
    'header',
    { class: 'card-header' },
    h('span', { class: 'card-icon', 'aria-hidden': 'true' }, icon),
    h('h2', { class: 'card-title' }, title),
    status ? h('span', { class: 'status' }, status) : null,
  );
}

/** Shown in the browser by modules that need the Android app; `reason` says why and what the app can do. */
export function appRequiredCard(reason: string): HTMLElement {
  return h(
    'section',
    { class: 'card' },
    cardHeader('📲', 'Android app needed'),
    h(
      'div',
      { class: 'card-body' },
      h('p', { class: 'card-message' }, reason),
      h('p', null, h('a', { class: 'button', href: APK_URL }, 'Download the Android app')),
      h('p', { class: 'card-message' }, 'Open the downloaded file and allow installing from your browser when Android asks. Updates install the same way.'),
    ),
  );
}
