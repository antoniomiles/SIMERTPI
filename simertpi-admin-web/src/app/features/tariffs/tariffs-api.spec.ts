import { HttpXsrfTokenExtractor, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { TariffsApi } from './tariffs-api';

describe('Administrative tariffs and schedules API', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'test-csrf-token' } },
        { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: true } },
      ],
    }),
  );

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('uses only same-origin administrative reads with the existing session cookie transport', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    api.listTariffs().subscribe();
    api.listSchedules().subscribe();
    api.listCalendarExceptions().subscribe();
    api.listBillingCalendars().subscribe();
    api.effective('2026-10-19T00:00:00Z').subscribe();
    api.effectiveOperationalCalendar('zone-1', '2026-10-28', '10:30').subscribe();
    for (const path of ['tariffs', 'schedules', 'calendar-exceptions', 'billing-day-calendars']) {
      const request = http.expectOne(`/api/v1/admin/parking/${path}`);
      expect(request.request.method).toBe('GET');
      expect(request.request.withCredentials).toBe(true);
      request.flush([]);
    }
    const effective = http.expectOne(
      (request) => request.url === '/api/v1/admin/parking/configuration/effective',
    );
    expect(effective.request.method).toBe('GET');
    expect(effective.request.params.get('at')).toBe('2026-10-19T00:00:00Z');
    expect(effective.request.withCredentials).toBe(true);
    effective.flush({
      at: '',
      generalTariff: null,
      generalBillingCalendarId: null,
      generalChargeable: true,
      generalBillingDaysConfigured: false,
      generalBillingNormativeReference: null,
      zones: [],
    });
    const operational = http.expectOne(
      (request) => request.url === '/api/v1/admin/parking/schedules/effective',
    );
    expect(operational.request.method).toBe('GET');
    expect(operational.request.params.get('zoneId')).toBe('zone-1');
    expect(operational.request.params.get('date')).toBe('2026-10-28');
    expect(operational.request.params.get('time')).toBe('10:30');
    expect(operational.request.withCredentials).toBe(true);
    operational.flush({ zoneId: 'zone-1', date: '2026-10-28', time: '10:30', operational: true, holiday: false, chargeable: true, windowStart: '08:00', windowEnd: '17:00', source: 'GENERAL', reasonCode: 'RULES_RESOLVED' });
  });

  it('posts only administrative versioned configuration after obtaining CSRF', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    api
      .createBillingCalendar({
        zoneId: null,
        validFrom: '2026-10-20',
        validTo: null,
        chargeableDays: [1, 2, 3, 4, 5],
        normativeReference: 'Ordenanza aprobada',
      })
      .subscribe();
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

  it('creates and updates operating schedules through the protected administrative API', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    const schedule = {
      zoneId: null,
      dayOfWeek: 1,
      startTime: '08:00:00',
      endTime: '17:00:00',
      validFrom: '2026-11-02',
      validTo: null,
      reason: 'Configuración aprobada',
    };

    api.createSchedule(schedule).subscribe();
    flushCsrf(http);
    const create = http.expectOne('/api/v1/admin/parking/schedules');
    expect(create.request.method).toBe('POST');
    expect(create.request.withCredentials).toBe(true);
    expect(create.request.body).toEqual(schedule);
    create.flush({ id: 'schedule-1' });

    api
      .updateSchedule('schedule-1', { ...schedule, expectedUpdatedAt: '2026-10-28T12:00:00Z' })
      .subscribe();
    flushCsrf(http);
    const update = http.expectOne('/api/v1/admin/parking/schedules/schedule-1');
    expect(update.request.method).toBe('PUT');
    expect(update.request.withCredentials).toBe(true);
    expect(update.request.body.expectedUpdatedAt).toBe('2026-10-28T12:00:00Z');
    update.flush({ id: 'schedule-1' });
  });

  it('uses a versioned future revision for effective schedules and keeps finalization date-only', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    const revision = {
      zoneId: 'zone-1',
      dayOfWeek: 2,
      startTime: '09:00:00',
      endTime: '18:00:00',
      validFrom: '2026-11-05',
      validTo: null,
      reason: 'Ajuste programado',
      expectedUpdatedAt: '2026-10-28T12:00:00Z',
    };

    api.reviseSchedule('schedule-2', revision).subscribe();
    flushCsrf(http);
    const revise = http.expectOne('/api/v1/admin/parking/schedules/schedule-2/revise');
    expect(revise.request.method).toBe('POST');
    expect(revise.request.withCredentials).toBe(true);
    expect(revise.request.body).toEqual(revision);
    revise.flush({ id: 'schedule-3' });

    api
      .endSchedule('schedule-3', '2026-11-06', '2026-10-28T12:00:00Z', 'Fin de período')
      .subscribe();
    flushCsrf(http);
    const end = http.expectOne('/api/v1/admin/parking/schedules/schedule-3/end');
    expect(end.request.method).toBe('POST');
    expect(end.request.withCredentials).toBe(true);
    expect(end.request.body).toEqual({
      effectiveFrom: '2026-11-06',
      expectedUpdatedAt: '2026-10-28T12:00:00Z',
      reason: 'Fin de período',
    });
    end.flush({ id: 'schedule-3' });
  });

  it('deactivates only through the audited schedule deactivation endpoint', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    const body = { expectedUpdatedAt: '2026-10-28T12:00:00Z', reason: 'Corrección solicitada' };
    api.deactivateSchedule('schedule-4', body).subscribe();
    flushCsrf(http);
    const request = http.expectOne('/api/v1/admin/parking/schedules/schedule-4/deactivate');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.body).toEqual(body);
    request.flush({ id: 'schedule-4', active: false });
  });

  it('creates, updates, and deactivates calendar exceptions with concurrency and reason fields', () => {
    const api = TestBed.inject(TariffsApi);
    const http = TestBed.inject(HttpTestingController);
    const holiday = {
      holidayDate: '2026-12-06',
      name: 'Día local',
      zoneId: null,
      holidayType: 'MUNICIPAL_EXCEPTION' as const,
      tariffed: false,
      startTime: null,
      endTime: null,
      normativeReference: 'Resolución aprobada',
      validFrom: '2026-12-06',
      validTo: null,
      reason: 'Registro autorizado',
    };
    api.createCalendarException(holiday).subscribe();
    flushCsrf(http);
    const create = http.expectOne('/api/v1/admin/parking/calendar-exceptions');
    expect(create.request.method).toBe('POST');
    expect(create.request.withCredentials).toBe(true);
    expect(create.request.body).toEqual(holiday);
    create.flush({ id: 'exception-1' });

    api
      .updateCalendarException('exception-1', {
        ...holiday,
        name: 'Día local actualizado',
        expectedUpdatedAt: '2026-10-28T12:00:00Z',
      })
      .subscribe();
    flushCsrf(http);
    const update = http.expectOne('/api/v1/admin/parking/calendar-exceptions/exception-1');
    expect(update.request.method).toBe('PUT');
    expect(update.request.withCredentials).toBe(true);
    expect(update.request.body.expectedUpdatedAt).toBe('2026-10-28T12:00:00Z');
    update.flush({ id: 'exception-1' });

    const deactivateBody = {
      expectedUpdatedAt: '2026-10-28T13:00:00Z',
      reason: 'Se canceló la resolución',
    };
    api.deactivateCalendarException('exception-1', deactivateBody).subscribe();
    flushCsrf(http);
    const deactivate = http.expectOne(
      '/api/v1/admin/parking/calendar-exceptions/exception-1/deactivate',
    );
    expect(deactivate.request.method).toBe('POST');
    expect(deactivate.request.withCredentials).toBe(true);
    expect(deactivate.request.body).toEqual(deactivateBody);
    deactivate.flush({ id: 'exception-1', active: false });
  });
});

function flushCsrf(http: HttpTestingController): void {
  const csrf = http.expectOne('/api/v1/admin/auth/csrf');
  expect(csrf.request.method).toBe('GET');
  expect(csrf.request.withCredentials).toBe(true);
  csrf.flush(null, { status: 204, statusText: 'No Content' });
}
