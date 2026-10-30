import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { ZonesApi } from '../zones/zones-api';
import { BillingCalendar, BillingCalendarHistoryItem, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { BillingDays } from './billing-days';
import { OperationToast } from '../../shared/ui/operation-toast';

const calendar: BillingCalendar = {
  id: 'general-1', zoneId: null, zoneCode: null, zoneName: null, validFrom: '2026-01-01', validTo: null,
  normativeReference: 'Ordenanza semanal', active: true, createdAt: '2026-01-01T05:00:00Z', updatedAt: '2026-01-01T05:00:00Z',
  rowVersion: 0, versionGroupId: 'group-1', previousCalendarId: null, cancelledAt: null, cancelledByUserId: null,
  cancellationReason: null, cancelledByUsername: null,
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
  endBillingCalendar: ReturnType<typeof vi.fn>; updateScheduledBillingCalendar: ReturnType<typeof vi.fn>;
  reviseBillingCalendar: ReturnType<typeof vi.fn>; cancelScheduledBillingCalendar: ReturnType<typeof vi.fn>;
  billingCalendarHistory: ReturnType<typeof vi.fn>;
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
      endBillingCalendar: vi.fn().mockReturnValue(of(calendar)),
      updateScheduledBillingCalendar: vi.fn().mockReturnValue(of(calendar)),
      reviseBillingCalendar: vi.fn().mockReturnValue(of({ ...calendar, id: 'new-version', previousCalendarId: calendar.id })),
      cancelScheduledBillingCalendar: vi.fn().mockReturnValue(of({ ...calendar, active: false, cancelledAt: '2026-10-22T12:00:00Z' })),
      billingCalendarHistory: vi.fn().mockReturnValue(of([])),
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
    expect(fixture.nativeElement.textContent).toContain('un d\u00eda sin cobro significa estacionamiento gratuito');
  });

  it('starts with no preselected chargeable days and submits only the checked days', () => {
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    expect(page.chargeableDays()).toEqual([]);
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-01', validTo: '', normativeReference: 'Ordenanza confirmada', reason: '' });
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
      page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2026-10-21', validTo: '', normativeReference: 'Ordenanza confirmada', reason: '' });
      page.submit(new Event('submit'));
      expect(page.validFromError()).toBe('La fecha de inicio debe ser posterior al d\u00eda de hoy en Ecuador.');
      expect(api.createBillingCalendar).not.toHaveBeenCalled();

      page.form.controls.validFrom.setValue('2026-02-30');
      page.form.controls.validFrom.markAsTouched();
      page.submit(new Event('submit'));
      expect(page.validFromError()).toBe('Ingrese una fecha v\u00e1lida.');
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
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-02', validTo: '2027-01-01', normativeReference: 'Ordenanza confirmada', reason: '' });
    page.submit(new Event('submit'));
    expect(page.validToError()).toBe('La fecha final debe ser igual o posterior a la fecha inicial.');
    expect(api.createBillingCalendar).not.toHaveBeenCalled();
  });

  it('explains a real calendar overlap response instead of showing the user-directory conflict', () => {
    api.createBillingCalendar.mockReturnValue(throwError(() => new PortalError(
      'conflict', 'La operaciÃ³n entra en conflicto con la configuraciÃ³n o el estado actual.', 'BILLING_CALENDAR_VALIDITY_OVERLAP',
    )));
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-01', validTo: '', normativeReference: 'Ordenanza confirmada', reason: '' });
    page.toggleDay(1, true);
    page.toggleDay(5, true);
    page.submit(new Event('submit'));
    expect(page.actionError()).toBe('Ya existe un calendario para el mismo alcance en el per\u00edodo seleccionado.');
    expect(page.actionError()).not.toContain('usuario');
    expect(api.createBillingCalendar).toHaveBeenCalledWith(expect.objectContaining({ chargeableDays: [1, 5] }));
  });

  it('shows backend field validation on the corresponding date without exposing server text', () => {
    api.createBillingCalendar.mockReturnValue(throwError(() => new PortalError(
      'validation', 'El servidor rechazÃ³ los datos del calendario.', null, ['validFrom'],
    )));
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue({ scope: 'GENERAL', zoneId: '', validFrom: '2027-01-01', validTo: '', normativeReference: 'Ordenanza confirmada', reason: '' });
    page.toggleDay(2, true);
    page.submit(new Event('submit'));
    expect(page.validFromError()).toBe('El servidor rechaz\u00f3 la fecha de inicio. Verifique que sea v\u00e1lida y futura en Ecuador.');
    expect(page.actionError()).not.toContain('servidor interno');
    expect(api.createBillingCalendar).toHaveBeenCalledWith(expect.objectContaining({ chargeableDays: [2] }));
  });

  it('creates a single future version for a current zone calendar without a partial close-and-create sequence', () => {
    const local: BillingCalendar = { ...calendar, id: 'zone-calendar', zoneId: 'zone-1', zoneCode: 'Z-01', zoneName: 'Centro', validTo: '2027-03-01' };
    api.reviseBillingCalendar.mockReturnValue(of({ ...local, id: 'new-calendar', previousCalendarId: local.id }));
    const fixture = TestBed.createComponent(BillingDays);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEdit(local);
    expect(page.chargeableDays()).toEqual([1, 2, 4, 5]);
    expect(page.form.controls.validTo.value).toBe('2027-03-01');
    page.form.setValue({ scope: 'ZONE', zoneId: 'zone-1', validFrom: '2027-02-01', validTo: '2027-03-01', normativeReference: 'Nueva ordenanza', reason: 'Corrección administrativa' });
    page.submit(new Event('submit'));
    expect(api.reviseBillingCalendar).toHaveBeenCalledWith('zone-calendar', expect.objectContaining({ validFrom: '2027-02-01', chargeableDays: [1, 2, 4, 5], expectedVersion: 0 }));
    expect(api.endBillingCalendar).not.toHaveBeenCalled();
    expect(api.createBillingCalendar).not.toHaveBeenCalled();
    expect(page.actionMessage()).toContain('Se cre\u00f3 una nueva versi\u00f3n');
    expect(page.savedVersionId()).toBe('new-calendar');
    expect(TestBed.inject(OperationToast).current()).toMatchObject({ kind: 'success', message: expect.stringContaining('Calendario actualizado correctamente') });
  });

  it('updates scheduled calendars in place, retaining their identity and showing success only after confirmation', () => {
    const scheduled: BillingCalendar = { ...calendar, id: 'scheduled-1', validFrom: '2027-03-01' };
    api.listBillingCalendars.mockReturnValue(of([calendar, scheduled]));
    const fixture = TestBed.createComponent(BillingDays); fixture.detectChanges();
    const page=fixture.componentInstance; page.openEdit(scheduled);
    page.form.patchValue({ validFrom:'2027-04-01', validTo:'', normativeReference:'Nueva referencia', reason:'CorrecciÃ³n aprobada' });
    page.toggleDay(1,false); page.submit(new Event('submit'));
    expect(api.updateScheduledBillingCalendar).toHaveBeenCalledWith('scheduled-1', expect.objectContaining({ validFrom:'2027-04-01', chargeableDays:[2,4,5], expectedVersion:0 }));
    expect(page.actionMessage()).toContain('actualizado correctamente');
  });

  it('requires reason for scheduled cancellation and shows confirmation after backend success', () => {
    const scheduled: BillingCalendar = { ...calendar, id:'scheduled-2', validFrom:'2027-03-01' };
    const fixture=TestBed.createComponent(BillingDays); fixture.detectChanges(); const page=fixture.componentInstance;
    page.openCancel(scheduled); page.submitCancel(new Event('submit'));
    expect(api.cancelScheduledBillingCalendar).not.toHaveBeenCalled();
    page.cancellationForm.controls.reason.setValue('ResoluciÃ³n corregida'); page.submitCancel(new Event('submit'));
    expect(api.cancelScheduledBillingCalendar).toHaveBeenCalledWith('scheduled-2','ResoluciÃ³n corregida',0);
    expect(page.actionMessage()).toContain('no entrar\u00e1 en vigencia');
  });

  it('shows an error while retaining the entered values when a calendar write fails', () => {
    api.updateScheduledBillingCalendar.mockReturnValue(throwError(() => new PortalError('conflict','VersiÃ³n modificada por otro administrador.','BILLING_CALENDAR_VERSION_CHANGED_REFRESH_REQUIRED')));
    const scheduled: BillingCalendar = { ...calendar, id:'scheduled-3', validFrom:'2027-03-01' };
    const fixture=TestBed.createComponent(BillingDays); fixture.detectChanges(); const page=fixture.componentInstance;
    page.openEdit(scheduled); page.form.patchValue({ validFrom:'2027-04-01', reason:'Cambio autorizado' }); page.submit(new Event('submit'));
    expect(page.form.controls.validFrom.value).toBe('2027-04-01');
    expect(page.actionError()).toContain('Otra persona modific\u00f3 este calendario');
    expect(TestBed.inject(OperationToast).current()).toMatchObject({ kind: 'error', message: expect.stringContaining('Otra persona modific') });
    expect(page.showForm()).toBe(true);
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

  it('presents calendar history with both previous and new values without inventing a baseline author', () => {
    const history: BillingCalendarHistoryItem[] = [{
      id: 'history-1', calendarId: calendar.id, previousCalendarId: null, action: 'SCHEDULED_UPDATED',
      actorId: null, actorName: null, occurredAt: '2026-10-22T12:00:00Z', effectiveFrom: '2027-01-01',
      reason: 'Approved correction', beforeState: { monday: true }, afterState: { monday: false },
    }];
    api.billingCalendarHistory.mockReturnValue(of(history));
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
    });
    try {
      const fixture = TestBed.createComponent(BillingDays); fixture.detectChanges();
      const page = fixture.componentInstance; page.openHistory(calendar); fixture.detectChanges();
      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('Valores anteriores');
      expect(text).toContain('Valores nuevos');
      expect(text).toContain('Approved correction');
      expect(text).toContain('No disponible para este registro hist\u00f3rico anterior.');
      expect(fixture.nativeElement.querySelector('.history-state-comparison pre')?.textContent).toContain('monday');
    } finally { /* The test DOM requires the configurable dialog polyfill. */ }
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
