import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { TariffsApi } from './tariffs-api';

describe('Administrative tariffs and schedules API', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([apiInterceptor])),
      provideHttpClientTesting(),
      { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: true } },
    ],
  }));

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('uses only same-origin administrative reads with the existing session cookie transport', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    api.listTariffs().subscribe();
    api.listSchedules().subscribe();
    api.listCalendarExceptions().subscribe();
    api.listBillingCalendars().subscribe();
    api.effective('2026-10-19T00:00:00Z').subscribe();
    for (const path of ['tariffs', 'schedules', 'calendar-exceptions', 'billing-day-calendars']) {
      const request = http.expectOne(`/api/v1/admin/parking/${path}`);
      expect(request.request.method).toBe('GET');
      expect(request.request.withCredentials).toBe(true);
      request.flush([]);
    }
    const effective = http.expectOne((request) => request.url === '/api/v1/admin/parking/configuration/effective');
    expect(effective.request.method).toBe('GET');
    expect(effective.request.params.get('at')).toBe('2026-10-19T00:00:00Z');
    expect(effective.request.withCredentials).toBe(true);
    effective.flush({ at: '', generalTariff: null, generalBillingCalendarId: null, generalChargeable: true,
      generalBillingDaysConfigured: false, generalBillingNormativeReference: null, zones: [] });
  });

  it('posts only administrative versioned configuration after obtaining CSRF', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    api.createBillingCalendar({ zoneId: null, validFrom: '2026-10-20', validTo: null,
      chargeableDays: [1, 2, 3, 4, 5], normativeReference: 'Ordenanza aprobada' }).subscribe();
    const csrf = http.expectOne('/api/v1/admin/auth/csrf');
    expect(csrf.request.method).toBe('GET');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush(null, { status: 204, statusText: 'No Content' });
    const request = http.expectOne('/api/v1/admin/parking/billing-day-calendars');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.body.chargeableDays).toEqual([1, 2, 3, 4, 5]);
    request.flush('calendar-id');
  });
});
