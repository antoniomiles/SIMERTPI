import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { AuditApi } from './audit-api';

describe('Administrative audit API', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: true } },
      ],
    }),
  );

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('uses the same-origin administrative session route and bounded filter parameters', () => {
    TestBed.inject(AuditApi)
      .search({
        action: 'LOGIN',
        resourceType: 'ADMIN_SESSION',
        result: 'FAILURE',
        correlationId: 'trace-1',
        from: '2026-10-18T00:00:00.000Z',
        to: '2026-10-18T12:00:00.000Z',
        offset: 50,
        limit: 51,
      })
      .subscribe();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (item) => item.url === '/api/v1/admin/audit',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.params.get('limit')).toBe('51');
    expect(request.request.params.get('offset')).toBe('50');
    expect(request.request.params.get('action')).toBe('LOGIN');
    expect(request.request.params.get('resourceType')).toBe('ADMIN_SESSION');
    expect(request.request.params.get('result')).toBe('FAILURE');
    expect(request.request.params.get('correlationId')).toBe('trace-1');
    request.flush([]);
  });

  it('loads the allowlisted configuration history through the protected admin route', () => {
    TestBed.inject(AuditApi).configurationHistory(26, 50).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      (item) => item.url === '/api/v1/admin/configuration-history',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.params.get('limit')).toBe('26');
    expect(request.request.params.get('offset')).toBe('50');
    request.flush([]);
  });
});
