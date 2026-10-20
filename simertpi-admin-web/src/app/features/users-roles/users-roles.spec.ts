import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminAccessApi, AdminAccountPage, AdminRoleSummary } from './admin-access-api';
import { UsersRoles } from './users-roles';

const accountPage: AdminAccountPage = {
  content: [{
    id: 'account-1', username: 'municipal.user', email: 'user@example.invalid',
    firstName: 'Test', lastName: 'User', roles: ['INSPECTOR'], enabled: false,
    activationPending: true, createdAt: '2026-10-18T10:00:00Z',
  }],
  page: 0, size: 25, totalElements: 1, totalPages: 1,
};
const roleCatalog: AdminRoleSummary[] = [{
  code: 'INSPECTOR', name: 'Inspector municipal', description: 'Rol del personal de inspección', assignedAccounts: 1,
}];

describe('Users and roles administration', () => {
  let api: { users: ReturnType<typeof vi.fn>; roles: ReturnType<typeof vi.fn> };
  let preview = false;

  beforeEach(async () => {
    preview = false;
    api = { users: vi.fn().mockReturnValue(of(accountPage)), roles: vi.fn().mockReturnValue(of(roleCatalog)) };
    await TestBed.configureTestingModule({
      imports: [UsersRoles],
      providers: [
        { provide: AdminAccessApi, useValue: api },
        { provide: ActivatedRoute, useFactory: () => ({ snapshot: { pathFromRoot: [{ data: preview ? { preview: true } : {} }] } }) },
      ],
    }).compileComponents();
  });

  it('loads real directory and role catalog through read APIs, showing only minimized fields', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    expect(api.users).toHaveBeenCalledWith('', '', null, 0);
    expect(api.roles).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('municipal.user');
    expect(fixture.nativeElement.textContent).toContain('INSPECTOR');
    expect(fixture.nativeElement.textContent).toContain('Pendiente de activación');
    expect(fixture.nativeElement.textContent).not.toContain('passwordHash');
    expect(fixture.nativeElement.querySelectorAll('.directory button')).toHaveLength(3);
  });

  it('sends server filters and keeps pagination bounded to returned pages', () => {
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    page.filters.setValue({ query: 'user', role: 'INSPECTOR', status: 'disabled' });
    page.loadUsers(0);
    expect(api.users).toHaveBeenLastCalledWith('user', 'INSPECTOR', false, 0);
    expect(page.hasPrevious()).toBe(false);
    expect(page.hasNext()).toBe(false);
  });

  it('shows the database role catalog without presenting it as an editable permission matrix', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    fixture.componentInstance.setTab('roles');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Rol del personal de inspección');
    expect(fixture.nativeElement.textContent).toContain('Las autorizaciones se definen y aplican en el backend');
    expect(fixture.nativeElement.querySelector('button[aria-selected="true"]')?.textContent).toContain('Catálogo');
    expect(fixture.nativeElement.querySelectorAll('.role-catalog button')).toHaveLength(1);
  });

  it('presents empty and error states and supports a deliberate retry', () => {
    api.users.mockReturnValueOnce(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    expect(fixture.componentInstance.usersState()).toBe('error');
    expect(fixture.nativeElement.textContent).toContain('No se pudo cargar el directorio');
    api.users.mockReturnValueOnce(of({ ...accountPage, content: [], totalElements: 0, totalPages: 0 }));
    fixture.componentInstance.loadUsers();
    fixture.detectChanges();
    expect(fixture.componentInstance.usersState()).toBe('empty');
    expect(fixture.nativeElement.textContent).toContain('Sin cuentas para estos filtros');
  });

  it('keeps local preview disconnected and contains no sample account or role rows', () => {
    preview = true;
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    expect(api.users).not.toHaveBeenCalled();
    expect(api.roles).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Previsualización sin datos');
  });
});
