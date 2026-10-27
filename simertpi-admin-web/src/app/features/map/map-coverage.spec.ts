import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ZonesApi } from '../zones/zones-api';
import { MapCoverage } from './map-coverage';

const zone = {
  id: 'zone-fixture',
  code: 'Z-FIX',
  name: 'Zona de prueba',
  description: null,
  active: true,
  createdAt: '2026-10-19T00:00:00Z',
  updatedAt: '2026-10-19T00:00:00Z',
};
const street = {
  id: 'street-fixture',
  zoneId: zone.id,
  code: 'S-FIX',
  name: 'Calle de prueba',
  description: null,
  active: true,
  createdAt: '2026-10-19T00:00:00Z',
  updatedAt: '2026-10-19T00:00:00Z',
};
const spaces = [
  {
    id: 'space-pair',
    streetId: street.id,
    code: 'P-PAIR',
    qrCode: 'QR-TEST-PAIR',
    spaceNumber: '01',
    latitude: 1.25,
    longitude: 2.5,
    spaceType: 'STANDARD',
    active: true,
    createdAt: '2026-10-19T00:00:00Z',
    updatedAt: '2026-10-19T00:00:00Z',
  },
  {
    id: 'space-missing',
    streetId: street.id,
    code: 'P-MISSING',
    qrCode: 'QR-TEST-MISSING',
    spaceNumber: '02',
    latitude: null,
    longitude: null,
    spaceType: 'STANDARD',
    active: false,
    createdAt: '2026-10-19T00:00:00Z',
    updatedAt: '2026-10-19T00:00:00Z',
  },
  {
    id: 'space-partial',
    streetId: street.id,
    code: 'P-PARTIAL',
    qrCode: 'QR-TEST-PARTIAL',
    spaceNumber: '03',
    latitude: 1.5,
    longitude: null,
    spaceType: 'STANDARD',
    active: true,
    createdAt: '2026-10-19T00:00:00Z',
    updatedAt: '2026-10-19T00:00:00Z',
  },
];

describe('Administrative map georeferencing coverage', () => {
  let api: {
    listZones: ReturnType<typeof vi.fn>;
    listStreets: ReturnType<typeof vi.fn>;
    listSpaces: ReturnType<typeof vi.fn>;
    mapCoverage: ReturnType<typeof vi.fn>;
  };
  let preview = false;

  beforeEach(async () => {
    preview = false;
    api = {
      listZones: vi.fn().mockReturnValue(of([zone])),
      listStreets: vi.fn().mockReturnValue(of([street])),
      listSpaces: vi.fn().mockReturnValue(of(spaces)),
      mapCoverage: vi.fn().mockReturnValue(of({totalSpaces:0,georeferencedSpaces:0,pendingGeoreference:0,evaluatedAt:'2026-10-19T00:00:00Z',refreshIntervalSeconds:60,markers:[]})),
    };
    await TestBed.configureTestingModule({
      imports: [MapCoverage],
      providers: [
        { provide: ZonesApi, useValue: api },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: ActivatedRoute,
          useFactory: () => ({
            snapshot: { pathFromRoot: [{ data: preview ? { preview: true } : {} }] },
          }),
        },
      ],
    }).compileComponents();
  });

  it('uses the existing read endpoints and labels coordinates as unverified without displaying them', () => {
    const fixture = TestBed.createComponent(MapCoverage);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;

    expect(api.listZones).toHaveBeenCalledOnce();
    expect(api.listStreets).toHaveBeenCalledWith(zone.id);
    expect(api.listSpaces).toHaveBeenCalledWith(street.id);
    expect(api.mapCoverage).toHaveBeenCalledWith(zone.id,undefined);
    expect(fixture.componentInstance.completePairs()).toBe(1);
    expect(fixture.componentInstance.missingPairs()).toBe(2);
    expect(fixture.componentInstance.enabledSpaces()).toBe(2);
    expect(text).toContain('Registradas · por validar');
    expect(text).toContain('Pendientes de captura');
    expect(text).toContain('Par incompleto');
    expect(text).toContain('No equivale a disponibilidad física');
    expect(text).not.toContain('1.25');
    expect(text).not.toContain('QR-TEST');
  });

  it('filters the accessible space list by administrative status and search text', () => {
    const fixture = TestBed.createComponent(MapCoverage);
    fixture.detectChanges();
    const page = fixture.componentInstance;

    page.setAdministrativeFilter('inactive');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.space-list li')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('P-MISSING');

    page.setAdministrativeFilter('all');
    page.setQuery('partial');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.space-list li')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('P-PARTIAL');
  });

  it('does not call administrative APIs in local preview mode', () => {
    preview = true;
    const fixture = TestBed.createComponent(MapCoverage);
    fixture.detectChanges();

    expect(api.listZones).not.toHaveBeenCalled();
    expect(api.listStreets).not.toHaveBeenCalled();
    expect(api.listSpaces).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Previsualización sin datos');
    expect(fixture.nativeElement.querySelectorAll('[data-map-marker]')).toHaveLength(0);
  });

  it('surfaces read failures and offers retry without performing mutations', () => {
    api.listZones.mockReturnValue(throwError(() => new Error('unavailable')));
    const fixture = TestBed.createComponent(MapCoverage);
    fixture.detectChanges();

    expect(fixture.componentInstance.zonesState()).toBe('error');
    expect(fixture.nativeElement.textContent).toContain('No se pudieron cargar las zonas');
  });

  it('refreshes map status every 30 seconds, clears stale data on a request failure and recovers on the next poll', async () => {
    const fresh = {totalSpaces:1,georeferencedSpaces:0,pendingGeoreference:1,evaluatedAt:'2026-10-19T00:00:00Z',refreshIntervalSeconds:60,markers:[]};
    api.mapCoverage.mockReset().mockReturnValueOnce(of(fresh)).mockReturnValueOnce(throwError(()=>new HttpErrorResponse({status:0,statusText:'Network Error'}))).mockReturnValueOnce(of({...fresh,evaluatedAt:'2026-10-19T00:01:00Z'}));
    vi.useFakeTimers();
    try {
      const fixture = TestBed.createComponent(MapCoverage);
      fixture.detectChanges();
      expect(api.mapCoverage).toHaveBeenCalledTimes(1);
      expect(fixture.componentInstance.mapData()?.evaluatedAt).toBe(fresh.evaluatedAt);
      await vi.advanceTimersByTimeAsync(60_000);
      fixture.detectChanges();
      expect(api.mapCoverage).toHaveBeenCalledTimes(2);
      expect(fixture.componentInstance.mapData()).toBeNull();
      expect(fixture.componentInstance.mapError()).toContain('No se pudo conectar');
      await vi.advanceTimersByTimeAsync(60_000);
      fixture.detectChanges();
      expect(api.mapCoverage).toHaveBeenCalledTimes(3);
      expect(fixture.componentInstance.mapData()?.evaluatedAt).toBe('2026-10-19T00:01:00Z');
      expect(fixture.componentInstance.mapError()).toBe('');
      fixture.destroy();
    } finally { vi.useRealTimers(); }
  });

  it('formats the server evaluation timestamp in Ecuador and explains each operational marker state', () => {
    const fixture = TestBed.createComponent(MapCoverage);fixture.detectChanges();
    const page=fixture.componentInstance;
    expect(page.mapUpdatedAt()).toContain('18 oct 2026');
    expect(page.mapStatusLabel('NO_ACTIVE_SESSION')).toContain('no confirma disponibilidad física');
    expect(page.mapStatusLabel('EXPIRED')).toContain('sigue ocupado');
    expect(page.mapStatusLabel('PAYMENT_PENDING')).toContain('Pago pendiente');
    expect(page.mapStatusLabel('MAX_TIME_REACHED')).toContain('Tiempo máximo alcanzado');
  });

  it('loads the whole selected zone first and narrows the map to a street only after that filter is selected', () => {
    const fixture=TestBed.createComponent(MapCoverage);fixture.detectChanges();const page=fixture.componentInstance;
    expect(api.mapCoverage).toHaveBeenLastCalledWith(zone.id,undefined);
    page.selectMapStreet(street.id);
    expect(api.mapCoverage).toHaveBeenLastCalledWith(zone.id,street.id);
    page.selectMapStreet('');
    expect(api.mapCoverage).toHaveBeenLastCalledWith(zone.id,undefined);
  });
});
