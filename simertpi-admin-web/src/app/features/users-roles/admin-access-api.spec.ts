import { HttpXsrfTokenExtractor, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { AdminAccessApi } from './admin-access-api';

describe('Administrative access API', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([apiInterceptor])),
      provideHttpClientTesting(),
      { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'test-csrf-token' } },
      { provide: PORTAL_CONFIG, useValue: environment },
    ],
  }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function completeCsrfPreflight() {
    const http = TestBed.inject(HttpTestingController);
    const csrf = http.expectOne('/api/v1/admin/auth/csrf');
    expect(csrf.request.method).toBe('GET');
    csrf.flush(null, { status: 204, statusText: 'No Content' });
  }

  it('queries the account directory with bounded server filters using GET only', () => {
    TestBed.inject(AdminAccessApi).users('  name  ', 'INSPECTOR', false, 3).subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne((request) => request.url === '/api/v1/admin/access/users');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBe(true);
    expect(Object.fromEntries(req.request.params.keys().map((key) => [key, req.request.params.get(key)]))).toEqual({
      page: '3', size: '25', q: 'name', role: 'INSPECTOR', enabled: 'false',
    });
    req.flush({ content: [], page: 3, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('queries the catalog through GET without requesting or sending permission mutations', () => {
    TestBed.inject(AdminAccessApi).roles().subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/roles');
    expect(req.request.method).toBe('GET');
    expect(req.request.body).toBeNull();
    req.flush([]);
  });

  it('loads role capabilities from the explicit read-only permission endpoint', () => {
    TestBed.inject(AdminAccessApi).permissions().subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/permissions');
    expect(req.request.method).toBe('GET');
    expect(req.request.body).toBeNull();
    req.flush({ roles: [{ roleCode: 'INSPECTOR', roleName: 'Inspector', permissions: ['inspection:read'] }] });
  });

  it('creates an administrative account with only the selected existing roles and reason', () => {
    TestBed.inject(AdminAccessApi).createAccount({
      username: 'ops.user', email: 'ops@example.invalid', firstName: 'Ops', lastName: 'User',
      phone: null, roles: ['INSPECTOR'], reason: 'Alta autorizada',
    }).subscribe();
    completeCsrfPreflight();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/users');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.roles).toEqual(['INSPECTOR']);
    expect(req.request.body.reason).toBe('Alta autorizada');
    expect(req.request.withCredentials).toBe(true);
    req.flush({ activationToken: 'one-time', activationExpiresAt: '2026-10-30T00:00:00Z' });
  });

  it('assigns or revokes roles only through the versioned protected endpoint', () => {
    TestBed.inject(AdminAccessApi).assignRoles('user-1', {
      roles: ['INSPECTOR'], reason: 'Asignación autorizada', updatedAt: '2026-10-28T10:00:00Z',
    }).subscribe();
    completeCsrfPreflight();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/users/user-1/roles');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      roles: ['INSPECTOR'], reason: 'Asignación autorizada', updatedAt: '2026-10-28T10:00:00Z',
    });
    req.flush({});
  });

  it('updates account profile with the optimistic version and reason', () => {
    TestBed.inject(AdminAccessApi).updateAccount('user-1', {
      email: 'person@example.invalid', firstName: 'Ada', lastName: 'Lovelace', phone: null,
      reason: 'Corrección de datos', updatedAt: '2026-10-28T10:00:00Z',
    }).subscribe();
    completeCsrfPreflight();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/users/user-1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.body).toMatchObject({ reason: 'Corrección de datos', updatedAt: '2026-10-28T10:00:00Z' });
    req.flush({});
  });

  it('changes account status with a mandatory reason and current version', () => {
    TestBed.inject(AdminAccessApi).updateStatus('user-1', {
      enabled: false, reason: 'Fin de asignación', updatedAt: '2026-10-28T10:00:00Z',
    }).subscribe();
    completeCsrfPreflight();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/users/user-1/status');
    expect(req.request.method).toBe('PUT');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.body).toEqual({ enabled: false, reason: 'Fin de asignación', updatedAt: '2026-10-28T10:00:00Z' });
    req.flush({});
  });

  it('creates and reissues inspector activation only through the existing protected endpoints', () => {
    const api = TestBed.inject(AdminAccessApi);
    api.createInspector({ username: 'inspector.1', email: 'inspector@example.invalid', firstName: 'Inspector', lastName: 'Uno', phone: null }).subscribe();
    completeCsrfPreflight();
    const create = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/inspectors');
    expect(create.request.method).toBe('POST');
    expect(create.request.withCredentials).toBe(true);
    create.flush({ activationToken: 'one-time' });
    api.reissueInspectorActivation('user-1').subscribe();
    completeCsrfPreflight();
    const reissue = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/inspectors/user-1/activation');
    expect(reissue.request.method).toBe('POST');
    expect(reissue.request.withCredentials).toBe(true);
    reissue.flush({ activationToken: 'one-time-again', expiresAt: '2026-10-30T00:00:00Z' });
  });

  it('reissues a pending administrative invitation with reason and optimistic version', () => {
    TestBed.inject(AdminAccessApi).reissueAdminActivation('admin-1', {
      reason: 'Enlace vencido', updatedAt: '2026-10-28T10:00:00Z',
    }).subscribe();
    completeCsrfPreflight();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/users/admin-1/activation');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Enlace vencido', updatedAt: '2026-10-28T10:00:00Z' });
    expect(req.request.withCredentials).toBe(true);
    req.flush({ activationToken: 'one-time', activationExpiresAt: '2026-10-30T10:00:00Z' });
  });
});
