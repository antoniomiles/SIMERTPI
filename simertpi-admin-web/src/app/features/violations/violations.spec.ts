import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { AdminViolation, AdminViolationPage, ViolationsApi } from './violations-api';
import { Violations } from './violations';

const row: AdminViolation = {
  id: 'violation-001',
  plate: 'ABC-123',
  violationType: 'NO_PAYMENT',
  status: 'OPEN',
  occurredAt: '2026-10-19T14:00:00Z',
  inspectionId: 'inspection-001',
  parkingSessionId: null,
  zoneCode: 'Z-1',
  zoneName: 'Zona Centro',
  streetCode: 'C-1',
  streetName: 'Calle Uno',
  spaceCode: 'P-1',
  spaceNumber: '10',
  inspectorUsername: 'inspector.dev',
  evidenceCount: 1,
  createdAt: '2026-10-19T14:01:00Z',
  notifiedAt: null,
  paidAt: null,
};
const page: AdminViolationPage = {
  items: [row],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};

describe('Administrative violations view', () => {
  let api: { search: ReturnType<typeof vi.fn>; detail: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = { search: vi.fn().mockReturnValue(of(page)), detail: vi.fn().mockReturnValue(of(row)) };
    await TestBed.configureTestingModule({
      imports: [Violations],
      providers: [
        { provide: ViolationsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('renders the actual read-only fields and clarifies recorded status', () => {
    const fixture = TestBed.createComponent(Violations);
    fixture.detectChanges();
    expect(api.search).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('ABC-123');
    expect(fixture.nativeElement.textContent).toContain('NO_PAYMENT');
    expect(fixture.nativeElement.textContent).toContain('Abierta');
    expect(fixture.nativeElement.textContent).toContain('Zona Centro');
    expect(fixture.nativeElement.textContent).toContain('1 resultados');
    expect(fixture.nativeElement.textContent).not.toContain('fineAmount');
  });

  it('sends server-side filters and resets the page on search', () => {
    const fixture = TestBed.createComponent(Violations);
    fixture.detectChanges();
    fixture.componentInstance.filters.setValue({
      q: 'ABC-123', plate: 'ABC-123', zone: 'Z-1', status: 'OPEN',
      from: '2026-10-19', to: '2026-10-19',
    });
    fixture.componentInstance.load(0);
    expect(api.search).toHaveBeenLastCalledWith({
      page: 0, size: 25, q: 'ABC-123', plate: 'ABC-123', zone: 'Z-1', status: 'OPEN',
      from: '2026-10-19', to: '2026-10-19',
    });
  });

  it('rejects a reversed or overlong date range before calling the API', () => {
    const fixture = TestBed.createComponent(Violations);
    fixture.detectChanges();
    api.search.mockClear();
    fixture.componentInstance.filters.patchValue({ from: '2025-01-01', to: '2026-01-02' });
    fixture.componentInstance.applyFilters();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(api.search).not.toHaveBeenCalled();
  });

  it('normalizes query errors and permits a later retry', () => {
    api.search.mockReturnValueOnce(
      throwError(() => new PortalError('unavailable', 'El servicio no está disponible.')),
    );
    const fixture = TestBed.createComponent(Violations);
    fixture.detectChanges();
    expect(fixture.componentInstance.errorMessage()).toBe('El servicio no está disponible.');
    api.search.mockReturnValue(of(page));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
  });

  it('loads a minimized detail and exposes no narrative or fine amount', () => {
    const fixture = TestBed.createComponent(Violations);
    fixture.detectChanges();
    fixture.componentInstance.openDetail(row);
    fixture.detectChanges();
    expect(api.detail).toHaveBeenCalledWith(row.id);
    expect(fixture.nativeElement.textContent).toContain('DETALLE MINIMIZADO');
    expect(fixture.nativeElement.textContent).not.toContain('private violation narrative');
    expect(fixture.nativeElement.textContent).not.toContain('Valor de multa');
  });

  it('keeps local preview disconnected from enforcement records', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(Violations);
    fixture.detectChanges();
    expect(api.search).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Previsualización local');
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });
});
