import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { Zones } from './zones';
import { ParkingSpace, ParkingStreet, ParkingZone, ZonesApi } from './zones-api';

const zone: ParkingZone = {
  id: 'zone-1', code: 'Z-01', name: 'Centro', description: null,
  active: true, createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
};
const street: ParkingStreet = {
  id: 'street-1', zoneId: zone.id, code: 'S-01', name: 'Calle de prueba', description: null,
  active: true, createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
};
const space: ParkingSpace = {
  id: 'space-1', streetId: street.id, code: 'P-01', qrCode: 'QR-CONTENIDO', spaceNumber: '01',
  latitude: null, longitude: null, spaceType: 'STANDARD', active: true,
  createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
};

describe('Administrative zones, streets and spaces', () => {
  let api: {
    listZones: ReturnType<typeof vi.fn>;
    createZone: ReturnType<typeof vi.fn>;
    listStreets: ReturnType<typeof vi.fn>;
    createStreet: ReturnType<typeof vi.fn>;
    listSpaces: ReturnType<typeof vi.fn>;
    createSpace: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      listZones: vi.fn().mockReturnValue(of([zone])),
      createZone: vi.fn().mockReturnValue(of({ ...zone, id: 'zone-2', code: 'Z-02', name: 'Nueva' })),
      listStreets: vi.fn().mockReturnValue(of([street])),
      createStreet: vi.fn().mockReturnValue(of({ ...street, id: 'street-2', code: 'S-02' })),
      listSpaces: vi.fn().mockReturnValue(of([space])),
      createSpace: vi.fn().mockReturnValue(of({ ...space, id: 'space-2', code: 'P-02' })),
    };
    await TestBed.configureTestingModule({
      imports: [Zones],
      providers: [
        { provide: ZonesApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads actual catalog relationships and reports real active-space counts', () => {
    const fixture = TestBed.createComponent(Zones);
    fixture.detectChanges();
    expect(api.listZones).toHaveBeenCalledOnce();
    expect(api.listStreets).toHaveBeenCalledWith(zone.id);
    expect(api.listSpaces).toHaveBeenCalledWith(street.id);
    expect(fixture.nativeElement.textContent).toContain('Z-01 · Centro');
    expect(fixture.nativeElement.textContent).toContain('S-01 · Calle de prueba');
    expect(fixture.nativeElement.textContent).toContain('1 registrados · 1 habilitados');
  });

  it('creates a zone once, reloads the directory and selects the created record', () => {
    const created = { ...zone, id: 'zone-2', code: 'Z-02', name: 'Nueva' };
    api.createZone.mockReturnValue(of(created));
    api.listZones.mockReturnValue(of([created]));
    api.listStreets.mockReturnValue(of([]));
    const fixture = TestBed.createComponent(Zones);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.zoneForm.setValue({ code: ' Z-02 ', name: ' Nueva ', description: ' ' });
    page.createZone();
    page.createZone();
    expect(api.createZone).toHaveBeenCalledTimes(1);
    expect(api.createZone).toHaveBeenCalledWith({ code: 'Z-02', name: 'Nueva', description: undefined });
    expect(page.selectedZone()?.id).toBe(created.id);
    expect(page.mutationMessage()).toContain('Z-02');
  });

  it('creates child records using selected parent IDs and does not accept parent IDs in form data', () => {
    api.listStreets.mockReturnValue(of([street, { ...street, id: 'street-2', code: 'S-02', name: 'Calle nueva' }]));
    const fixture = TestBed.createComponent(Zones);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.streetForm.setValue({ code: 'S-02', name: 'Calle nueva', description: '' });
    page.createStreet();
    expect(api.createStreet).toHaveBeenCalledWith(zone.id, {
      code: 'S-02', name: 'Calle nueva', description: undefined,
    });
    page.spaceForm.setValue({
      code: 'P-02', qrCode: 'QR-02', spaceNumber: '02', latitude: '', longitude: '', spaceType: '',
    });
    page.createSpace();
    expect(api.createSpace).toHaveBeenCalledWith('street-2', {
      code: 'P-02', qrCode: 'QR-02', spaceNumber: '02',
      latitude: undefined, longitude: undefined, spaceType: undefined,
    });
  });

  it('rejects partial coordinates and safely reports duplicate conflicts', () => {
    const fixture = TestBed.createComponent(Zones);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.spaceForm.setValue({
      code: 'P-03', qrCode: 'QR-03', spaceNumber: '03', latitude: '1.2', longitude: '', spaceType: '',
    });
    page.createSpace();
    expect(api.createSpace).not.toHaveBeenCalled();
    expect(page.mutationError()).toContain('latitud y longitud juntas');

    api.createZone.mockReturnValue(throwError(() => new PortalError('conflict', 'duplicate')));
    page.zoneForm.setValue({ code: 'Z-01', name: 'Zona repetida', description: '' });
    page.createZone();
    expect(page.mutationError()).toContain('códigos no estén repetidos');
    expect(page.mutationError()).not.toContain('duplicate');
  });

  it('keeps the local preview isolated from API calls', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(Zones);
    fixture.detectChanges();
    expect(api.listZones).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Esta vista no consulta ni modifica datos');
  });

  it('prevents overlapping submissions while a create request is pending', () => {
    const pending = new Subject<ParkingZone>();
    api.createZone.mockReturnValue(pending);
    const fixture = TestBed.createComponent(Zones);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.zoneForm.setValue({ code: 'Z-02', name: 'Nueva', description: '' });
    page.createZone();
    page.createZone();
    expect(api.createZone).toHaveBeenCalledTimes(1);
    pending.next({ ...zone, id: 'zone-2', code: 'Z-02', name: 'Nueva' });
    pending.complete();
  });
});
