import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { ViolationsApi } from './violations-api';

describe('Administrative violations API', () => {
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

  it('sends bounded filters through the existing same-origin admin client', () => {
    TestBed.inject(ViolationsApi)
      .search({
        page: 2,
        size: 25,
        status: 'OPEN',
        plate: 'ABC-123',
        zone: 'Z-1',
        q: 'space 4',
        from: '2026-10-01',
        to: '2026-10-19',
      })
      .subscribe();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (item) => item.url === '/api/v1/admin/violations',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('25');
    expect(request.request.params.get('status')).toBe('OPEN');
    expect(request.request.params.get('plate')).toBe('ABC-123');
    expect(request.request.params.get('zone')).toBe('Z-1');
    expect(request.request.params.get('q')).toBe('space 4');
    expect(request.request.params.get('from')).toBe('2026-10-01');
    expect(request.request.params.get('to')).toBe('2026-10-19');
    request.flush({
      items: [],
      page: 2,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: false,
      last: true,
    });
  });

  it('fetches detail by encoded identifier through the same-origin client', () => {
    const id = 'b908ed10-601c-4ab6-931d-d8745f67f72b';
    TestBed.inject(ViolationsApi).detail(id).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      `/api/v1/admin/violations/${id}`,
    );
    expect(request.request.method).toBe('GET');
    request.flush({});
  });

  it('loads evidence metadata through the read-only admin endpoint', () => {
    TestBed.inject(ViolationsApi).evidence('violation-001').subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/violations/violation-001/evidence',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    request.flush([]);
  });

  it('downloads evidence only through the bounded admin content endpoint', () => {
    TestBed.inject(ViolationsApi).downloadEvidence('evidence-001').subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/evidence/evidence-001/content',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['fixture'], { type: 'image/jpeg' }));
  });
});
