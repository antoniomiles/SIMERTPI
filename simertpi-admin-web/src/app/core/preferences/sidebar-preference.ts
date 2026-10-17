import { DOCUMENT, inject, Injectable, InjectionToken, signal } from '@angular/core';
export const SIDEBAR_PREFERENCE_KEY = 'simertpi.admin.ui.sidebar';
export const VISUAL_PREFERENCE_STORAGE = new InjectionToken<Pick<
  Storage,
  'getItem' | 'setItem'
> | null>('VISUAL_PREFERENCE_STORAGE', {
  factory: () => {
    try {
      return inject(DOCUMENT).defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  },
});
/** Only a non-sensitive visual enum is persisted. No account or session information. */
@Injectable({ providedIn: 'root' })
export class SidebarPreference {
  private readonly storage = inject(VISUAL_PREFERENCE_STORAGE);
  private readonly state = signal(this.read());
  readonly collapsed = this.state.asReadonly();
  toggle() {
    const next = !this.state();
    this.state.set(next);
    try {
      this.storage?.setItem(SIDEBAR_PREFERENCE_KEY, next ? 'collapsed' : 'expanded');
    } catch {
      /* Preference remains usable in memory. */
    }
  }
  private read(): boolean {
    try {
      return this.storage?.getItem(SIDEBAR_PREFERENCE_KEY) === 'collapsed';
    } catch {
      return false;
    }
  }
}
