import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import {
  AdminInspection,
  AdminInspectionDetail,
  AdminInspectionPage,
  InspectionsApi,
} from './inspections-api';
import { Inspections } from './inspections';

const row: AdminInspection = {
  id: '11111111-1111-4111-8111-111111111111',
  observedAt: '2026-10-27T14:00:00Z',
  result: 'EXPIRED',
  plate: 'ABC1234',
  zoneCode: 'Z-1',
  zoneName: 'Centro',
  streetCode: 'C-1',
  streetName: 'Calle Uno',
  spaceCode: 'P-1',
  spaceNumber: '8',
  inspectorUsername: 'inspector.test',
  violationId: null,
};
const page: AdminInspectionPage = {
  items: [row],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};
const detail: AdminInspectionDetail = {
  ...row,
  notes: 'Observación de prueba',
  latitude: null,
  longitude: null,
  createdAt: '2026-10-27T14:01:00Z',
};

describe('Administrative inspections view', () => {
  let api: { search: ReturnType<typeof vi.fn>; detail: ReturnType<typeof vi.fn> };
  beforeEach(async () => {
    api = {
      search: vi.fn().mockReturnValue(of(page)),
      detail: vi.fn().mockReturnValue(of(detail)),
    };
    await TestBed.configureTestingModule({
      imports: [Inspections],
      providers: [
        provideRouter([]),
        { provide: InspectionsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('renders recorded observations without representing them as sanctions', () => {
    const fixture = TestBed.createComponent(Inspections);
    fixture.detectChanges();
    expect(api.search).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('ABC1234');
    expect(fixture.nativeElement.textContent).toContain('Sesión reportada como vencida');
    expect(fixture.nativeElement.textContent).toContain('Sin infracción vinculada');
    expect(fixture.nativeElement.textContent).toContain('no genera por sí mismo una infracción');
  });

  it('applies bounded filters and opens an independently loaded detail', () => {
    const fixture = TestBed.createComponent(Inspections);
    fixture.detectChanges();
    fixture.componentInstance.filters.setValue({
      plate: 'ABC1234',
      result: 'EXPIRED',
      zone: 'Z-1',
      from: '2026-10-27',
      to: '2026-10-27',
    });
    fixture.componentInstance.applyFilters();
    expect(api.search).toHaveBeenLastCalledWith({
      page: 0,
      size: 25,
      plate: 'ABC1234',
      result: 'EXPIRED',
      zone: 'Z-1',
      from: '2026-10-27',
      to: '2026-10-27',
    });
    fixture.componentInstance.openDetail(row);
    fixture.detectChanges();
    expect(api.detail).toHaveBeenCalledWith(row.id);
    expect(fixture.nativeElement.textContent).toContain('Observación de prueba');
  });

  it('shows a backend error instead of a false success', () => {
    api.search.mockReturnValue(throwError(() => new Error('backend unavailable')));
    const fixture = TestBed.createComponent(Inspections);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.nativeElement.textContent).toContain(
      'No se pudo completar la solicitud. Inténtelo más tarde.',
    );
  });

  it('rejects reversed and overlong municipal date ranges before querying', () => {
    const fixture = TestBed.createComponent(Inspections);
    fixture.detectChanges();
    api.search.mockClear();
    fixture.componentInstance.filters.patchValue({ from: '2026-10-28', to: '2026-10-27' });
    fixture.componentInstance.applyFilters();
    expect(api.search).not.toHaveBeenCalled();
    expect(fixture.componentInstance.errorMessage()).toContain('no superar 366 días');
    fixture.componentInstance.filters.patchValue({ from: '2025-09-30', to: '2026-10-01' });
    fixture.componentInstance.applyFilters();
    expect(api.search).not.toHaveBeenCalled();
  });
});
