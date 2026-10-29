type Child = Node | string | number | null | undefined | false;
type Props = Record<string, string | number | boolean | undefined | null | ((event: Event) => void)>;

/**
 * Creates an element: h('a', { class: 'button', href }, 'Open'). Props starting with "on" become
 * event listeners; false, null and undefined props and children are skipped.
 */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Props | null = null, ...children: Child[]): HTMLElementTagNameMap[K] {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(props ?? {})) {
    if (value === undefined || value === null || value === false) continue;
    if (typeof value === 'function') element.addEventListener(name.slice(2).toLowerCase(), value);
    else element.setAttribute(name, value === true ? '' : String(value));
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    element.append(typeof child === 'number' ? String(child) : child);
  }
  return element;
}
