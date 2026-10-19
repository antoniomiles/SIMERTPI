import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { PaymentsApi } from './payments-api';

describe('Administrative payments API', () => {
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

  it('uses the protected same-origin route and preserves exact server filters', () => {
    TestBed.inject(PaymentsApi)
      .search({
        page: 2,
        size: 25,
        status: 'APPROVED',
        q: 'payment-reference',
        from: '2026-10-01',
        to: '2026-10-18',
      })
      .subscribe();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (item) => item.url === '/api/v1/admin/payments',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.params.keys().sort()).toEqual([
      'from', 'page', 'q', 'size', 'status', 'to',
    ]);
    expect(request.request.params.get('status')).toBe('APPROVED');
    request.flush({
      items: [],
      summaryByCurrency: [],
      page: 2,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: false,
      last: true,
    });
  });
});
