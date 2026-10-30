import { describe, expect, it } from 'vitest';
import { APK_URL, appRequiredCard, cardHeader } from './cards';

describe('cards', () => {
  it('builds a header with an optional status', () => {
    expect(cardHeader('🌐', 'Connection', 'Online').textContent).toBe('🌐ConnectionOnline');
    expect(cardHeader('🌐', 'Connection').querySelector('.status')).toBeNull();
  });

  it('links the Android app and explains why it is needed', () => {
    const card = appRequiredCard('Browsers cannot do this.');
    expect(card.textContent).toContain('Android app needed');
    expect(card.textContent).toContain('Browsers cannot do this.');
    expect(card.querySelector('a.button')?.getAttribute('href')).toBe(APK_URL);
  });
});
