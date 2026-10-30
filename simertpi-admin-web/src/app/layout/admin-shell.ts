import { PORTAL_CONFIG } from '../core/config/portal-config';
import { AdminSession } from '../core/auth/admin-session';
import { normalizeError } from '../core/http/portal-error';
import {
  Component,
  afterNextRender,
  Injector,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import {
  ActivatedRoute,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Brand } from '../shared/ui/brand';
import { Icon } from '../shared/ui/icon';
import { SidebarPreference } from '../core/preferences/sidebar-preference';
import { NAVIGATION } from './navigation';
interface NavigationTooltip {
  label: string;
  key: string;
  top: number;
  left: number;
}
@Component({
  selector: 'app-admin-shell',
  imports: [Brand, Icon, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './admin-shell.html',
})
export class AdminShell {
  private readonly injector = inject(Injector);
  readonly preference = inject(SidebarPreference);
  readonly session = inject(AdminSession);
  readonly administrativeRoleLabel = () => {
    const roles = this.session.identity()?.roles ?? [];
    if (roles.includes('IT_ADMIN')) return 'Superadministrador';
    if (roles.includes('SIMERTPI_ADMIN')) return 'Administrador';
    return 'Usuario administrativo';
  };
  readonly environmentLabel = { local: 'LOCAL', dev: 'DEV', production: 'PROD' }[
    inject(PORTAL_CONFIG).name
  ];
  readonly logoutError = signal('');
  readonly signingOut = signal(false);
  private readonly router = inject(Router);
  readonly groups = NAVIGATION;
  readonly visibleGroups = () => {
    const identity = this.session.identity();
    const permissions = identity?.permissions;
    const roles = identity?.roles ?? [];
    return NAVIGATION.map((group) => ({
      ...group,
      items: group.items.flatMap((item) => {
        if (item.children) {
          const children = item.children.filter((child) => {
            if (child.path === 'system/email' && !roles.includes('IT_ADMIN')) return false;
            return permissions === undefined || this.canView(child.path, permissions, roles);
          });
          return children.length ? [{ ...item, children }] : [];
        }
        if (item.path)
          return permissions === undefined || this.canView(item.path, permissions, roles)
            ? [item]
            : [];
        return [item];
      }),
    })).filter((group) => group.items.length > 0);
  };
  readonly opened = signal(false);
  readonly tariffsMenuExpanded = signal(false);
  readonly zonesMenuExpanded = signal(false);
  readonly systemMenuExpanded = signal(false);
  readonly compact = signal(window.matchMedia('(max-width: 960px)').matches);
  readonly tooltip = signal<NavigationTooltip | null>(null);
  readonly preview = inject(ActivatedRoute).snapshot.data['preview'] === true;
  readonly base = this.preview ? '/preview' : '/admin';
  readonly menuButton = viewChild<ElementRef<HTMLButtonElement>>('menuButton');
  private hideTimer: ReturnType<typeof setTimeout> | undefined;
  constructor() {
    this.tariffsMenuExpanded.set(!this.isCollapsed() && this.isTariffsActive());
    this.zonesMenuExpanded.set(!this.isCollapsed() && this.isZonesActive());
    this.systemMenuExpanded.set(!this.isCollapsed() && this.isSystemEmailActive());
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => {
      if (this.hideTimer) clearTimeout(this.hideTimer);
    });
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(destroy),
      )
      .subscribe(() => {
        this.opened.set(false);
        this.tariffsMenuExpanded.set(!this.isCollapsed() && this.isTariffsActive());
        this.zonesMenuExpanded.set(!this.isCollapsed() && this.isZonesActive());
        this.systemMenuExpanded.set(!this.isCollapsed() && this.isSystemEmailActive());
        this.hideTooltip();
        afterNextRender(() => document.getElementById('main-content')?.focus(), {
          injector: this.injector,
        });
      });
  }
  signOut() {
    if (this.signingOut()) return;
    this.signingOut.set(true);
    this.logoutError.set('');
    this.session.signOut().subscribe({
      next: () => {
        this.signingOut.set(false);
        void this.router.navigate(['/login']);
      },
      error: (error: unknown) => {
        this.signingOut.set(false);
        this.logoutError.set(normalizeError(error).message);
      },
    });
  }
  isCollapsed() {
    return !this.compact() && this.preference.collapsed();
  }
  skipToMain() {
    document.getElementById('main-content')?.focus();
  }
  toggleMenu() {
    this.hideTooltip();
    if (!this.compact()) {
      this.preference.toggle();
      this.tariffsMenuExpanded.set(!this.isCollapsed() && this.isTariffsActive());
      this.zonesMenuExpanded.set(!this.isCollapsed() && this.isZonesActive());
      this.systemMenuExpanded.set(!this.isCollapsed() && this.isSystemEmailActive());
      return;
    }
    this.opened.update((value) => !value);
    if (this.opened())
      afterNextRender(() => document.querySelector<HTMLElement>('.sidebar nav a')?.focus(), {
        injector: this.injector,
      });
  }
  @HostListener('window:resize') resize() {
    this.compact.set(window.matchMedia('(max-width: 960px)').matches);
    this.hideTooltip();
    if (!this.compact()) this.opened.set(false);
  }
  closeMenu() {
    this.opened.set(false);
    this.hideTooltip();
    afterNextRender(() => this.menuButton()?.nativeElement.focus(), { injector: this.injector });
  }
  selectedRoute() {
    this.hideTooltip();
    if (this.compact()) {
      this.opened.set(false);
      afterNextRender(() => document.getElementById('main-content')?.focus(), {
        injector: this.injector,
      });
    }
  }
  toggleTariffsMenu() {
    if (this.isCollapsed()) {
      this.preference.toggle();
      this.tariffsMenuExpanded.set(true);
      return;
    }
    const expanding = !this.tariffsMenuExpanded();
    this.tariffsMenuExpanded.set(expanding);
  }
  isTariffsActive() {
    return this.router.url.startsWith(this.base + '/tariffs');
  }
  isSystemEmailActive() {
    return this.router.url.startsWith(this.base + '/system/email');
  }
  isZonesActive() {
    return ['/zones', '/streets', '/spaces'].some((path) =>
      this.router.url.startsWith(this.base + path),
    );
  }
  isNavigationActive(label: string) {
    return label === 'Tarifas y horarios'
      ? this.isTariffsActive()
      : label === 'Zonas y espacios'
        ? this.isZonesActive()
        : label === 'Configuración del sistema'
          ? this.isSystemEmailActive()
          : false;
  }
  isNavigationExpanded(label: string) {
    return label === 'Tarifas y horarios'
      ? this.tariffsMenuExpanded()
      : label === 'Zonas y espacios'
        ? this.zonesMenuExpanded()
        : label === 'Configuración del sistema'
          ? this.systemMenuExpanded()
          : false;
  }
  navigationSubmenuId(label: string) {
    return label === 'Tarifas y horarios'
      ? 'tariffs-navigation'
      : label === 'Zonas y espacios'
        ? 'zones-navigation'
        : 'system-navigation';
  }
  toggleNavigationMenu(label: string) {
    if (label === 'Zonas y espacios') this.toggleZonesMenu();
    else if (label === 'Configuración del sistema') this.toggleSystemMenu();
    else this.toggleTariffsMenu();
  }
  private toggleZonesMenu() {
    if (this.isCollapsed()) {
      this.preference.toggle();
      this.zonesMenuExpanded.set(true);
      return;
    }
    this.zonesMenuExpanded.update((value) => !value);
  }
  private toggleSystemMenu() {
    if (this.isCollapsed()) {
      this.preference.toggle();
      this.systemMenuExpanded.set(true);
      return;
    }
    this.systemMenuExpanded.update((value) => !value);
  }
  childRoute(path: string) {
    return [this.base, ...path.split('/')];
  }
  private canView(path: string, permissions: readonly string[], roles: readonly string[]) {
    if (roles.includes('IT_ADMIN')) return true;
    if (path === 'system/email') return false;
    const key = path.startsWith('tariffs')
      ? 'tariffs.view'
      : path === 'dashboard'
        ? 'dashboard.view'
        : path === 'access'
          ? 'users.view'
          : path === 'personal'
            ? 'users.view'
            : path === 'audit'
              ? 'audit.view'
              : path === 'map' || path === 'spaces'
                ? 'spaces.view'
                : path === 'zones'
                  ? 'zones.view'
                  : path === 'streets'
                    ? 'streets.view'
                    : path === 'parking-sessions'
                      ? 'sessions.view'
                      : path === 'inspections'
                        ? 'inspections.view'
                        : path === 'violations'
                          ? 'violations.view'
                          : path === 'payments'
                            ? 'payments.view'
                            : path === 'reports'
                              ? 'reports.view'
                              : undefined;
    return !key || permissions.includes(key);
  }
  showTooltip(event: Event, key: string, pending = false) {
    if (!this.isCollapsed()) return;
    this.keepTooltip();
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    this.tooltip.set({
      key,
      label: pending ? `${key} · Pendiente` : key,
      top: Math.max(8, Math.min(window.innerHeight - 70, box.top + box.height / 2 - 20)),
      left: box.right + 12,
    });
  }
  keepTooltip() {
    if (this.hideTimer) clearTimeout(this.hideTimer);
  }
  scheduleHide(event?: Event) {
    if (event?.type === 'mouseleave' && event.currentTarget === document.activeElement) return;
    this.keepTooltip();
    this.hideTimer = setTimeout(() => {
      if (document.activeElement?.getAttribute('aria-describedby') !== 'navigation-tooltip')
        this.tooltip.set(null);
    }, 160);
  }
  hideTooltip() {
    this.keepTooltip();
    this.tooltip.set(null);
  }
  @HostListener('document:keydown', ['$event']) keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      if (this.tooltip()) this.hideTooltip();
      else if (this.opened()) this.closeMenu();
    }
    if (event.key !== 'Tab' || !this.compact() || !this.opened()) return;
    const elements = Array.from(
      document.querySelectorAll<HTMLElement>(
        '.sidebar a[href], .sidebar button, .sidebar [tabindex="0"]',
      ),
    );
    const first = elements[0];
    const last = elements.at(-1);
    if (!first || !last) return;
    if (!document.getElementById('sidebar')?.contains(document.activeElement)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
