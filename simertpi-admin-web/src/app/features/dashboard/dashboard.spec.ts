import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { AdminReportResponse, ReportsApi } from '../reports/reports-api';
import { Dashboard } from './dashboard';
import { DashboardVisualizations } from './dashboard-visualizations';

const response: AdminReportResponse = {
  from: '2026-10-13',
  to: '2026-10-19',
  timeZone: 'America/Guayaquil',
  operational: {
    sessionsStarted: 6,
    sessionsByStatus: [
      { code: 'ACTIVE', count: 4 },
      { code: 'COMPLETED', count: 2 },
    ],
    sessionsByZone: [
      { zoneCode: 'Z-01', zoneName: 'Centro', count: 4 },
      { zoneCode: 'Z-02', zoneName: 'Norte', count: 2 },
    ],
    dailySessions: [
      { date: '2026-10-13', count: 1 },
      { date: '2026-10-14', count: 0 },
      { date: '2026-10-15', count: 5 },
    ],
    recordedDuration: { endedSessionCount: 2, totalSeconds: '7200', averageSeconds: '3600' },
    spacesByZone: [
      { zoneCode: 'Z-01', zoneName: 'Centro', configuredSpaces: 10, activeSpaces: 8 },
      { zoneCode: 'Z-02', zoneName: 'Norte', configuredSpaces: 7, activeSpaces: 6 },
    ],
  },
  financial: {
    paymentsByCurrentStatus: [
      { status: 'PENDING', currency: 'USD', count: 2, amount: '4.00' },
      { status: 'PROCESSING', currency: 'USD', count: 1, amount: '2.00' },
      { status: 'APPROVED', currency: 'USD', count: 3, amount: '10.25' },
      { status: 'FAILED', currency: 'USD', count: 8, amount: '20.00' },
    ],
    approvedPaymentsByPaidAt: [
      { currency: 'USD', count: 2, amount: '10.25' },
      { currency: 'EUR', count: 1, amount: '5.10' },
    ],
    dailyApprovedPayments: [
      { date: '2026-10-13', currency: 'USD', count: 1, amount: '0.10' },
      { date: '2026-10-14', currency: 'USD', count: 1, amount: '10.15' },
      { date: '2026-10-13', currency: 'EUR', count: 1, amount: '5.10' },
    ],
    approvedPaymentsByZone: [],
  },
};

describe('Administrative dashboard', () => {
  let api: { load: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = { load: vi.fn().mockReturnValue(of(response)) };
    await TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [provideRouter([]), { provide: ReportsApi, useValue: api }],
    }).compileComponents();
  });

  it('loads the default seven municipal days and renders the real report aggregates', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const { from, to } = page.filters.getRawValue();
    expect((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000).toBe(6);
    expect(api.load).toHaveBeenCalledOnce();
    expect(api.load).toHaveBeenCalledWith(from, to);
    expect(page.lastSuccessfulAt()).toBeInstanceOf(Date);
    expect(fixture.nativeElement.textContent).toContain('SIMERTPI — GAD Municipal de Piñas');
    expect(fixture.nativeElement.textContent).toContain('Sesiones iniciadas');
    expect(fixture.nativeElement.textContent).toContain('USD 10,25');
    expect(fixture.nativeElement.textContent).toContain('EUR 5,10');
    expect(fixture.nativeElement.textContent).not.toContain('USD 15,35');
    expect(fixture.nativeElement.textContent).toContain('17');
    expect(fixture.nativeElement.textContent).toContain('14 habilitados');
    expect(fixture.nativeElement.textContent).toContain('3');
    expect(fixture.nativeElement.textContent).toContain('no indica disponibilidad física');
    expect(fixture.nativeElement.textContent).toContain(
      'no confirma liquidación ni depósito bancario',
    );
  });

  it('counts only current PENDING and PROCESSING payment records from the selected period', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.componentInstance.pendingPayments()).toBe(3);
    expect(fixture.nativeElement.textContent).toContain('conservan PENDING o PROCESSING');
  });

  it('formats decimal strings exactly and computes chart widths with integer minor units', () => {
    const fixture = TestBed.createComponent(DashboardVisualizations);
    fixture.componentRef.setInput('data', response);
    fixture.detectChanges();
    const charts = fixture.componentInstance;
    expect(charts.formatAmount('9999999999.99', 'USD')).toBe('USD 9.999.999.999,99');
    expect(charts.formatAmount('0.10', 'USD')).toBe('USD 0,10');
    expect(charts.paymentBarWidth('0.10', 'USD')).toBe(0.98);
    expect(charts.paymentBarWidth('5.10', 'EUR')).toBe(100);
  });

  it('renders accessible chart descriptions and current module links from report data', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[aria-label="Sesiones iniciadas por día"]'),
    ).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[aria-label="ACTIVE: 4 sesiones"]')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[aria-label="Z-01 Centro: 4 sesiones"]'),
    ).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('Ver sesiones');
    expect(fixture.nativeElement.textContent).toContain('Ver pagos');
    expect(fixture.nativeElement.textContent).toContain('Abrir reportes');
  });

  it('uses the selected dates for manual refresh and ignores duplicate calls while loading', () => {
    const pending = new Subject<AdminReportResponse>();
    api.load.mockReturnValueOnce(of(response)).mockReturnValue(pending);
    const fixture = TestBed.createComponent(Dashboard);
    const page = fixture.componentInstance;
    expect(api.load).toHaveBeenCalledOnce();
    page.filters.setValue({ from: '2026-10-01', to: '2026-10-08' });
    page.load();
    page.load();
    expect(api.load).toHaveBeenCalledTimes(2);
    expect(api.load).toHaveBeenLastCalledWith('2026-10-01', '2026-10-08');
    pending.next(response);
    expect(page.state()).toBe('ready');
  });

  it('rejects reversed and overlong periods without making another request', () => {
    const fixture = TestBed.createComponent(Dashboard);
    const page = fixture.componentInstance;
    api.load.mockClear();
    page.filters.setValue({ from: '2026-10-19', to: '2026-10-18' });
    page.load();
    page.filters.setValue({ from: '2025-10-18', to: '2026-10-19' });
    page.load();
    expect(api.load).not.toHaveBeenCalled();
    expect(page.state()).toBe('error');
  });

  it('shows a normalized API error and recovers on a deliberate retry', () => {
    api.load.mockReturnValueOnce(
      throwError(() => new PortalError('network', 'Servicio no disponible.')),
    );
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.componentInstance.lastSuccessfulAt()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Servicio no disponible.');
    api.load.mockReturnValue(of(response));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
    expect(fixture.componentInstance.lastSuccessfulAt()).toBeInstanceOf(Date);
  });

  it('shows zero and chart empty states when the report has no activity', () => {
    api.load.mockReturnValue(
      of({
        ...response,
        operational: {
          ...response.operational,
          sessionsStarted: 0,
          sessionsByStatus: [],
          sessionsByZone: [],
          dailySessions: [],
          spacesByZone: [],
        },
        financial: {
          ...response.financial,
          paymentsByCurrentStatus: [],
          approvedPaymentsByPaidAt: [],
          dailyApprovedPayments: [],
        },
      }),
    );
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No hay sesiones iniciadas para graficar');
    expect(fixture.nativeElement.textContent).toContain('No hay actividad vinculada a zonas');
    expect(fixture.nativeElement.textContent).toContain('No hay pagos aprobados por proveedor');
    expect(fixture.nativeElement.textContent).toContain(
      'No hay zonas configuradas para contar espacios',
    );
  });

  it('keeps local preview disconnected and without invented indicators', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    expect(api.load).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(
      'no consulta datos ni presenta indicadores de ejemplo',
    );
    expect(fixture.nativeElement.querySelector('.metric-grid')).toBeNull();
  });
});
