import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { ZonesApi } from '../zones/zones-api';
import { BillingCalendar, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { BillingDays } from './billing-days';

const calendar: BillingCalendar = {
  id: 'general-1', zoneId: null, zoneCode: null, zoneName: null, validFrom: '2026-01-01', validTo: null,
  normativeReference: 'Ordenanza semanal', active: true, createdAt: '2026-01-01T05:00:00Z', updatedAt: '2026-01-01T05:00:00Z',
  days: { '1': true, '2': true, '3': false, '4': true, '5': true, '6': false, '7': false },
};
const effective: EffectiveConfiguration = {
  at: '2026-10-20T15:00:00Z', generalTariff: null, generalBillingCalendarId: calendar.id,
  generalChargeable: true, generalBillingDaysConfigured: true, generalBillingNormativeReference: 'Ordenanza semanal',
  zones: [],
};
type TariffsApiMock = {
  listTariffs: ReturnType<typeof vi.fn>; listSchedules: ReturnType<typeof vi.fn>;
  listCalendarExceptions: ReturnType<typeof vi.fn>; listBillingCalendars: ReturnType<typeof vi.fn>;
  effective: ReturnType<typeof vi.fn>; createTariff: ReturnType<typeof vi.fn>;
  endTariff: ReturnType<typeof vi.fn>; createBillingCalendar: ReturnType<typeof vi.fn>;
  endBillingCalendar: ReturnType<typeof vi.fn>;
};

describe('Billing days configuration screen', () => {
  let api: TariffsApiMock;
  let zonesApi: { listZones: ReturnType<typeof vi.fn> };
  beforeEach(async () => {
    api = {
      listTariffs: vi.fn().mockReturnValue(of([])), listSchedules: vi.fn().mockReturnValue(of([])),
      listCalendarExceptions: vi.fn().mockReturnValue(of([])), listBillingCalendars: vi.fn().mockReturnValue(of([calendar])),
      effective: vi.fn().mockReturnValue(of(effective)), createTariff: vi.fn(), endTariff: vi.fn(),
      createBillingCalendar: vi.fn().mockReturnValue(of('calendar-2')),
      endBillingCalendar: vi.fn().mockReturnValue(of('general-1')),
    };
    zonesApi = { listZones: vi.fn().mockReturnValue(of([])) };
    await TestBed.configureTestingModule({
      imports: [BillingDays],
      providers: [
        { provide: TariffsApi, useValue: api }, { provide: ZonesApi, useValue: zonesApi },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads only the weekly calendar contracts and identifies no configured calendar honestly', () => {
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    expect(api.listBillingCalendars).toHaveBeenCalledOnce();
    expect(api.effective).toHaveBeenCalledOnce();
    expect(api.listSchedules).not.toHaveBeenCalled();
    expect(api.listTariffs).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Martes');
    expect(fixture.nativeElement.textContent).toContain('un día sin cobro significa estacionamiento gratuito');
  });

  it('starts with no preselected chargeable days and submits only the checked days', () => {
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    expect(page.chargeableDays()).toEqual([]);
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-01', validTo: '', normativeReference: 'Ordenanza confirmada' });
    page.toggleDay(1, true);
    page.toggleDay(5, true);
    page.submit(new Event('submit'));
    expect(api.createBillingCalendar).toHaveBeenCalledWith(expect.objectContaining({ zoneId: null, validFrom: '2027-01-01', chargeableDays: [1, 5] }));
  });

  it('blocks past or impossible dates in Ecuador time and associates the messages with their fields', () => {
    vi.useFakeTimers();
    try {
      // On this instant the UTC day is already October 22, while Ecuador is still October 21.
      vi.setSystemTime(new Date('2026-10-22T03:30:00.000Z'));
      const fixture = TestBed.createComponent(BillingDays);
      fixture.detectChanges();
      const page = fixture.componentInstance;
      page.openCreate();
      expect(page.minStartDate()).toBe('2026-10-22');
      page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2026-10-21', validTo: '', normativeReference: 'Ordenanza confirmada' });
      page.submit(new Event('submit'));
      expect(page.validFromError()).toBe('La fecha de inicio debe ser posterior al día de hoy en Ecuador.');
      expect(api.createBillingCalendar).not.toHaveBeenCalled();

      page.form.controls.validFrom.setValue('2026-02-30');
      page.form.controls.validFrom.markAsTouched();
      page.submit(new Event('submit'));
      expect(page.validFromError()).toBe('Ingrese una fecha válida.');
      expect(api.createBillingCalendar).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('rejects an end date before the start date without sending a request', () => {
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-02', validTo: '2027-01-01', normativeReference: 'Ordenanza confirmada' });
    page.submit(new Event('submit'));
    expect(page.validToError()).toBe('La fecha final debe ser igual o posterior a la fecha inicial.');
    expect(api.createBillingCalendar).not.toHaveBeenCalled();
  });

  it('explains a real calendar overlap response instead of showing the user-directory conflict', () => {
    api.createBillingCalendar.mockReturnValue(throwError(() => new PortalError(
      'conflict', 'La operación entra en conflicto con la configuración o el estado actual.', 'BILLING_CALENDAR_VALIDITY_OVERLAP',
    )));
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-01', validTo: '', normativeReference: 'Ordenanza confirmada' });
    page.toggleDay(1, true);
    page.toggleDay(5, true);
    page.submit(new Event('submit'));
    expect(page.actionError()).toBe('Ya existe un calendario para el mismo alcance en el período seleccionado.');
    expect(page.actionError()).not.toContain('usuario');
    expect(api.createBillingCalendar).toHaveBeenCalledWith(expect.objectContaining({ chargeableDays: [1, 5] }));
  });

  it('shows backend field validation on the corresponding date without exposing server text', () => {
    api.createBillingCalendar.mockReturnValue(throwError(() => new PortalError(
      'validation', 'El servidor rechazó los datos del calendario.', null, ['validFrom'],
    )));
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-01', validTo: '', normativeReference: 'Ordenanza confirmada' });
    page.toggleDay(2, true);
    page.submit(new Event('submit'));
    expect(page.validFromError()).toBe('El servidor rechazó la fecha de inicio. Verifique que sea válida y futura en Ecuador.');
    expect(page.actionError()).not.toContain('servidor interno');
    expect(api.createBillingCalendar).toHaveBeenCalledWith(expect.objectContaining({ chargeableDays: [2] }));
  });

  it('preserves a zone exception scope and ends the old version before creating a replacement', () => {
    const local: BillingCalendar = { ...calendar, id: 'zone-calendar', zoneId: 'zone-1', zoneCode: 'Z-01', zoneName: 'Centro' };
    const calls: string[] = [];
    api.endBillingCalendar.mockImplementation(() => { calls.push('end'); return of(local.id); });
    api.createBillingCalendar.mockImplementation(() => { calls.push('create'); return of('new-calendar'); });
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(local);
    expect(page.chargeableDays()).toEqual([1, 2, 4, 5]);
    page.form.setValue({ scope: 'ZONE', zoneId: 'zone-1', validFrom: '2027-02-01', validTo: '', normativeReference: 'Nueva ordenanza' });
    page.submit(new Event('submit'));
    expect(calls).toEqual(['end', 'create']);
    expect(api.endBillingCalendar).toHaveBeenCalledWith('zone-calendar', '2027-02-01');
    expect(api.createBillingCalendar).toHaveBeenCalledWith(expect.objectContaining({ zoneId: 'zone-1', chargeableDays: [1, 2, 4, 5] }));
  });

  it('shows the backend failure and supports retry without changing data locally', () => {
    api.listBillingCalendars.mockReturnValue(throwError(() => new PortalError('unavailable', 'Servicio no disponible.')));
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.componentInstance.errorMessage()).toBe('Servicio no disponible.');
    api.listBillingCalendars.mockReturnValue(of([]));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
    expect(fixture.componentInstance.generalCalendar()).toBeNull();
  });

  it('does not call APIs or prefill a calendar in local preview', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } } });
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    expect(api.listBillingCalendars).not.toHaveBeenCalled();
    expect(api.effective).not.toHaveBeenCalled();
    expect(fixture.componentInstance.chargeableDays()).toEqual([]);
  });
});
