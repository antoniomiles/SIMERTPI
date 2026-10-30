import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Personnel } from './personnel';
import { InspectorCreated, InspectorDirectory, MunicipalPersonnelApi } from './municipal-personnel-api';
import { PortalError } from '../../core/http/portal-error';

const inspector: InspectorCreated = {
  id: 'inspector-1', username: 'test.inspector', email: 'inspector@example.invalid',
  firstName: 'Test', lastName: 'Inspector', phone: null, enabled: false,
  activationPending: true, deliveryStatus: 'ACCEPTED_BY_PROVIDER', activationExpiresAt: '2026-10-19T00:00:00Z',
};
const directory: InspectorDirectory = {
  content: [{ ...inspector, role: 'INSPECTOR', createdAt: '2026-10-17T00:00:00Z' }],
  page: 0, size: 25, totalElements: 1, totalPages: 1,
};

describe('Municipal personnel connected flows', () => {
  let api: MunicipalPersonnelApi;
  let list: ReturnType<typeof vi.fn>;
  let create: ReturnType<typeof vi.fn>;
  let reissue: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    list = vi.fn().mockReturnValue(of(directory));
    create = vi.fn().mockReturnValue(of(inspector));
    reissue = vi.fn().mockReturnValue(of({ deliveryStatus: 'UNCONFIGURED', activationExpiresAt: '2026-10-20T00:00:00Z' }));
    api = { list, create, reissueActivation: reissue } as unknown as MunicipalPersonnelApi;
    await TestBed.configureTestingModule({
      imports: [Personnel],
      providers: [{ provide: MunicipalPersonnelApi, useValue: api }],
    }).compileComponents();
  });

  it('loads real directory data and exposes filters to the backend query', () => {
    const fixture = TestBed.createComponent(Personnel);
    fixture.detectChanges();
    expect(list).toHaveBeenCalledWith('', 'all', 0);
    fixture.componentInstance.filters.setValue({ search: 'ana', status: 'disabled' });
    fixture.componentInstance.load(0);
    expect(list).toHaveBeenCalledWith('ana', 'disabled', 0);
    expect(fixture.nativeElement.textContent).toContain('Pendiente de activación');
  });

  it('submits valid data once without allowing the browser to choose a role or password', () => {
    const fixture = TestBed.createComponent(Personnel);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.form.setValue({ username: 'new.inspector', firstName: 'Ana', lastName: 'Paz', email: 'ana@example.invalid', phone: '' });
    page.submit();
    page.submit();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create).toHaveBeenCalledWith({ username: 'new.inspector', firstName: 'Ana', lastName: 'Paz', email: 'ana@example.invalid', phone: undefined });
    expect(page.activation()?.deliveryStatus).toBe('ACCEPTED_BY_PROVIDER');
    expect(Object.keys(page.form.controls)).not.toContain('role');
    expect(Object.keys(page.form.controls)).not.toContain('password');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('keeps validation local and reports duplicate conflicts safely', () => {
    create.mockReturnValue(throwError(() => new PortalError('conflict', 'Ya existe un usuario con ese nombre o correo.')));
    const fixture = TestBed.createComponent(Personnel);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.submit();
    expect(create).not.toHaveBeenCalled();
    page.form.setValue({ username: 'duplicate', firstName: 'Ana', lastName: 'Paz', email: 'ana@example.invalid', phone: '' });
    page.submit();
    expect(page.errorMessage()).toContain('Ya existe');
  });

  it('shows email delivery status and never exposes an activation token', () => {
    const fixture = TestBed.createComponent(Personnel);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.form.setValue({ username: 'new.inspector', firstName: 'Ana', lastName: 'Paz', email: 'ana@example.invalid', phone: '' });
    page.submit();
    expect(page.activation()?.deliveryStatus).toBe('ACCEPTED_BY_PROVIDER');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('A'.repeat(43));
    expect(fixture.nativeElement.textContent).toContain('No se muestra en el portal');
    expect(fixture.nativeElement.textContent).toContain('24 horas');
    page.dismissActivation();
    expect(page.activation()).toBeNull();
  });
});
