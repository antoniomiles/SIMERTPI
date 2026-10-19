import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { ZonesApi } from './zones-api';

describe('Administrative parking catalog API', () => {
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

  it('uses same-origin administrative endpoints, session cookies and parent-scoped create bodies', () => {
    const api = TestBed.inject(ZonesApi);
    const http = TestBed.inject(HttpTestingController);
    api.listZones().subscribe();
    const zones = http.expectOne('/api/v1/admin/parking/zones');
    expect(zones.request.method).toBe('GET');
    expect(zones.request.withCredentials).toBe(true);
    zones.flush([]);

    api.createZone({ code: 'Z-01', name: 'Centro' }).subscribe();
    const zone = http.expectOne('/api/v1/admin/parking/zones');
    expect(zone.request.method).toBe('POST');
    expect(zone.request.body).toEqual({ code: 'Z-01', name: 'Centro' });
    expect(zone.request.withCredentials).toBe(true);
    zone.flush({ id: 'zone-1' });

    api.createStreet('zone-1', { code: 'S-01', name: 'Calle registrada' }).subscribe();
    const street = http.expectOne('/api/v1/admin/parking/zones/zone-1/streets');
    expect(street.request.body).toEqual({ code: 'S-01', name: 'Calle registrada' });
    expect(street.request.body).not.toHaveProperty('zoneId');
    street.flush({ id: 'street-1' });

    api.createSpace('street-1', { code: 'P-01', qrCode: 'QR-01', spaceNumber: '01' }).subscribe();
    const space = http.expectOne('/api/v1/admin/parking/streets/street-1/spaces');
    expect(space.request.body).toEqual({ code: 'P-01', qrCode: 'QR-01', spaceNumber: '01' });
    expect(space.request.body).not.toHaveProperty('streetId');
    space.flush({ id: 'space-1' });
  });
});
