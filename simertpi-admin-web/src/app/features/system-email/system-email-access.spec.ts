import { Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import { AdminSession } from '../../core/auth/admin-session';
import { superadminGuard } from '../../core/auth/access.guards';

describe('System email route authorization', () => {
  const identity = signal<any>(null);

  beforeEach(() => {
    identity.set(null);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AdminSession, useValue: { identity } }],
    });
  });

  it('attaches the Superadministrator guard to the actual administrative email route', () => {
    const adminRoute = routes.find((route) => route.path === 'admin');
    const emailRoute = adminRoute?.children?.find((route) => route.path === 'system/email');
    expect(emailRoute?.canActivate).toContain(superadminGuard);
    expect(emailRoute?.canDeactivate).toHaveLength(1);
  });

  it('allows direct route access for IT_ADMIN', () => {
    identity.set({ enabled: true, roles: ['IT_ADMIN'] });
    const result = TestBed.runInInjectionContext(() => superadminGuard({} as never, {} as never));
    expect(result).toBe(true);
  });

  it('rejects direct route access for SIMERTPI_ADMIN and redirects to the dashboard', () => {
    identity.set({ enabled: true, roles: ['SIMERTPI_ADMIN'] });
    const result = TestBed.runInInjectionContext(() => superadminGuard({} as never, {} as never));
    expect(result).toEqual(expect.anything());
    expect(TestBed.inject(Router).serializeUrl(result as any)).toBe('/admin/dashboard');
  });
});
