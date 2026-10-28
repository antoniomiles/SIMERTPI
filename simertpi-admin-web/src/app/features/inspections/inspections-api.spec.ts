import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { InspectionsApi } from './inspections-api';

describe('Administrative inspections API', () => {
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

  it('sends inspection filters through the same-origin administrative client', () => {
    TestBed.inject(InspectionsApi)
      .search({
        page: 1,
        size: 25,
        plate: 'ABC1234',
        result: 'EXPIRED',
        zone: 'Z-1',
        from: '2026-10-01',
        to: '2026-10-27',
      })
      .subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      (item) => item.url === '/api/v1/admin/inspections',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.get('result')).toBe('EXPIRED');
    expect(request.request.params.get('plate')).toBe('ABC1234');
    request.flush({
      items: [],
      page: 1,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: false,
      last: true,
    });
  });

  it('queries a detail using the bounded admin route', () => {
    const id = '11111111-1111-4111-8111-111111111111';
    TestBed.inject(InspectionsApi).detail(id).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      `/api/v1/admin/inspections/${id}`,
    );
    expect(request.request.method).toBe('GET');
    request.flush({});
  });
});
