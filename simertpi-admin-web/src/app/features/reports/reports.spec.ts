import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { AdminReportResponse, ReportsApi } from './reports-api';
import { Reports } from './reports';

const response: AdminReportResponse = {
  from: '2026-10-04',
  to: '2026-10-05',
  timeZone: 'America/Guayaquil',
  operational: {
    sessionsStarted: 3,
    sessionsByStatus: [{ code: 'ACTIVE', count: 2 }, { code: 'COMPLETED', count: 1 }],
    sessionsByZone: [{ zoneCode: 'Z-01', zoneName: 'Centro', count: 3 }],
    dailySessions: [{ date: '2026-10-04', count: 1 }, { date: '2026-10-05', count: 2 }],
    recordedDuration: { endedSessionCount: 1, totalSeconds: '5400.000000', averageSeconds: '5400.000000' },
    spacesByZone: [{ zoneCode: 'Z-01', zoneName: 'Centro', configuredSpaces: 10, activeSpaces: 8 }],
  },
  financial: {
    paymentsByCurrentStatus: [{ status: 'APPROVED', currency: 'USD', count: 1, amount: '10.25' }],
    approvedPaymentsByPaidAt: [{ currency: 'USD', count: 1, amount: '10.25' }],
    dailyApprovedPayments: [{ date: '2026-10-05', currency: 'USD', count: 1, amount: '10.25' }],
    approvedPaymentsByZone: [{ zoneCode: 'Z-01', zoneName: 'Centro', currency: 'USD', count: 1, amount: '10.25' }],
  },
};

describe('Administrative reports view', () => {
  let api: { load: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = { load: vi.fn().mockReturnValue(of(response)) };
    await TestBed.configureTestingModule({
      imports: [Reports],
      providers: [
        { provide: ReportsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('requests the default municipal date range and presents aggregated operational and financial data', () => {
    const fixture = TestBed.createComponent(Reports);
    fixture.detectChanges();
    expect(api.load).toHaveBeenCalledOnce();
    expect(api.load).toHaveBeenCalledWith(fixture.componentInstance.filters.getRawValue().from, fixture.componentInstance.today);
    expect(fixture.nativeElement.textContent).toContain('Sesiones iniciadas');
    expect(fixture.nativeElement.textContent).toContain('USD 10,25');
    expect(fixture.nativeElement.textContent).toContain('no registra comisiones, dep\u00f3sitos ni conciliaciones bancarias');
    expect(fixture.nativeElement.textContent).toContain('no son detecci\u00f3n de veh\u00edculos ni ocupaci\u00f3n f\u00edsica');
  });

  it('formats exact decimal strings without floating-point conversion', () => {
    const page = TestBed.createComponent(Reports).componentInstance;
    expect(page.formatAmount('9999999999.99', 'USD')).toBe('USD 9.999.999.999,99');
    expect(page.formatAmount('0.10', 'USD')).toBe('USD 0,10');
  });

  it('rejects reversed or overlong date ranges without calling the API again', () => {
    const page = TestBed.createComponent(Reports).componentInstance;
    api.load.mockClear();
    page.filters.setValue({ from: '2025-10-18', to: '2026-10-19' });
    page.load();
    expect(page.state()).toBe('error');
    expect(api.load).not.toHaveBeenCalled();
    page.filters.setValue({ from: '2026-10-05', to: '2026-10-04' });
    page.load();
    expect(api.load).not.toHaveBeenCalled();
  });

  it('normalizes server errors and allows a deliberate retry', () => {
    const page = TestBed.createComponent(Reports).componentInstance;
    api.load.mockReturnValueOnce(throwError(() => new PortalError('forbidden', 'No tiene permiso.')));
    page.load();
    expect(page.state()).toBe('error');
    expect(page.errorMessage()).toBe('No tiene permiso.');
    api.load.mockReturnValue(of(response));
    page.load();
    expect(page.state()).toBe('ready');
  });

  it('keeps preview disconnected from APIs and shows no simulated metrics', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(Reports);
    fixture.detectChanges();
    expect(api.load).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('no consulta datos ni muestra indicadores de ejemplo');
  });
});
