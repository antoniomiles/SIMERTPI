import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import { ConfiguredTariff, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { Tariffs } from './tariffs';
import { isoToGuayaquilDateTimeLocal } from './tariffs-format';
import { OperationToast } from '../../shared/ui/operation-toast';

function formValue(overrides: Record<string, unknown>) {
  return { code: '', name: '', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '', durationMinutes: null,
    minMinutes: null, maxContinuousMinutes: null, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '',
    validTo: '', normativeReference: '', reason: '', expectedVersion: null, ...overrides };
}

const tariff: ConfiguredTariff = {
  id: 'tariff-1', code: 'GEN-01', name: 'Tarifa aprobada', zoneId: null, zoneCode: null, zoneName: null,
  currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
  gracePeriodMinutes: 0, roundingMode: 'HALF_UP', active: true, normativeReference: 'Ordenanza vigente',
  validFrom: '2026-01-01T05:00:00Z', validTo: null, createdAt: '2026-01-01T05:00:00Z', updatedAt: '2026-01-01T05:00:00Z',
  cancelledAt: null, cancelledByUserId: null, cancellationReason: null, cancelledByUsername: null,
  rowVersion: 0, versionGroupId: 'tariff-series-1', previousVersionId: null,
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
  endTariff: ReturnType<typeof vi.fn>; updateScheduledTariff: ReturnType<typeof vi.fn>;
  endTariffNow: ReturnType<typeof vi.fn>; reviseTariffNow: ReturnType<typeof vi.fn>;
  cancelScheduledTariff: ReturnType<typeof vi.fn>; reviseTariff: ReturnType<typeof vi.fn>; tariffHistory: ReturnType<typeof vi.fn>; createBillingCalendar: ReturnType<typeof vi.fn>;
  endBillingCalendar: ReturnType<typeof vi.fn>;
};

describe('Tariff configuration screen', () => {
  let api: TariffsApiMock;
  let zonesApi: { listZones: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    // JSDOM does not implement the native dialog API; the browser E2E suite
    // verifies the real showModal/close behavior.
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value(this: HTMLDialogElement) { this.removeAttribute('open'); },
    });
    api = {
      listTariffs: vi.fn().mockReturnValue(of([tariff])), listSchedules: vi.fn().mockReturnValue(of([])),
      listCalendarExceptions: vi.fn().mockReturnValue(of([])), listBillingCalendars: vi.fn().mockReturnValue(of([])),
      effective: vi.fn().mockReturnValue(of(effective)), createTariff: vi.fn().mockReturnValue(of(tariff)),
      endTariff: vi.fn().mockReturnValue(of(tariff)), updateScheduledTariff: vi.fn().mockReturnValue(of(tariff)),
      endTariffNow: vi.fn().mockReturnValue(of({ ...tariff, validTo: new Date().toISOString() })),
      reviseTariff: vi.fn().mockReturnValue(of({ ...tariff, id: 'tariff-version-2', previousVersionId: tariff.id })),
      reviseTariffNow: vi.fn().mockReturnValue(of({ ...tariff, id: 'tariff-version-now', validFrom: new Date().toISOString(), previousVersionId: tariff.id })),
      tariffHistory: vi.fn().mockReturnValue(of({ items: [], page: 0, size: 25, total: 0 })),
      cancelScheduledTariff: vi.fn().mockReturnValue(of(tariff)), createBillingCalendar: vi.fn().mockReturnValue(of('calendar-1')),
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
    page.form.setValue(formValue({ code: 'GEN-2027', name: 'Tarifa general', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,25', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-01-01T00:00', validTo: '', normativeReference: 'Ordenanza artículo 5' }));
    page.submit(new Event('submit'));
    expect(api.createTariff).toHaveBeenCalledWith(expect.objectContaining({ amount: '0.25', zoneId: null, currency: 'USD' }));
    expect(api.endTariff).not.toHaveBeenCalled();
  });

  it('edits the existing future tariff id, including value and validity, without creating another row', () => {
    const scheduled: ConfiguredTariff = { ...tariff, validFrom: new Date(Date.now() + 86400000 * 3).toISOString() };
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEditScheduled(scheduled);
    const future = new Date(Date.now() + 86400000 * 4);
    const local = isoToGuayaquilDateTimeLocal(future.toISOString());
    page.form.patchValue({ code: scheduled.code, name: scheduled.name, amount: '0,75', validFrom: local, validTo: '', reason: 'Corrección de programación QA' });
    page.submit(new Event('submit'));
    expect(api.updateScheduledTariff).toHaveBeenCalledOnce();
    expect(api.updateScheduledTariff).toHaveBeenCalledWith(scheduled.id, expect.objectContaining({ amount: '0.75' }));
    expect(api.createTariff).not.toHaveBeenCalled();
  });

  it('cancels a future tariff only after a reason is entered and refreshes the list', () => {
    const scheduled: ConfiguredTariff = { ...tariff, validFrom: new Date(Date.now() + 86400000).toISOString() };
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCancel(scheduled);
    page.cancellationForm.controls.reason.setValue('Se ingresó un importe equivocado');
    page.submitCancel(new Event('submit'));
    expect(api.cancelScheduledTariff).toHaveBeenCalledWith(scheduled.id, 'Se ingresó un importe equivocado');
    expect(api.listTariffs).toHaveBeenCalledTimes(2);
    expect(page.actionMessage()).toContain('no entrará en vigencia');
  });

  it('does not call an update when the scheduled tariff has already started', () => {
    const scheduled: ConfiguredTariff = { ...tariff, validFrom: '2026-01-01T05:00:00Z' };
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEditScheduled(scheduled);
    expect(page.editingScheduled()).toBeNull();
    expect(api.updateScheduledTariff).not.toHaveBeenCalled();
  });

  it('shows a Spanish date error for an impossible calendar selection and sends no request', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue(formValue({ code: 'GEN-2027', name: 'Tarifa general', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,25', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-02-29T00:00', validTo: '', normativeReference: 'Ordenanza 001-2027' }));
    page.submit(new Event('submit'));
    expect(page.actionError()).toContain('fechas y horas válidas');
    expect(api.createTariff).not.toHaveBeenCalled();
  });

  it('distinguishes a valid but past Guayaquil start date from an invalid date and sends no request', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue(formValue({ code: 'GEN-2027', name: 'Tarifa general', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,25', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: isoToGuayaquilDateTimeLocal(new Date(Date.now() - 86400000).toISOString()), validTo: '', normativeReference: 'Ordenanza 001-2027' }));
    page.submit(new Event('submit'));
    expect(page.actionError()).toContain('debe ser futura');
    expect(page.actionError()).toContain('America/Guayaquil');
    expect(api.createTariff).not.toHaveBeenCalled();
  });

  it('shows edit and cancel actions only for future scheduled tariffs', () => {
    const scheduled: ConfiguredTariff = { ...tariff, validFrom: new Date(Date.now() + 86400000).toISOString() };
    const fixture = TestBed.createComponent(Tariffs);
    fixture.componentInstance.tariffs.set([scheduled]);
    fixture.detectChanges();
    const actionButtons = fixture.nativeElement.querySelectorAll('.row-actions .button') as NodeListOf<HTMLButtonElement>;
    const actions = [...actionButtons].map((button) => button.textContent?.trim() ?? '');
    expect(actions).toEqual(['Ver', 'Editar', 'Cancelar programación', 'Historial']);
    expect(fixture.nativeElement.querySelector('details')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Programada');
  });

  it('uses distinct soft status badges and compact icon actions without changing available actions', () => {
    const rows: ConfiguredTariff[] = [
      { ...tariff, id: 'current', validFrom: new Date(Date.now() - 86400000).toISOString() },
      { ...tariff, id: 'scheduled', validFrom: new Date(Date.now() + 86400000).toISOString() },
      { ...tariff, id: 'cancelled', validFrom: new Date(Date.now() + 86400000).toISOString(), cancelledAt: new Date().toISOString() },
      { ...tariff, id: 'ended', active: false },
    ];
    const fixture = TestBed.createComponent(Tariffs);
    fixture.componentInstance.tariffs.set(rows);
    fixture.detectChanges();
    const statuses = [...fixture.nativeElement.querySelectorAll('.tariff-status') as NodeListOf<HTMLElement>];
    expect(statuses.map((item) => item.textContent?.trim())).toEqual(['Vigente', 'Programada', 'Cancelada', 'Finalizada']);
    expect(statuses.map((item) => ['status-current', 'status-scheduled', 'status-cancelled', 'status-ended'].find((name) => item.classList.contains(name))))
      .toEqual(['status-current', 'status-scheduled', 'status-cancelled', 'status-ended']);
    const scheduledActions = fixture.nativeElement.querySelectorAll('tr:nth-child(2) .action-button') as NodeListOf<HTMLElement>;
    expect([...scheduledActions].map((item) => item.textContent?.trim())).toEqual(['Ver', 'Editar', 'Cancelar programación', 'Historial']);
    for (const action of scheduledActions) expect(action.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
  });

  it('opens a dedicated tariff detail dialog with Ecuador-local dates and cancellation history', () => {
    const scheduled: ConfiguredTariff = {
      ...tariff,
      zoneId: zone.id,
      zoneCode: zone.code,
      zoneName: zone.name,
      validFrom: new Date(Date.now() + 86400000).toISOString(),
      cancelledAt: '2026-10-20T15:00:00Z',
      cancelledByUsername: 'admin.fixture',
      cancellationReason: 'Se corrigió el importe',
    };
    const fixture = TestBed.createComponent(Tariffs);
    fixture.componentInstance.tariffs.set([scheduled]);
    fixture.detectChanges();
    const viewButton = fixture.nativeElement.querySelector('.row-actions button');
    viewButton.click();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('.tariff-detail-dialog');
    expect(dialog).not.toBeNull();
    expect(dialog.getAttribute('aria-labelledby')).toBe('tariff-detail-title');
    expect(dialog.textContent).toContain('Detalle de tarifa');
    expect(dialog.textContent).toContain('GEN-01');
    expect(dialog.textContent).toContain('Tarifa aprobada');
    expect(dialog.textContent).toContain('Excepción para zona');
    expect(dialog.textContent).toContain('Centro');
    expect(dialog.textContent).toContain('0,25 USD');
    expect(dialog.textContent).toContain('Duración por importe');
    expect(dialog.textContent).toContain('Mínimo facturable');
    expect(dialog.textContent).toContain('Máximo continuo');
    expect(dialog.textContent).toContain('Tolerancia');
    expect(dialog.textContent).toContain('Regla de redondeo');
    expect(dialog.textContent).toContain('hora de Ecuador');
    expect(dialog.textContent).toContain('admin.fixture');
    expect(dialog.textContent).toContain('Se corrigió el importe');
    expect(dialog.querySelector('details')).toBeNull();
  });

  it('opens scheduled editing in the reference side panel and asks before discarding unsaved values', () => {
    const scheduled: ConfiguredTariff = { ...tariff, validFrom: new Date(Date.now() + 86400000).toISOString() };
    const fixture = TestBed.createComponent(Tariffs);
    fixture.componentInstance.tariffs.set([scheduled]);
    fixture.detectChanges();
    fixture.nativeElement.querySelectorAll('.row-actions button')[1].click();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('.tariff-editor-panel');
    expect(dialog).not.toBeNull();
    expect(dialog.textContent).toContain('Editar tarifa programada');
    expect(dialog.querySelector('[formControlName="code"]').value).toBe(scheduled.code);
    expect(dialog.querySelector('[formControlName="amount"]').value).toBe('0,25');
    expect(dialog.textContent).toContain('Guardar cambios');

    fixture.componentInstance.form.controls.amount.setValue('0,50');
    fixture.componentInstance.form.controls.amount.markAsDirty();
    fixture.componentInstance.requestEditClose();
    fixture.detectChanges();
    expect(dialog.textContent).toContain('Descartar los cambios?');
    expect(fixture.componentInstance.editingScheduled()).toEqual(scheduled);
    fixture.componentInstance.confirmEditDiscard.set(false);
    fixture.componentInstance.closeScheduledEditor();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.tariff-editor-panel')).toBeNull();
  });

  it('revises a current tariff with one atomic version request and mandatory reason', () => {
    const calls: string[] = [];
    api.reviseTariff.mockImplementation(() => { calls.push('revise'); return of({ ...tariff, id: 'tariff-version-2' }); });
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.tariff-editor-panel')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Editar tarifa vigente');
    page.form.setValue(formValue({ code: 'GEN-01', name: 'Tarifa revisada', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-02-01T00:00', validTo: '', normativeReference: 'Nueva ordenanza', reason: 'Corrección respaldada por norma', expectedVersion: 0 }));
    page.submit(new Event('submit'));
    expect(calls).toEqual(['revise']);
    expect(api.reviseTariff).toHaveBeenCalledWith(tariff.id, expect.objectContaining({
      code: 'GEN-01', amount: '0.30', reason: 'Corrección respaldada por norma', expectedVersion: tariff.rowVersion,
      validFrom: '2027-02-01T05:00:00.000Z',
    }));
    expect(api.endTariff).not.toHaveBeenCalled();
    expect(api.createTariff).not.toHaveBeenCalled();
  });

  it('does not issue a second request or end the current tariff if revision fails', () => {
    api.reviseTariff.mockReturnValue(throwError(() => new PortalError('conflict', 'Código duplicado.')));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    page.form.setValue(formValue({ code: 'GEN-02', name: 'Tarifa revisada', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-02-01T00:00', validTo: '', normativeReference: 'Nueva ordenanza', reason: 'Motivo QA', expectedVersion: tariff.rowVersion }));
    page.submit(new Event('submit'));
    expect(page.actionError()).toContain('Código duplicado.');
    expect(api.endTariff).not.toHaveBeenCalled();
    expect(api.createTariff).not.toHaveBeenCalled();
  });

  it('applies a current tariff revision at server time and transmits the corrected Ecuador end date', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    page.setRevisionTiming('now');
    page.form.setValue(formValue({ code: 'GEN-01-NOW', name: 'Tarifa corregida', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '', validTo: '2030-12-22T22:06', normativeReference: 'Ordenanza vigente', reason: 'Corrección inmediata autorizada', expectedVersion: tariff.rowVersion }));
    page.submit(new Event('submit'));
    expect(api.reviseTariffNow).toHaveBeenCalledOnce();
    expect(api.reviseTariffNow).toHaveBeenCalledWith(tariff.id, expect.objectContaining({
      amount: '0.30', expectedVersion: tariff.rowVersion, reason: 'Corrección inmediata autorizada', validTo: '2030-12-23T03:06:00.000Z',
    }));
    expect(api.reviseTariffNow.mock.calls[0][1]).not.toHaveProperty('validFrom');
    expect(api.reviseTariff).not.toHaveBeenCalled();
    expect(page.actionMessage()).toContain('se conservaron los datos históricos');
    expect(TestBed.inject(OperationToast).current()?.message).toContain('Identificador: tariff-version-now');
  });

  it('keeps the editor and entered values when the backend rejects a revision and shows a persistent error toast', () => {
    api.reviseTariffNow.mockReturnValue(throwError(() => new PortalError('conflict', 'La operación entra en conflicto.', 'TARIFF_VALIDITY_OVERLAP')));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    page.setRevisionTiming('now');
    page.form.setValue(formValue({ code: 'GEN-01-NOW', name: 'Tarifa corregida', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validTo: '2030-12-22T22:06', normativeReference: 'Ordenanza vigente', reason: 'Corrección respaldada' }));
    page.submit(new Event('submit'));
    expect(page.revisionOf()?.id).toBe(tariff.id);
    expect(page.form.controls.amount.value).toBe('0,30');
    expect(page.actionError()).toContain('vigencia que se cruza');
    expect(TestBed.inject(OperationToast).current()).toMatchObject({ kind: 'error', message: expect.stringContaining('vigencia que se cruza') });
    expect(page.actionMessage()).toBe('');
  });

  it('rejects an end date that cannot be converted in Ecuador instead of silently sending an open-ended version', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openRevision(tariff);
    page.setRevisionTiming('now');
    page.form.setValue(formValue({ code: 'GEN-01-NOW', name: 'Tarifa corregida', currency: 'USD', amount: '0,30', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validTo: '2030-02-30T22:06', normativeReference: 'Ordenanza vigente', reason: 'Corrección respaldada' }));
    page.submit(new Event('submit'));
    expect(api.reviseTariffNow).not.toHaveBeenCalled();
    expect(page.form.controls.validTo.hasError('invalidDateTime')).toBe(true);
    expect(TestBed.inject(OperationToast).current()?.kind).toBe('error');
  });

  it('finalizes a current tariff immediately with a reason and warns about zones losing effective coverage', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEnd(tariff);
    page.setEndTiming('now');
    expect(page.zonesWithoutTariffAfterEnd(tariff)).toContain('Z-01 · Centro');
    page.endForm.controls.reason.setValue('Cese inmediato respaldado');
    page.submitEnd(new Event('submit'));
    expect(api.endTariffNow).toHaveBeenCalledWith(tariff.id, 'Cese inmediato respaldado', tariff.rowVersion);
    expect(api.endTariff).not.toHaveBeenCalled();
  });

  it('does not finalize immediately without a reason', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEnd(tariff);
    page.setEndTiming('now');
    page.submitEnd(new Event('submit'));
    expect(api.endTariffNow).not.toHaveBeenCalled();
  });

  it('requires a reason and opens a confirmation dialog before ending a current tariff', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEnd(tariff);
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('.tariff-edit-dialog') as HTMLDialogElement;
    expect(dialog).not.toBeNull();
    expect(dialog.textContent).toContain('Finalizar vigencia de GEN-01');
    expect(dialog.textContent).toContain('Si no existe una versión de reemplazo');
    page.endForm.controls.validTo.setValue(isoToGuayaquilDateTimeLocal(new Date(Date.now() + 86400000).toISOString()));
    page.submitEnd(new Event('submit'));
    expect(api.endTariff).not.toHaveBeenCalled();
    page.endForm.controls.reason.setValue('Reemplazo aprobado en QA');
    page.submitEnd(new Event('submit'));
    expect(api.endTariff).toHaveBeenCalledWith(tariff.id, expect.any(String), 'Reemplazo aprobado en QA', tariff.rowVersion);
  });

  it('explains tariff validity conflicts instead of reporting a user permission or duplicate-account error', () => {
    api.createTariff.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 409,
      error: { status: 409, message: 'TARIFF_VALIDITY_OVERLAP' },
    })));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.setValue(formValue({ code: 'GEN-2027', name: 'Tarifa general', scope: 'GENERAL', zoneId: '', currency: 'USD', amount: '0,25', durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '2027-01-01T00:00', validTo: '', normativeReference: 'Ordenanza artículo 5' }));
    page.submit(new Event('submit'));
    expect(page.actionError()).toContain('vigencia que se cruza');
    expect(page.actionError()).not.toContain('permiso');
    expect(page.actionError()).not.toContain('usuario');
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
