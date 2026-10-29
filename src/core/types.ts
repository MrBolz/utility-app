/** A self-contained feature of the app, shown as a tile on the home screen. */
export interface UtilityModule {
  /** URL-safe identifier, used in the route: #/m/<id> */
  id: string;
  title: string;
  description: string;
  /** Emoji or short text shown on the home tile. */
  icon: string;
  /**
   * Render the module into `container`. May return a cleanup function,
   * which is called when the user navigates away.
   */
  mount(container: HTMLElement): void | (() => void);
}
