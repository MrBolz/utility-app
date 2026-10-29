import { describe, expect, it } from 'vitest';
import { hello } from '.';

describe('hello module', () => {
  it('counts taps', () => {
    const container = document.createElement('div');
    hello.mount(container);
    const button = container.querySelector('button')!;
    expect(button.textContent).toBe('Tapped 0 times');
    button.click();
    expect(button.textContent).toBe('Tapped 1 time');
  });
});
