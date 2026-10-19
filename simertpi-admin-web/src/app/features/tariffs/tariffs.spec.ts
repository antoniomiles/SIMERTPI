import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { Tariffs } from './tariffs';
import { CalendarException, ConfiguredSchedule, ConfiguredTariff, TariffsApi } from './tariffs-api';

const tariff: ConfiguredTariff = {
  id: 'tariff-1', code: 'T-01', name: 'Tarifa configurada', zoneId: 'zone-1', zoneCode: 'Z-01', zoneName: 'Centro',
  currency: 'USD', amount: 0.5, durationMinutes: 30, minMinutes: 15, maxContinuousMinutes: 240,
  gracePeriodMinutes: 0, roundingMode: 'HALF_UP', active: true,
  validFrom: '2026-10-18T00:00:00Z', validTo: null, createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
};
const schedule: ConfiguredSchedule = {
  id: 'schedule-1', zoneId: 'zone-1', zoneCode: 'Z-01', zoneName: 'Centro', dayOfWeek: 1,
  startTime: '08:00:00', endTime: '17:00:00', active: true, validFrom: null, validTo: null,
  createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
};
const exception: CalendarException = {
  id: 'exception-1', holidayDate: '2026-12-25', name: 'Excepción registrada', zoneId: null,
  zoneCode: null, zoneName: null, holidayType: 'NON_TARIFFED', tariffed: false, startTime: null,
  endTime: null, normativeReference: 'Ordenanza registrada', validFrom: '2026-01-01', validTo: null,
  active: true, createdAt: '2026-01-01T00:00:00Z',
};

describe('Administrative tariff and schedule view', () => {
  let api: { listTariffs: ReturnType<typeof vi.fn>; listSchedules: ReturnType<typeof vi.fn>; listCalendarExceptions: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      listTariffs: vi.fn().mockReturnValue(of([tariff])),
      listSchedules: vi.fn().mockReturnValue(of([schedule])),
      listCalendarExceptions: vi.fn().mockReturnValue(of([exception])),
    };
    await TestBed.configureTestingModule({
      imports: [Tariffs],
      providers: [
        { provide: TariffsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads real tariff, schedule and calendar data and displays configured policy fields', () => {
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(api.listTariffs).toHaveBeenCalledOnce();
    expect(api.listSchedules).toHaveBeenCalledOnce();
    expect(api.listCalendarExceptions).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('T-01 · Tarifa configurada');
    expect(fixture.nativeElement.textContent).toContain('USD');
    expect(fixture.nativeElement.textContent).toContain('Máximo continuo: 240 min');
    expect(fixture.nativeElement.textContent).toContain('Z-01 · Centro');
    expect(fixture.nativeElement.textContent).toContain('08:00 – 17:00');
    expect(fixture.nativeElement.textContent).toContain('Ordenanza registrada');
  });

  it('filters all real catalogs by text and enabled state without changing records', () => {
    api.listTariffs.mockReturnValue(of([tariff, { ...tariff, id: 'tariff-2', code: 'OLD', active: false }]));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.statusFilter.set('disabled');
    page.query.set('old');
    fixture.detectChanges();
    expect(page.filteredTariffs().map((row) => row.id)).toEqual(['tariff-2']);
    expect(page.filteredSchedules()).toEqual([]);
    expect(page.filteredExceptions()).toEqual([]);
  });

  it('isolates the local preview from all API requests', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(api.listTariffs).not.toHaveBeenCalled();
    expect(api.listSchedules).not.toHaveBeenCalled();
    expect(api.listCalendarExceptions).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Esta vista no consulta ni modifica datos');
  });

  it('reports normalized API errors and retries all catalog requests', () => {
    api.listTariffs.mockReturnValue(throwError(() => new PortalError('forbidden', 'No tiene permiso.')));
    const fixture = TestBed.createComponent(Tariffs);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.componentInstance.errorMessage()).toBe('No tiene permiso.');
    api.listTariffs.mockReturnValue(of([tariff]));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
  });
});
