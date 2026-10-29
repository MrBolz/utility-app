import { describe, expect, it } from 'vitest';
import { moduleHref, parseRoute } from './router';

describe('parseRoute', () => {
  it.each(['', '#', '#/', '#/unknown'])('treats %j as home', (hash) => {
    expect(parseRoute(hash)).toEqual({ name: 'home' });
  });

  it('parses module routes', () => {
    expect(parseRoute('#/m/hello')).toEqual({ name: 'module', id: 'hello' });
    expect(parseRoute('#/m/hello/')).toEqual({ name: 'module', id: 'hello' });
  });

  it('round-trips with moduleHref', () => {
    expect(parseRoute(moduleHref('unit-converter'))).toEqual({ name: 'module', id: 'unit-converter' });
  });
});
