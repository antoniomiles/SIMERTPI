import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import { ConfiguredTariff, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { Tariffs } from './tariffs';

const tariff: ConfiguredTariff = {
  id: 'tariff-1', code: 'GEN-01', name: 'Tarifa aprobada', zoneId: null, zoneCode: null, zoneName: null,
  currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
  gracePeriodMinutes: 0, roundingMode: 'HALF_UP', active: true, normativeReference: 'Ordenanza vigente',
  validFrom: '2026-01-01T05:00:00Z', validTo: null, createdAt: '2026-01-01T05:00:00Z', updatedAt: '2026-01-01T05:00:00Z',
};
const zone: ParkingZone = {
  id: 'zone-1', code: 'Z-01', name: 'Centro', description: null, active: true,
  createdAt: '2026-01-01T05:00:00Z', updatedAt: '2026-01-01T05:00:00Z',
};
const effective: EffectiveConfiguration = {
  at: '2026-10-20T15:00:00Z', generalTariff: {
    id: tariff.id, code: tariff.code, name: tariff.name, currency: 'USD', amount: '0.25', durationMinutes: 60,
    minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP',
    validFrom: tariff.validFrom, validTo: null, normativeReference: 'Ordenanza vigente',
  },
  generalBillingCalendarId: null, generalChargeable: false, generalBillingDaysConfigured: false,
  generalBillingNormativeReference: null,
  zones: [{ zoneId: zone.id, zoneCode: zone.code, zoneName: zone.name, tariff: {
    id: tariff.id, code: tariff.code, name: tariff.name, currency: 'USD', amount: '0.25', durationMinutes: 60,
    minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP',
    validFrom: tariff.validFrom, validTo: null, normativeReference: 'Ordenanza vigente',
  }, tariffSource: 'GENERAL',
    billingCalendarId: null, chargeable: true, billingDaysConfigured: false, billingDaysSource: 'LEGACY_UNCONFIGURED',
    billingDaysNormativeReference: null }],
};
type TariffsApiMock = {
  listTariffs: ReturnType<typeof vi.fn>; listSchedules: ReturnType<typeof vi.fn>;
  listCalendarExceptions: ReturnType<typeof vi.fn>; listBillingCalendars: ReturnType<typeof vi.fn>;
  effective: ReturnType<typeof vi.fn>; createTariff: ReturnType<typeof vi.fn>;
  endTariff: ReturnType<typeof vi.fn>; createBillingCalendar: ReturnType<typeof vi.fn>;
  endBillingCalendar: ReturnType<typeof vi.fn>;
};

describe('Tariff configuration screen', () => {
  let api: TariffsApiMock;
  let zonesApi: { listZones: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      listTariffs: vi.fn().mockReturnValue(of([tariff])), listSchedules: vi.fn().mockReturnValue(of([])),
      listCalendarExceptions: vi.fn().mockReturnValue(of([])), listBillingCalendars: vi.fn().mockReturnValue(of([])),
      effective: vi.fn().mockReturnValue(of(effective)), createTariff: vi.fn().mockReturnValue(of(tariff)),
      endTariff: vi.fn().mockReturnValue(of(tariff)), createBillingCalendar: vi.fn().mockReturnValue(of('calendar-1')),
      endBillingCalendar: vi.fn().mockReturnValue(of('calendar-1')),
    };
    zonesApi = { listZones: vi.fn().mockReturnValue(of([zone])) };
    await TestBed.configureTestingModule({
      imports: [Tariffs],
      providers: [
        { provide: TariffsApi, useValue: api }, { provide: ZonesApi, useValue: zonesApi },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads the real read contracts and shows general inheritance without adding calendars or schedules', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(api.listTariffs).toHaveBeenCalledOnce();
    expect(api.effective).toHaveBeenCalledOnce();
    expect(zonesApi.listZones).toHaveBeenCalledOnce();
    expect(api.listBillingCalendars).not.toHaveBeenCalled();
    expect(api.listSchedules).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('TARIFA GENERAL VIGENTE');
    expect(fixture.nativeElement.textContent).toContain('Regla general heredada');
    expect(fixture.nativeElement.textContent).toContain('0,25 USD');
  });

  it('keeps the editor closed until the administrator chooses to create or modify', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
  });

  it('normalizes a locally entered comma decimal for the existing exact-decimal API contract', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue({ code: 'GEN-2027', name: 'Tarifa general', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,25', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-01-01T00:00', validTo: '', normativeReference: 'Ordenanza artículo 5' });
    page.submit(new Event('submit'));
    expect(api.createTariff).toHaveBeenCalledWith(expect.objectContaining({ amount: '0.25', zoneId: null, currency: 'USD' }));
    expect(api.endTariff).not.toHaveBeenCalled();
  });

  it('revises an existing version by ending it at the new start and creating a new version', () => {
    const calls: string[] = [];
    api.endTariff.mockImplementation(() => { calls.push('end'); return of(tariff); });
    api.createTariff.mockImplementation(() => { calls.push('create'); return of(tariff); });
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    page.form.setValue({ code: 'GEN-02', name: 'Tarifa revisada', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-02-01T00:00', validTo: '', normativeReference: 'Nueva ordenanza' });
    page.submit(new Event('submit'));
    expect(calls).toEqual(['end', 'create']);
    expect(api.endTariff).toHaveBeenCalledWith(tariff.id, new Date('2027-02-01T00:00').toISOString());
  });

  it('shows a clear warning if the old version was scheduled to end but creation of its replacement fails', () => {
    api.endTariff.mockReturnValue(of(tariff));
    api.createTariff.mockReturnValue(throwError(() => new PortalError('conflict', 'Código duplicado.')));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    page.form.setValue({ code: 'GEN-02', name: 'Tarifa revisada', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-02-01T00:00', validTo: '', normativeReference: 'Nueva ordenanza' });
    page.submit(new Event('submit'));
    expect(page.actionError()).toContain('la nueva versión no se confirmó');
  });

  it('keeps the approved tariff data and provides a retry after a read error', () => {
    api.listTariffs.mockReturnValue(throwError(() => new PortalError('forbidden', 'No tiene permiso.')));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.componentInstance.errorMessage()).toBe('No tiene permiso.');
    api.listTariffs.mockReturnValue(of([tariff]));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
  });

  it('does not call configuration APIs in local preview mode', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } } });
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(api.listTariffs).not.toHaveBeenCalled();
    expect(api.effective).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('No se muestran tarifas de ejemplo');
  });
});
