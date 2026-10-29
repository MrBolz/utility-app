import { describe, expect, it, vi } from 'vitest';
import { h } from './dom';

describe('h', () => {
  it('sets attributes, listeners and children', () => {
    const onClick = vi.fn();
    const button = h('button', { class: 'button', disabled: false, 'aria-label': 'Go', onClick }, 'Go ', 3, null, h('span', null, '!'));
    expect(button.outerHTML).toBe('<button class="button" aria-label="Go">Go 3<span>!</span></button>');
    button.click();
    expect(onClick).toHaveBeenCalledOnce();
  });
});
