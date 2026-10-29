export type Route = { name: 'home' } | { name: 'module'; id: string };

export function parseRoute(hash: string): Route {
  const match = /^#\/m\/([^/?#]+)\/?$/.exec(hash);
  return match ? { name: 'module', id: decodeURIComponent(match[1]) } : { name: 'home' };
}

export function moduleHref(id: string): string {
  return `#/m/${encodeURIComponent(id)}`;
}
