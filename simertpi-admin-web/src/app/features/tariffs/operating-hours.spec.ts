import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ConfiguredSchedule, TariffsApi } from './tariffs-api';
import { OperatingHours } from './operating-hours';
import { ZonesApi } from '../zones/zones-api';
import { OperationToast } from '../../shared/ui/operation-toast';

const schedule = (overrides: Partial<ConfiguredSchedule> = {}): ConfiguredSchedule => ({
  id: 'schedule-1',
  zoneId: null,
  zoneCode: null,
  zoneName: null,
  dayOfWeek: 1,
  startTime: '08:00:00',
  endTime: '17:00:00',
  active: true,
  validFrom: null,
  validTo: null,
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-01T10:00:00Z',
  ...overrides,
});

describe('OperatingHours', () => {
  let api: {
    listSchedules: ReturnType<typeof vi.fn>;
    createSchedule: ReturnType<typeof vi.fn>;
    updateSchedule: ReturnType<typeof vi.fn>;
    reviseSchedule: ReturnType<typeof vi.fn>;
    endSchedule: ReturnType<typeof vi.fn>;
    deactivateSchedule: ReturnType<typeof vi.fn>;
    effectiveOperationalCalendar: ReturnType<typeof vi.fn>;
  };
  let zonesApi: { listZones: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  beforeEach(async () => {
    api = {
      listSchedules: vi.fn().mockReturnValue(of([schedule()])),
      createSchedule: vi.fn().mockReturnValue(of(schedule({ id: 'created' }))),
      updateSchedule: vi.fn().mockReturnValue(of(schedule())),
      reviseSchedule: vi.fn().mockReturnValue(of(schedule({ id: 'revision' }))),
      endSchedule: vi.fn().mockReturnValue(of(schedule({ validTo: '2999-06-30' }))),
      deactivateSchedule: vi.fn().mockReturnValue(of(schedule({ active: false }))),
      effectiveOperationalCalendar: vi.fn().mockReturnValue(of({ zoneId: 'zone-1', date: '2026-10-28', time: '10:00', operational: true, holiday: false, chargeable: true, windowStart: '08:00:00', windowEnd: '17:00:00', source: 'ZONE', reasonCode: 'RULES_RESOLVED' })),
    };
    zonesApi = {
      listZones: vi
        .fn()
        .mockReturnValue(of([{ id: 'zone-1', code: 'Z1', name: 'Centro', active: true }])),
    };
    toast = { success: vi.fn(), error: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [OperatingHours],
      providers: [
        { provide: TariffsApi, useValue: api },
        { provide: ZonesApi, useValue: zonesApi },
        { provide: OperationToast, useValue: toast },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads only the real schedule read endpoint and renders interval and badge', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    expect(api.listSchedules).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('Lunes');
    expect(fixture.nativeElement.textContent).toContain('08:00 – 17:00');
    expect(fixture.nativeElement.querySelector('app-admin-config-status')?.textContent).toContain(
      'Vigente',
    );
    expect(fixture.nativeElement.querySelector('button.primary-button')?.textContent).toContain(
      'Nuevo horario',
    );
  });

  it('filters by weekday, zone and state and exposes a clear empty-result message', () => {
    api.listSchedules.mockReturnValue(
      of([
        schedule(),
        schedule({
          id: 'schedule-2',
          dayOfWeek: 5,
          zoneId: 'zone-1',
          zoneCode: 'Z-01',
          zoneName: 'Centro',
          validFrom: '2999-01-01',
        }),
      ]),
    );
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.dayFilter.set('5');
    page.statusFilter.set('scheduled');
    expect(page.visibleRows().map((row) => row.id)).toEqual(['schedule-2']);
    expect(page.weekdayName(5)).toBe('Viernes');
    page.query.set('absent');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'No hay resultados para los filtros seleccionados',
    );
    fixture.nativeElement.querySelector('.filters button.secondary-button').click();
    fixture.detectChanges();
    expect(page.visibleRows()).toHaveLength(2);
  });

  it('consults effective hours using the backend calendar resolver', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.effectiveDate.set('2026-10-28');
    page.effectiveTime.set('10:00');
    page.consultEffective();
    expect(api.effectiveOperationalCalendar).toHaveBeenCalledWith('zone-1', '2026-10-28', '10:00');
    expect(page.effectiveResult()?.operational).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('El intervalo semanal efectivo está activo');
  });

  it('shows empty and error states and can recover by retrying the GET', () => {
    api.listSchedules
      .mockReturnValueOnce(of([]))
      .mockReturnValueOnce(throwError(() => new Error('network')))
      .mockReturnValueOnce(of([schedule()]));
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No hay horarios registrados');
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('ready');
    expect(api.listSchedules).toHaveBeenCalledTimes(3);
  });

  it('treats wall-clock schedule times as configured and derives validity dates without browser timezone conversion', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.status(schedule({ validFrom: '2999-01-01' }))).toBe('scheduled');
    expect(page.status(schedule({ validFrom: '2000-01-01', validTo: '2000-12-31' }))).toBe('ended');
    expect(page.status(schedule({ active: false }))).toBe('inactive');
    expect(page.validity(schedule({ validFrom: '2026-01-01', validTo: null }))).toContain(
      'Sin fecha final',
    );
  });

  it('creates configured hours and confirms only after a successful backend response', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.patchValue({
      scope: '__general__',
      dayOfWeek: '1',
      startTime: '08:00',
      endTime: '17:00',
      reason: 'Resolución aprobada',
    });
    page.submit();
    expect(api.createSchedule).toHaveBeenCalledWith(
      expect.objectContaining({ zoneId: null, dayOfWeek: 1, startTime: '08:00:00' }),
    );
    expect(toast.success).toHaveBeenCalledWith('El horario se creó correctamente.');
    expect(api.listSchedules).toHaveBeenCalledTimes(2);
  });

  it('programs a future revision for active hours instead of overwriting the effective row', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const row = schedule({ updatedAt: '2026-10-28T12:00:00Z' });
    page.openEdit(row);
    page.form.patchValue({ reason: 'Actualización aprobada', startTime: '09:00' });
    page.submit();
    expect(api.reviseSchedule).toHaveBeenCalledWith(
      row.id,
      expect.objectContaining({
        expectedUpdatedAt: row.updatedAt,
        validFrom: page.tomorrow(),
        startTime: '09:00:00',
      }),
    );
    expect(api.updateSchedule).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith(
      'El cambio se programó correctamente y conservará la versión anterior.',
    );
  });

  it('warns when editing a scheduled general or zonal schedule changes its coverage', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEdit(schedule({ validFrom: '2999-01-01', zoneId: null }));
    page.form.controls.scope.setValue('zone-1');
    expect(page.scopeChangeWarning()).toContain('dejará de aplicar a las demás zonas');

    page.openEdit(schedule({ validFrom: '2999-01-01', zoneId: 'zone-1' }));
    page.form.controls.scope.setValue('__general__');
    expect(page.scopeChangeWarning()).toContain('podrá aplicar a todas las zonas');
  });

  it('requires a reason and expected timestamp to end current hours in the future', () => {
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const row = schedule({ updatedAt: '2026-10-28T12:00:00Z' });
    page.openEnd(row);
    page.end();
    expect(api.endSchedule).not.toHaveBeenCalled();
    page.form.patchValue({ reason: 'Cambio de horario aprobado' });
    page.end();
    expect(api.endSchedule).toHaveBeenCalledWith(
      row.id,
      page.tomorrow(),
      row.updatedAt,
      'Cambio de horario aprobado',
    );
    expect(toast.success).toHaveBeenCalledWith(
      'La finalización del horario se programó correctamente.',
    );
  });

  it('cancels a scheduled row without deleting it and keeps the modal open on server conflict', () => {
    api.deactivateSchedule.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    const fixture = TestBed.createComponent(OperatingHours);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const row = schedule({ validFrom: '2999-01-01', updatedAt: '2026-10-28T12:00:00Z' });
    page.openDeactivate(row);
    page.form.controls.reason.setValue('Se corrigió la programación');
    page.deactivate();
    expect(api.deactivateSchedule).toHaveBeenCalledWith(
      row.id,
      expect.objectContaining({ expectedUpdatedAt: row.updatedAt }),
    );
    expect(page.dialog()).toBe('deactivate');
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledOnce();
  });
});
