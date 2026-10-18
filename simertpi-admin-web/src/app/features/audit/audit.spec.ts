import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Audit } from './audit';
import { AuditApi, AuditRecord } from './audit-api';
import { PortalError } from '../../core/http/portal-error';

const rows: AuditRecord[] = [
  {
    id: 'event-1',
    occurredAt: '2026-10-18T12:00:00Z',
    actorName: 'Admin Prueba',
    actorRole: 'SIMERTPI_ADMIN',
    action: 'INSPECTOR_CREATED',
    resourceType: 'INSPECTOR',
    result: 'SUCCESS',
    correlationId: 'trace-1',
  },
];

describe('Administrative audit view', () => {
  let search: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    search = vi.fn().mockReturnValue(of(rows));
    await TestBed.configureTestingModule({
      imports: [Audit],
      providers: [{ provide: AuditApi, useValue: { search } }],
    }).compileComponents();
  });

  it('loads real API results and renders the operational audit fields', () => {
    const fixture = TestBed.createComponent(Audit);
    fixture.detectChanges();
    expect(search).toHaveBeenCalledWith(expect.objectContaining({ offset: 0, limit: 51 }));
    expect(fixture.nativeElement.textContent).toContain('INSPECTOR_CREATED');
    expect(fixture.nativeElement.textContent).toContain('Admin Prueba');
  });

  it('translates filters and moves through result pages without exceeding API bounds', () => {
    search.mockReturnValue(
      of([
        ...rows,
        ...Array.from({ length: 50 }, (_, i) => ({ ...rows[0], id: `event-${i + 2}` })),
      ]),
    );
    const fixture = TestBed.createComponent(Audit);
    fixture.detectChanges();
    fixture.componentInstance.filters.setValue({
      action: ' LOGIN ',
      resourceType: ' ADMIN_SESSION ',
      result: 'FAILURE',
      correlationId: ' trace-2 ',
      from: '2026-10-18T09:00',
      to: '2026-10-18T10:00',
    });
    fixture.componentInstance.applyFilters();
    expect(search).toHaveBeenLastCalledWith(
      expect.objectContaining({
        action: ' LOGIN ',
        resourceType: ' ADMIN_SESSION ',
        result: 'FAILURE',
        correlationId: ' trace-2 ',
        from: new Date('2026-10-18T09:00').toISOString(),
        to: new Date('2026-10-18T10:00').toISOString(),
        offset: 0,
        limit: 51,
      }),
    );
    fixture.componentInstance.next();
    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 50 }));
  });

  it('rejects an inverted date range locally and shows safe API errors', () => {
    const fixture = TestBed.createComponent(Audit);
    fixture.detectChanges();
    fixture.componentInstance.filters.patchValue({
      from: '2026-10-19T10:00',
      to: '2026-10-18T10:00',
    });
    const callsBefore = search.mock.calls.length;
    fixture.componentInstance.applyFilters();
    expect(search).toHaveBeenCalledTimes(callsBefore);
    expect(fixture.componentInstance.errorMessage()).toContain('anterior');

    search.mockReturnValue(throwError(() => new PortalError('forbidden', 'Acceso no permitido.')));
    fixture.componentInstance.filters.reset({
      action: '',
      resourceType: '',
      result: '',
      correlationId: '',
      from: '',
      to: '',
    });
    fixture.componentInstance.load();
    expect(fixture.componentInstance.errorMessage()).toBe('Acceso no permitido.');
    expect(fixture.componentInstance.state()).toBe('error');
  });
});
