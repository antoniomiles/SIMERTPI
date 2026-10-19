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
    for (const path of ['tariffs', 'schedules', 'calendar-exceptions']) {
      const request = http.expectOne(`/api/v1/admin/parking/${path}`);
      expect(request.request.method).toBe('GET');
      expect(request.request.withCredentials).toBe(true);
      request.flush([]);
    }
  });
});
