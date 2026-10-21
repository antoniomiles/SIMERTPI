import { TestBed } from '@angular/core/testing';
import {
  HttpXsrfTokenExtractor,
  provideHttpClient,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AdminSession } from './admin-session';
import { AdminIdentity, safeReturnUrl } from './admin-session-state';
import { PORTAL_CONFIG } from '../config/portal-config';
import { apiInterceptor } from '../http/api.interceptor';

describe('Real administrative session client', () => {
  let session: AdminSession;
  let http: HttpTestingController;
  function identity(seconds = 60): AdminIdentity {
    return {
      userId: 'isolated-fixture',
      username: 'fixture.admin',
      roles: ['SIMERTPI_ADMIN'],
      enabled: true,
      sessionExpiresAt: new Date(Date.now() + seconds * 1000).toISOString(),
      absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
    };
  }
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(
          withInterceptors([apiInterceptor]),
          withXsrfConfiguration({ cookieName: '__Host-SIMERTPI_ADMIN_XSRF', headerName: 'X-XSRF-TOKEN' }),
        ),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'test-csrf-token' } },
        {
          provide: PORTAL_CONFIG,
          useValue: {
            name: 'local',
            apiBaseUrl: '/api/v1',
            administrativeApiEnabled: true,
            localPreview: false,
          },
        },
      ],
    });
    session = TestBed.inject(AdminSession);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });
  function succeed() {
    session.signIn('fixture.admin', 'unit-test-only').subscribe();
    http.expectOne('/api/v1/admin/auth/csrf').flush(null);
    http.expectOne('/api/v1/admin/auth/login').flush(identity());
  }
  it('requests CSRF before login and accepts only a server identity', () => {
    session.signIn('fixture.admin', 'unit-test-only').subscribe();
    expect(session.authenticated()).toBe(false);
    http.expectNone('/api/v1/admin/auth/login');
    http.expectOne('/api/v1/admin/auth/csrf').flush(null);
    const login = http.expectOne('/api/v1/admin/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.headers.get('X-XSRF-TOKEN')).toBe('test-csrf-token');
    login.flush(identity());
    expect(session.authenticated()).toBe(true);
  });
  it('recovers login after a transient CSRF gateway response without repeating the login POST', async () => {
    session.restore().subscribe((ok) => expect(ok).toBe(false));
    http.expectOne('/api/v1/admin/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    session.signIn('fixture.admin', 'unit-test-only').subscribe();
    http.expectOne('/api/v1/admin/auth/csrf').flush(null, { status: 502, statusText: 'Bad Gateway' });
    (await vi.waitFor(() => http.expectOne('/api/v1/admin/auth/csrf')))
      .flush(null, { status: 204, statusText: 'No Content' });
    const login = http.expectOne('/api/v1/admin/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.headers.get('X-XSRF-TOKEN')).toBe('test-csrf-token');
    login.flush(identity());
    expect(session.authenticated()).toBe(true);
    http.expectNone('/api/v1/admin/auth/login');
  });
  it('does not retry login POST and allows a later user-initiated attempt', () => {
    session.signIn('fixture.admin', 'unit-test-only').subscribe({ error: () => {} });
    http.expectOne('/api/v1/admin/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/admin/auth/login').flush(null, { status: 503, statusText: 'Unavailable' });
    http.expectNone('/api/v1/admin/auth/login');
    expect(session.authenticated()).toBe(false);

    session.signIn('fixture.admin', 'unit-test-only').subscribe();
    http.expectOne('/api/v1/admin/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/admin/auth/login').flush(identity());
    expect(session.authenticated()).toBe(true);
  });
  it('restores a cookie session through me, without a token response', () => {
    session.restore().subscribe((ok) => expect(ok).toBe(true));
    http.expectOne('/api/v1/admin/auth/me').flush(identity());
    expect(session.identity()?.username).toBe('fixture.admin');
  });
  it('does not accept citizen or expired identities', () => {
    session.restore().subscribe((ok) => expect(ok).toBe(false));
    http.expectOne('/api/v1/admin/auth/me').flush({ ...identity(), roles: ['CITIZEN'] });
    expect(session.authenticated()).toBe(false);
    session.restore().subscribe((ok) => expect(ok).toBe(false));
    http.expectOne('/api/v1/admin/auth/me').flush(identity(-1));
    expect(session.authenticated()).toBe(false);
  });
  it('clears state only after server logout succeeds', () => {
    succeed();
    session.signOut().subscribe();
    http.expectOne('/api/v1/admin/auth/csrf').flush(null);
    expect(session.authenticated()).toBe(true);
    http.expectOne('/api/v1/admin/auth/logout').flush(null);
    expect(session.authenticated()).toBe(false);
  });
  it('does not claim logout succeeded on a network failure', () => {
    succeed();
    session.signOut().subscribe({ error: () => {} });
    http.expectOne('/api/v1/admin/auth/csrf').flush(null);
    http
      .expectOne('/api/v1/admin/auth/logout')
      .flush('unavailable', { status: 503, statusText: 'Unavailable' });
    expect(session.authenticated()).toBe(true);
  });
  it('expires the frontend view and safely returns to login', () => {
    vi.useFakeTimers();
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/admin/dashboard');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    session.signIn('fixture.admin', 'unit-test-only').subscribe();
    http.expectOne('/api/v1/admin/auth/csrf').flush(null);
    http.expectOne('/api/v1/admin/auth/login').flush(identity(1));
    vi.advanceTimersByTime(1001);
    expect(session.authenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'expired' } });
  });
  it('allows only known internal return destinations', () => {
    expect(safeReturnUrl('/admin/personal')).toBe('/admin/personal');
    expect(safeReturnUrl('/admin/access')).toBe('/admin/access');
    expect(safeReturnUrl('/admin/map')).toBe('/admin/map');
    expect(safeReturnUrl('/admin/payments')).toBe('/admin/payments');
    expect(safeReturnUrl('/admin/reports')).toBe('/admin/reports');
    expect(safeReturnUrl('/admin/tariffs')).toBe('/admin/tariffs');
    expect(safeReturnUrl('/admin/tariffs/billing-days')).toBe('/admin/tariffs/billing-days');
    expect(safeReturnUrl('/admin/tariffs/holidays')).toBe('/admin/tariffs/holidays');
    expect(safeReturnUrl('/admin/tariffs/hours')).toBe('/admin/tariffs/hours');
    expect(safeReturnUrl('/admin/tariffs/history')).toBe('/admin/tariffs/history');
    for (const url of [
      null,
      'https://untrusted.invalid',
      '//untrusted.invalid',
      '/admin/personal?returnUrl=//untrusted.invalid',
      '%2F%2Funtrusted.invalid',
      '/admin/../login',
      '/admin/tariffs/unknown',
      '/admin/tariffs//history',
      '/admin/tariffs\\history',
      '/admin/tariffs/history#outside',
    ])
      expect(safeReturnUrl(url)).toBe('/admin/dashboard');
  });
});
