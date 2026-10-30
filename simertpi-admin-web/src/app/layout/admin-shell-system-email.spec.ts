import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { environment } from '../../environments/environment';
import { AdminSession } from '../core/auth/admin-session';
import { PORTAL_CONFIG } from '../core/config/portal-config';
import { SidebarPreference } from '../core/preferences/sidebar-preference';
import { AdminShell } from './admin-shell';

describe('AdminShell system email navigation visibility', () => {
  const identity = signal<any>(null);
  const preference = { collapsed: signal(false), toggle: vi.fn() };

  beforeEach(async () => {
    identity.set(null);
    preference.collapsed.set(false);
    preference.toggle.mockClear();
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi
        .fn()
        .mockReturnValue({ matches: false, addListener: vi.fn(), removeListener: vi.fn() }),
    });
    await TestBed.configureTestingModule({
      imports: [AdminShell],
      providers: [
        provideRouter([]),
        { provide: AdminSession, useValue: { identity } },
        { provide: PORTAL_CONFIG, useValue: environment },
        { provide: SidebarPreference, useValue: preference },
      ],
    }).compileComponents();
  });

  function emailNavigationVisible(shell: AdminShell) {
    return shell
      .visibleGroups()
      .flatMap((group) => group.items)
      .flatMap((item) => item.children ?? [])
      .some((child) => child.path === 'system/email');
  }

  it('shows email navigation only for IT_ADMIN and hides it for legacy, ordinary, and preview identities', () => {
    const fixture = TestBed.createComponent(AdminShell);
    const shell = fixture.componentInstance;

    identity.set({ enabled: true, roles: ['IT_ADMIN'], permissions: [] });
    expect(emailNavigationVisible(shell)).toBe(true);

    identity.set({ enabled: true, roles: ['SIMERTPI_ADMIN'] });
    expect(emailNavigationVisible(shell)).toBe(false);

    identity.set({ enabled: true, roles: ['SIMERTPI_ADMIN'], permissions: ['dashboard.view'] });
    expect(emailNavigationVisible(shell)).toBe(false);

    identity.set(null);
    expect(emailNavigationVisible(shell)).toBe(false);
  });
});
