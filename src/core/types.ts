/** A self-contained feature of the app, shown as a tab in the top menu. */
export interface UtilityModule {
  /** URL-safe identifier, used in the route: #/m/<id> */
  id: string;
  title: string;
  /** Emoji or short text shown in the tab. */
  icon: string;
  /**
   * Render the module into `container`. May return a cleanup function,
   * which is called when the user switches to another tab.
   */
  mount(container: HTMLElement): void | (() => void);
}
