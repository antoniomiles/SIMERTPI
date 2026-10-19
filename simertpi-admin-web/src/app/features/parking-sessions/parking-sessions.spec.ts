import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { ParkingSessions } from './parking-sessions';
import {
  AdminParkingSession,
  AdminParkingSessionPage,
  ParkingSessionsApi,
} from './parking-sessions-api';

const row: AdminParkingSession = {
  id: 'session-001',
  plate: 'ABC-123',
  zoneCode: 'Z-1',
  zoneName: 'Zona Centro',
  streetCode: 'C-1',
  streetName: 'Calle Uno',
  spaceCode: 'P-1',
  spaceNumber: '10',
  spaceEnabled: true,
  sessionStatus: 'ACTIVE',
  startedAt: '2026-10-18T14:00:00Z',
  expectedEndAt: '2026-10-18T15:00:00Z',
  endedAt: null,
  durationMinutes: 35,
  paymentStatus: 'APPROVED',
};
const page: AdminParkingSessionPage = {
  items: [row],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};

describe('Administrative parking sessions view', () => {
  let api: { search: ReturnType<typeof vi.fn> };
  beforeEach(async () => {
    api = { search: vi.fn().mockReturnValue(of(page)) };
    await TestBed.configureTestingModule({
      imports: [ParkingSessions],
      providers: [
        { provide: ParkingSessionsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
  });

  it('loads real session fields and keeps session and space states distinct', () => {
    const fixture = TestBed.createComponent(ParkingSessions);
    fixture.detectChanges();
    expect(api.search).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('ABC-123');
    expect(fixture.nativeElement.textContent).toContain('Activa');
    expect(fixture.nativeElement.textContent).toContain('Espacio habilitado');
    expect(fixture.nativeElement.textContent).toContain('APPROVED');
  });

  it('applies server filters and resets pagination when searching', () => {
    const fixture = TestBed.createComponent(ParkingSessions);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.filters.setValue({
      q: 'ABC-123',
      status: 'ACTIVE',
      from: '2026-10-18',
      to: '2026-10-18',
    });
    component.load(0);
    expect(api.search).toHaveBeenLastCalledWith({
      page: 0,
      size: 25,
      q: 'ABC-123',
      status: 'ACTIVE',
      from: '2026-10-18',
      to: '2026-10-18',
    });
  });

  it('rejects reversed date ranges without an API request', () => {
    const fixture = TestBed.createComponent(ParkingSessions);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    api.search.mockClear();
    component.filters.setValue({ q: '', status: '', from: '2026-10-19', to: '2026-10-18' });
    component.applyFilters();
    expect(component.state()).toBe('error');
    expect(api.search).not.toHaveBeenCalled();
  });

  it('shows normalized access errors and supports retry', () => {
    api.search.mockReturnValueOnce(
      throwError(() => new PortalError('unauthorized', 'La sesión administrativa expiró.')),
    );
    const fixture = TestBed.createComponent(ParkingSessions);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(fixture.componentInstance.errorMessage()).toBe('La sesión administrativa expiró.');
    api.search.mockReturnValue(of(page));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state()).toBe('ready');
  });

  it('keeps local preview disconnected from operational data', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { pathFromRoot: [{ data: { preview: true } }] } },
    });
    const fixture = TestBed.createComponent(ParkingSessions);
    fixture.detectChanges();
    expect(api.search).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(
      'Esta vista no consulta ni modifica datos.',
    );
  });
});
