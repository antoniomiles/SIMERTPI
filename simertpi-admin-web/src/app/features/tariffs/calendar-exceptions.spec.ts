import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { CalendarException, TariffsApi } from './tariffs-api';
import { CalendarExceptions } from './calendar-exceptions';
import { ZonesApi } from '../zones/zones-api';
import { OperationToast } from '../../shared/ui/operation-toast';

const exception = (overrides: Partial<CalendarException> = {}): CalendarException => ({
  id: 'holiday-1',
  holidayDate: '2999-12-25',
  name: 'Feriado nacional',
  zoneId: null,
  zoneCode: null,
  zoneName: null,
  holidayType: 'NATIONAL_HOLIDAY',
  tariffed: false,
  startTime: null,
  endTime: null,
  normativeReference: null,
  validFrom: '2026-01-01',
  validTo: null,
  active: true,
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-01T10:00:00Z',
  ...overrides,
});

describe('CalendarExceptions', () => {
  let api: {
    listCalendarExceptions: ReturnType<typeof vi.fn>;
    createCalendarException: ReturnType<typeof vi.fn>;
    updateCalendarException: ReturnType<typeof vi.fn>;
    deactivateCalendarException: ReturnType<typeof vi.fn>;
    effectiveOperationalCalendar: ReturnType<typeof vi.fn>;
  };
  let zonesApi: { listZones: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let preview = false;
  beforeEach(async () => {
    api = {
      listCalendarExceptions: vi.fn().mockReturnValue(of([exception()])),
      createCalendarException: vi.fn().mockReturnValue(of(exception({ id: 'saved-id' }))),
      updateCalendarException: vi.fn().mockReturnValue(of(exception())),
      deactivateCalendarException: vi.fn().mockReturnValue(of(exception({ active: false }))),
      effectiveOperationalCalendar: vi.fn().mockReturnValue(of({ zoneId: 'zone-1', date: '2026-10-28', time: '10:00', operational: true, holiday: false, chargeable: true, windowStart: '08:00:00', windowEnd: '17:00:00', source: 'GENERAL', reasonCode: 'RULES_RESOLVED' })),
    };
    zonesApi = {
      listZones: vi
        .fn()
        .mockReturnValue(of([{ id: 'zone-1', code: 'Z1', name: 'Centro', active: true }])),
    };
    toast = { success: vi.fn(), error: vi.fn() };
    preview = false;
    await TestBed.configureTestingModule({
      imports: [CalendarExceptions],
      providers: [
        { provide: TariffsApi, useValue: api },
        { provide: ZonesApi, useValue: zonesApi },
        { provide: OperationToast, useValue: toast },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { pathFromRoot: [{ data: { preview } }] } },
        },
      ],
    }).compileComponents();
  });

  it('loads only the existing read endpoint and renders the accessible status badge', () => {
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    expect(api.listCalendarExceptions).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('Feriado nacional');
    expect(fixture.nativeElement.querySelector('app-admin-config-status')?.textContent).toContain(
      'Vigente',
    );
    expect(fixture.nativeElement.querySelector('button.primary-button')?.textContent).toContain(
      'Nueva excepción',
    );
    expect(fixture.nativeElement.querySelectorAll('.row-actions button')).toHaveLength(3);
  });

  it('filters by text and status, and clears filters', () => {
    api.listCalendarExceptions.mockReturnValue(
      of([
        exception(),
        exception({
          id: 'holiday-2',
          holidayDate: '2999-12-31',
          name: 'Fiesta local',
          zoneId: 'zone-1',
          zoneCode: 'Z-01',
          zoneName: 'Centro',
          validFrom: '2027-01-01',
        }),
      ]),
    );
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.query.set('centro');
    expect(page.visibleRows().map((row) => row.id)).toEqual(['holiday-2']);
    expect(page.status(page.visibleRows()[0])).toBe('scheduled');
    page.statusFilter.set('scheduled');
    expect(page.visibleRows()).toHaveLength(1);
    page.query.set('does not exist');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'No hay resultados para los filtros seleccionados',
    );
    fixture.nativeElement.querySelector('.filters button.secondary-button').click();
    fixture.detectChanges();
    expect(page.visibleRows()).toHaveLength(2);
  });

  it('queries the server-resolved calendar with Ecuador local date and time', () => {
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.effectiveDate.set('2026-10-28');
    page.effectiveTime.set('10:00');
    page.consultEffective();
    expect(api.effectiveOperationalCalendar).toHaveBeenCalledWith('zone-1', '2026-10-28', '10:00');
    expect(page.effectiveResult()?.source).toBe('GENERAL');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('El intervalo semanal efectivo está activo');
  });

  it('shows empty state from an empty backend response and retries a failed GET', () => {
    api.listCalendarExceptions
      .mockReturnValueOnce(of([]))
      .mockReturnValueOnce(throwError(() => new Error('network')))
      .mockReturnValueOnce(of([exception()]));
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No hay excepciones registradas');
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('ready');
    expect(api.listCalendarExceptions).toHaveBeenCalledTimes(3);
  });

  it('uses Ecuador calendar dates for scheduled, ended and inactive states', () => {
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.status(exception({ validFrom: '2999-01-01' }))).toBe('scheduled');
    expect(page.status(exception({ validFrom: '2000-01-01', validTo: '2000-12-31' }))).toBe(
      'ended',
    );
    expect(page.status(exception({ active: false }))).toBe('inactive');
  });

  it('creates a holiday exception and confirms only after the API succeeds', () => {
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreate();
    page.form.patchValue({
      holidayDate: '2999-12-25',
      name: 'Día especial',
      scope: '__general__',
      holidayType: 'MUNICIPAL_EXCEPTION',
      tariffed: 'false',
      validFrom: '2999-01-01',
      reason: 'Disposición aprobada',
    });
    page.submit();
    expect(api.createCalendarException).toHaveBeenCalledWith(
      expect.objectContaining({ zoneId: null, tariffed: false }),
    );
    expect(toast.success).toHaveBeenCalledWith('La excepción se creó correctamente.');
    expect(api.listCalendarExceptions).toHaveBeenCalledTimes(2);
  });

  it('retains the edit form and reports a real server conflict', () => {
    api.updateCalendarException.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const row = exception({ updatedAt: '2026-10-28T12:00:00Z' });
    page.openEdit(row);
    page.form.patchValue({ reason: 'Cambio aprobado' });
    page.submit();
    expect(api.updateCalendarException).toHaveBeenCalledWith(
      row.id,
      expect.objectContaining({ expectedUpdatedAt: row.updatedAt }),
    );
    expect(page.dialog()).toBe('edit');
    expect(page.form.controls.name.value).toBe(row.name);
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledOnce();
  });

  it('requires a reason before deactivation and sends the concurrency token', () => {
    const fixture = TestBed.createComponent(CalendarExceptions);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const row = exception({ updatedAt: '2026-10-28T12:00:00Z' });
    page.openDeactivate(row);
    page.deactivate();
    expect(api.deactivateCalendarException).not.toHaveBeenCalled();
    page.form.controls.reason.setValue('Configuración duplicada');
    page.deactivate();
    expect(api.deactivateCalendarException).toHaveBeenCalledWith(row.id, {
      reason: 'Configuración duplicada',
      expectedUpdatedAt: row.updatedAt,
    });
  });
});
