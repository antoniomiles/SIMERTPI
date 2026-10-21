import { TestBed } from '@angular/core/testing';
import {
  HttpErrorResponse,
  HttpHeaders,
  HttpXsrfTokenExtractor,
  provideHttpClient,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiClient } from './api-client';
import { apiInterceptor, requestTimeoutMs, ADMIN_AUTH_TIMEOUT_MS, ADMIN_API_TIMEOUT_MS } from './api.interceptor';
import { normalizeError, PortalError } from './portal-error';
import { PORTAL_CONFIG } from '../config/portal-config';
import { environment } from '../../../environments/environment';
import { firstValueFrom } from 'rxjs';
describe('Fail-closed administrative transport', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([apiInterceptor]),
          withXsrfConfiguration({ cookieName: '__Host-SIMERTPI_ADMIN_XSRF', headerName: 'X-XSRF-TOKEN' }),
        ),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'test-csrf-token' } },
        { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: false } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('blocks requests to unimplemented APIs before they reach the network', async () => {
    await expect(
      firstValueFrom(TestBed.inject(ApiClient).get('admin/users')),
    ).rejects.toBeInstanceOf(PortalError);
    TestBed.inject(HttpTestingController).expectNone(() => true);
  });
  it('rejects external or citizen paths', () => {
    const api = TestBed.inject(ApiClient);
    for (const path of ['https://example.invalid', '../users', 'users', 'admin/../users'])
      expect(() => api.get(path)).toThrow();
  });
  it('never displays server response bodies or URLs', () => {
    const error = new HttpErrorResponse({
      status: 500,
      error: { message: 'private-server-detail' },
      url: 'https://private.invalid',
    });
    expect(normalizeError(error).message).not.toContain('private');
  });
  it('classifies authentication, authorization, network and timeout failures', () => {
    expect(normalizeError(new HttpErrorResponse({ status: 401 })).kind).toBe('unauthorized');
    expect(normalizeError(new HttpErrorResponse({ status: 403 })).kind).toBe('forbidden');
    expect(normalizeError(new HttpErrorResponse({ status: 403 })).message).toContain('seguridad o permisos');
    expect(normalizeError(new HttpErrorResponse({ status: 0 })).kind).toBe('network');
    expect(normalizeError(Object.assign(new Error(), { name: 'TimeoutError' })).kind).toBe(
      'timeout',
    );
  });
  it('allows cold-start time for auth routes and distinguishes gateway failures', () => {
    for (const path of ['/api/v1/admin/auth/csrf', '/api/v1/admin/auth/login', '/api/v1/admin/auth/me'])
      expect(requestTimeoutMs(path)).toBe(ADMIN_AUTH_TIMEOUT_MS);
    expect(ADMIN_AUTH_TIMEOUT_MS).toBeGreaterThan(120000);
    expect(requestTimeoutMs('/api/v1/admin/audit')).toBe(ADMIN_API_TIMEOUT_MS);
    const upstream502 = normalizeError(new HttpErrorResponse({ status: 502 }));
    expect(upstream502.kind).toBe('unavailable');
    expect(upstream502.message).toContain('HTTP 502');
    expect(
      normalizeError(
        new HttpErrorResponse({
          status: 502,
          headers: new HttpHeaders({ 'X-Admin-Proxy-Error': 'UPSTREAM_UNAVAILABLE' }),
        }),
      ).kind,
    ).toBe('network');
    const unavailable503 = normalizeError(new HttpErrorResponse({ status: 503 }));
    expect(unavailable503.kind).toBe('unavailable');
    expect(unavailable503.message).toContain('HTTP 503');
    expect(unavailable503.message).not.toBe(upstream502.message);
    const timeoutError = normalizeError(new HttpErrorResponse({ status: 504 }));
    expect(timeoutError.kind).toBe('timeout');
    expect(timeoutError.message).toContain('No se repitió');
  });

  it('obtains CSRF before each administrative mutation, including after session renewal', async () => {
    let xsrfToken = 'test-csrf-token';
    TestBed.overrideProvider(PORTAL_CONFIG, {
      useValue: { ...environment, administrativeApiEnabled: true },
    });
    TestBed.overrideProvider(HttpXsrfTokenExtractor, {
      useValue: { getToken: () => xsrfToken },
    });
    const api = TestBed.inject(ApiClient);
    const http = TestBed.inject(HttpTestingController);
    const first = firstValueFrom(api.post('admin/parking/tariffs', { code: 'fixture-one' }));
    const csrf = http.expectOne('/api/v1/admin/auth/csrf');
    expect(csrf.request.method).toBe('GET');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush(null, { status: 502, statusText: 'Bad Gateway' });
    const recoveredCsrf = await vi.waitFor(() => http.expectOne('/api/v1/admin/auth/csrf'));
    recoveredCsrf.flush(null, { status: 204, statusText: 'No Content' });
    const create = http.expectOne('/api/v1/admin/parking/tariffs');
    expect(create.request.method).toBe('POST');
    expect(create.request.withCredentials).toBe(true);
    expect(create.request.headers.get('X-XSRF-TOKEN')).toBe('test-csrf-token');
    create.flush({ id: 'fixture-one' });
    await expect(first).resolves.toEqual({ id: 'fixture-one' });

    xsrfToken = 'renewed-csrf-token';
    const renewed = firstValueFrom(api.post('admin/parking/tariffs', { code: 'fixture-two' }));
    http.expectOne('/api/v1/admin/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    const createAfterRenewal = http.expectOne('/api/v1/admin/parking/tariffs');
    expect(createAfterRenewal.request.headers.get('X-XSRF-TOKEN')).toBe('renewed-csrf-token');
    createAfterRenewal.flush({ id: 'fixture-two' });
    await expect(renewed).resolves.toEqual({ id: 'fixture-two' });
  });

  it('does not send an administrative mutation when the CSRF preflight fails', async () => {
    TestBed.overrideProvider(PORTAL_CONFIG, {
      useValue: { ...environment, administrativeApiEnabled: true },
    });
    const api = TestBed.inject(ApiClient);
    const http = TestBed.inject(HttpTestingController);
    const pending = firstValueFrom(api.post('admin/parking/tariffs', { code: 'fixture-blocked' }));
    http.expectOne('/api/v1/admin/auth/csrf').flush(null, { status: 403, statusText: 'Forbidden' });
    await expect(pending).rejects.toMatchObject({ kind: 'forbidden' });
    http.expectNone('/api/v1/admin/parking/tariffs');
  });

  it('bounds CSRF recovery to three safe GET attempts and never sends the write on continued gateway errors', async () => {
    TestBed.overrideProvider(PORTAL_CONFIG, {
      useValue: { ...environment, administrativeApiEnabled: true },
    });
    const api = TestBed.inject(ApiClient);
    const http = TestBed.inject(HttpTestingController);
    const pending = firstValueFrom(api.post('admin/parking/tariffs', { code: 'fixture-unavailable' }));
    for (let attempt = 0; attempt < 3; attempt++) {
      const csrf = await vi.waitFor(() => http.expectOne('/api/v1/admin/auth/csrf'));
      csrf.flush(null, { status: 502, statusText: 'Bad Gateway' });
    }
    await expect(pending).rejects.toMatchObject({ kind: 'unavailable' });
    http.expectNone('/api/v1/admin/parking/tariffs');
  });
});
