import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
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
  };
  let preview = false;

  beforeEach(async () => {
    preview = false;
    api = {
      listZones: vi.fn().mockReturnValue(of([zone])),
      listStreets: vi.fn().mockReturnValue(of([street])),
      listSpaces: vi.fn().mockReturnValue(of(spaces)),
    };
    await TestBed.configureTestingModule({
      imports: [MapCoverage],
      providers: [
        { provide: ZonesApi, useValue: api },
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
});
