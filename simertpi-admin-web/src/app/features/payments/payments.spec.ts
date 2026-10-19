import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { Payments } from './payments';
import { AdminPaymentPage, PaymentsApi } from './payments-api';

const page: AdminPaymentPage = {
  items: [
    {
      id: 'payment-001',
      parkingSessionId: 'session-001',
      provider: 'SANDBOX',
      amount: '9999999999.99',
      currency: 'USD',
      status: 'APPROVED',
      createdAt: '2026-10-18T14:00:00Z',
      paidAt: '2026-10-18T14:00:02Z',
    },
  ],
  summaryByCurrency: [
    {
      currency: 'USD',
      approvedCount: 1,
      approvedAmount: '9999999999.99',
      pendingCount: 2,
      pendingAmount: '0.30',
      failedCount: 1,
      failedAmount: '0.90',
      refundedCount: 0,
      refundedAmount: '0.00',
      cancelledCount: 0,
      cancelledAmount: '0.00',
    },
  ],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};

describe('Administrative payments view', () => {
  let api: { search: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = { search: vi.fn().mockReturnValue(of(page)) };
    await TestBed.configureTestingModule({
      imports: [Payments],
      providers: [
        { provide: PaymentsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads payment records and clearly separates provider approval from bank settlement', () => {
    const fixture = TestBed.createComponent(Payments);
    fixture.detectChanges();
    expect(api.search).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('payment-001');
    expect(fixture.nativeElement.textContent).toContain('session-001');
    expect(fixture.nativeElement.textContent).toContain('Aprobado por proveedor');
    expect(fixture.nativeElement.textContent).toContain('USD 9.999.999.999,99');
    expect(fixture.nativeElement.textContent).toContain('no confirma que el dinero se haya liquidado');
    expect(fixture.nativeElement.textContent).toContain('no registra liquidaciones, comisiones ni conciliación bancaria');
  });

  it('formats decimal strings without floating-point conversion', () => {
    const fixture = TestBed.createComponent(Payments);
    expect(fixture.componentInstance.formatAmount('0.10', 'USD')).toBe('USD 0,10');
    expect(fixture.componentInstance.formatAmount('9999999999.99', 'USD')).toBe('USD 9.999.999.999,99');
  });

  it('applies server-side filters and resets pagination on search', () => {
    const fixture = TestBed.createComponent(Payments);
    fixture.detectChanges();
    fixture.componentInstance.filters.setValue({
      q: ' session-001 ', status: 'PENDING', from: '2026-10-01', to: '2026-10-18',
    });
    fixture.componentInstance.applyFilters();
    expect(api.search).toHaveBeenLastCalledWith({
      page: 0, size: 25, q: 'session-001', status: 'PENDING', from: '2026-10-01', to: '2026-10-18',
    });
  });

  it('rejects an inverted date range without calling the API', () => {
    const fixture = TestBed.createComponent(Payments);
    fixture.detectChanges();
    api.search.mockClear();
    fixture.componentInstance.filters.setValue({ q: '', status: '', from: '2026-10-19', to: '2026-10-18' });
    fixture.componentInstance.applyFilters();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(api.search).not.toHaveBeenCalled();
    expect(fixture.componentInstance.errorMessage()).toContain('anterior');
  });

  it('paginates only within the server-reported page range', () => {
    api.search.mockReturnValue(of({ ...page, totalPages: 2, last: false }));
    const fixture = TestBed.createComponent(Payments);
    fixture.detectChanges();
    fixture.componentInstance.next();
    expect(api.search).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 25 }));
  });

  it('shows safe API errors and supports retry', () => {
    api.search.mockReturnValueOnce(throwError(() => new PortalError('forbidden', 'No tiene permiso.')));
    const fixture = TestBed.createComponent(Payments);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.componentInstance.errorMessage()).toBe('No tiene permiso.');
    api.search.mockReturnValue(of(page));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
  });

  it('keeps local preview disconnected from payment data', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(Payments);
    fixture.detectChanges();
    expect(api.search).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Esta vista no consulta ni modifica pagos');
  });
});
