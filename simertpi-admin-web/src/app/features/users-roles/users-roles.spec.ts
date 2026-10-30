import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { AdminAccessApi, AdminAccountPage, AdminRoleSummary } from './admin-access-api';
import { UsersRoles } from './users-roles';
import { AdminSession } from '../../core/auth/admin-session';

const accountPage: AdminAccountPage = {
  content: [
    {
      id: 'account-1',
      username: 'municipal.user',
      email: 'user@example.invalid',
      firstName: 'Test',
      lastName: 'User',
      phone: null,
      roles: ['INSPECTOR'],
      enabled: false,
      activationPending: true,
      createdAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:00:00Z',
    },
  ],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
};
const roleCatalog: AdminRoleSummary[] = [
  {
    code: 'IT_ADMIN',
    name: 'IT_ADMIN',
    displayName: 'Superadministrador',
    description: 'Administración integral',
    assignedAccounts: 1,
    enabled: true, protectedRole: true, portalEnabled: true, rowVersion: 0, userCount: 1, permissions: [], createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
  },
  {
    code: 'SIMERTPI_ADMIN',
    name: 'SIMERTPI_ADMIN',
    displayName: 'Administrador',
    description: 'Administración municipal',
    assignedAccounts: 1,
    enabled: true, protectedRole: true, portalEnabled: true, rowVersion: 0, userCount: 1, permissions: [], createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
  },
  {
    code: 'INSPECTOR',
    name: 'Inspector municipal',
    description: 'Rol del personal de inspección',
    assignedAccounts: 1,
    enabled: true, protectedRole: true, portalEnabled: true, rowVersion: 0, userCount: 1, permissions: ['inspections.view'], createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z',
  },
];

describe('Users and roles administration', () => {
  let api: {
    users: ReturnType<typeof vi.fn>;
    roles: ReturnType<typeof vi.fn>;
    updateAccount: ReturnType<typeof vi.fn>;
    updateStatus: ReturnType<typeof vi.fn>;
    createInspector: ReturnType<typeof vi.fn>;
    reissueInspectorActivation: ReturnType<typeof vi.fn>;
    reissueAdminActivation: ReturnType<typeof vi.fn>;
    createAccount: ReturnType<typeof vi.fn>;
    assignRoles: ReturnType<typeof vi.fn>;
    permissions: ReturnType<typeof vi.fn>;
    role: ReturnType<typeof vi.fn>;
    roleHistory: ReturnType<typeof vi.fn>;
    createRole: ReturnType<typeof vi.fn>;
    updateRole: ReturnType<typeof vi.fn>;
    updateRoleStatus: ReturnType<typeof vi.fn>;
  };
  let preview = false;
  let activeRoles: string[] = ['IT_ADMIN'];
  const permissionResponse = [{ code: 'dashboard.view', moduleCode: 'dashboard', actionCode: 'view', name: 'Ver panel', description: 'Consulta', assignable: true }];

  beforeEach(async () => {
    preview = false;
    activeRoles = ['IT_ADMIN'];
    api = {
      users: vi.fn().mockReturnValue(of(accountPage)),
      roles: vi.fn().mockReturnValue(of(roleCatalog)),
      updateAccount: vi.fn().mockReturnValue(of(accountPage.content[0])),
      updateStatus: vi.fn().mockReturnValue(of(accountPage.content[0])),
      createInspector: vi.fn().mockReturnValue(of({ ...accountPage.content[0], deliveryStatus: 'ACCEPTED_BY_PROVIDER', activationExpiresAt: '2026-10-30T00:00:00Z' })),
      reissueInspectorActivation: vi.fn().mockReturnValue(of({ deliveryStatus: 'ACCEPTED_BY_PROVIDER', activationExpiresAt: '2026-10-30T00:00:00Z' })),
      reissueAdminActivation: vi.fn().mockReturnValue(of({ deliveryStatus: 'ACCEPTED_BY_PROVIDER', activationExpiresAt: '2026-10-30T00:00:00Z' })),
      createAccount: vi.fn().mockReturnValue(of({ account: accountPage.content[0], deliveryStatus: 'ACCEPTED_BY_PROVIDER', activationExpiresAt: '2026-10-30T00:00:00Z' })),
      assignRoles: vi.fn().mockReturnValue(of(accountPage.content[0])),
      permissions: vi.fn().mockReturnValue(of(permissionResponse)),
      role: vi.fn().mockReturnValue(of(roleCatalog[0])),
      roleHistory: vi.fn().mockReturnValue(of([])),
      createRole: vi.fn().mockReturnValue(of(roleCatalog[0])),
      updateRole: vi.fn().mockReturnValue(of(roleCatalog[0])),
      updateRoleStatus: vi.fn().mockReturnValue(of(roleCatalog[0])),
    };
    await TestBed.configureTestingModule({
      imports: [UsersRoles],
      providers: [
        { provide: AdminAccessApi, useValue: api },
        { provide: AdminSession, useValue: { identity: () => ({ userId: 'admin-1', username: 'admin', roles: activeRoles, enabled: true, sessionExpiresAt: '2026-10-29T00:00:00Z', absoluteExpiresAt: '2026-10-29T00:00:00Z' }) } },
        {
          provide: ActivatedRoute,
          useFactory: () => ({
            snapshot: { pathFromRoot: [{ data: preview ? { preview: true } : {} }] },
          }),
        },
      ],
    }).compileComponents();
  });

  it('loads real directory and role catalog through read APIs, showing only minimized fields', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    expect(api.users).toHaveBeenCalledWith('', '', null, 0);
    expect(api.roles).toHaveBeenCalledOnce();
    expect(api.permissions).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('municipal.user');
    expect(fixture.nativeElement.textContent).toContain('Inspector municipal');
    expect(fixture.nativeElement.textContent).toContain('Pendiente de activación');
    expect(fixture.nativeElement.textContent).not.toContain('passwordHash');
    expect(
      fixture.nativeElement.querySelectorAll('#accounts-panel .section-heading button'),
    ).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.status.pending')?.textContent).toContain(
      'Pendiente de activación',
    );
    expect(fixture.nativeElement.textContent).toContain('Cuentas encontradas');
  });

  it('creates a custom role with only selected assignable permissions', () => {
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    page.openRoleCreate();
    page.roleForm.setValue({ code: 'SECRETARIA', name: 'Secretaría', description: 'Consulta autorizada', permissions: ['dashboard.view'], reason: '' });
    page.saveRole();
    expect(api.createRole).toHaveBeenCalledWith({ code: 'SECRETARIA', name: 'Secretaría', description: 'Consulta autorizada', permissions: ['dashboard.view'] });
    expect(page.roleDialog()).toBeNull();
    expect(page.notice()?.kind).toBe('success');
  });

  it('updates a custom role with optimistic concurrency and a required reason', () => {
    const custom: AdminRoleSummary = { code: 'SECRETARIA', name: 'Secretaría', description: 'Consulta', enabled: true, protectedRole: false, portalEnabled: true, rowVersion: 3, userCount: 0, permissions: ['dashboard.view'], createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z' };
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    page.openRoleEdit(custom);
    page.roleForm.setValue({ code: 'SECRETARIA', name: 'Secretaría', description: 'Consulta actualizada', permissions: ['dashboard.view'], reason: 'Ajuste autorizado' });
    page.saveRole();
    expect(api.updateRole).toHaveBeenCalledWith('SECRETARIA', expect.objectContaining({ updatedAt: custom.updatedAt, rowVersion: 3, reason: 'Ajuste autorizado', permissions: ['dashboard.view'] }));
  });

  it('prevents editing protected roles and loads role history through the read API', () => {
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    page.openRoleEdit(roleCatalog[0]);
    expect(page.roleDialog()).toBeNull();
    page.openRoleHistory(roleCatalog[2]);
    expect(api.roleHistory).toHaveBeenCalledWith('INSPECTOR');
    expect(page.roleDialog()).toBe('history');
  });

  it('sends server filters and keeps pagination bounded to returned pages', () => {
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    page.filters.setValue({ query: 'user', role: 'INSPECTOR', status: 'disabled' });
    page.loadUsers(0);
    expect(api.users).toHaveBeenLastCalledWith('user', 'INSPECTOR', false, 0);
    expect(page.hasPrevious()).toBe(false);
    expect(page.hasNext()).toBe(false);
  });

  it('shows the server permission catalog and role-management actions to Superadministrador', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    fixture.componentInstance.setTab('roles');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Rol del personal de inspección');
    expect(fixture.nativeElement.textContent).toContain(
      'Los roles y permisos se aplican en el backend',
    );
    expect(
      fixture.nativeElement.querySelector('button[aria-selected="true"]')?.textContent,
    ).toContain('Catálogo');
    expect(
      fixture.nativeElement.querySelectorAll('#roles-panel .section-heading button'),
    ).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('Roles registrados');
    expect(fixture.nativeElement.textContent).toContain('Crear rol personalizado');
    expect(fixture.nativeElement.textContent).toContain('El backend vuelve a validar autorización');
  });

  it('clears search, role and status filters and requests the first page', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    fixture.componentInstance.filters.setValue({
      query: 'persona',
      role: 'INSPECTOR',
      status: 'disabled',
    });
    fixture.componentInstance.clearFilters();
    expect(fixture.componentInstance.filters.getRawValue()).toEqual({
      query: '',
      role: '',
      status: 'all',
    });
    expect(api.users).toHaveBeenLastCalledWith('', '', null, 0);
  });

  it('distinguishes enabled, pending and disabled accounts', () => {
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    expect(page.accountStatusClass(true, false)).toBe('enabled');
    expect(page.accountStatusClass(false, true)).toBe('pending');
    expect(page.accountStatusClass(false, false)).toBe('disabled');
  });

  it('updates account profile only after a successful response and refreshes the directory', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEdit(accountPage.content[0]);
    page.profileForm.setValue({ email: 'changed@example.invalid', firstName: 'Changed', lastName: 'Name', phone: '', reason: 'Corrección administrativa' });
    page.saveProfile();
    expect(api.updateAccount).toHaveBeenCalledWith('account-1', expect.objectContaining({
      email: 'changed@example.invalid', updatedAt: '2026-10-18T10:00:00Z', reason: 'Corrección administrativa', phone: null,
    }));
    expect(page.operation()).toBeNull();
    expect(page.notice()?.kind).toBe('success');
    expect(api.users).toHaveBeenCalledTimes(2);
  });

  it('keeps the edit dialog open and reports backend rejection', () => {
    api.updateAccount.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'STALE_VERSION' } })));
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEdit(accountPage.content[0]);
    page.profileForm.setValue({ email: 'changed@example.invalid', firstName: 'Changed', lastName: 'Name', phone: '', reason: 'Corrección administrativa' });
    page.saveProfile();
    expect(page.operation()).toBe('edit');
    expect(page.saving()).toBe(false);
    expect(page.operationError()).toContain('conflicto');
  });

  it('creates an inspector through the invitation service and displays delivery status without a token', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreateInspector();
    page.inviteForm.setValue({ username: 'new.inspector', email: 'new@example.invalid', firstName: 'New', lastName: 'Inspector', phone: '', roles: ['INSPECTOR'], reason: 'Alta autorizada' });
    page.createInspector();
    expect(api.createAccount).toHaveBeenCalledWith(expect.objectContaining({ username: 'new.inspector', phone: null, roles: ['INSPECTOR'], reason: 'Alta autorizada' }));
    expect(api.createInspector).not.toHaveBeenCalled();
    expect(page.operation()).toBeNull();
    expect(page.deliveryResult()?.status).toBe('ACCEPTED_BY_PROVIDER');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('proveedor aceptó');
    expect(fixture.nativeElement.textContent).not.toContain('one-time');
    expect(fixture.nativeElement.textContent).not.toContain('token de activación');
  });

  it('allows SIMERTPI_ADMIN to manage only inspectors without loading role or permission catalogs', () => {
    activeRoles = ['SIMERTPI_ADMIN'];
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(api.users).toHaveBeenCalledOnce();
    expect(api.roles).not.toHaveBeenCalled();
    expect(api.permissions).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).not.toContain('Catálogo de roles');
    expect(fixture.nativeElement.textContent).toContain('Crear inspector');
    expect(page.canManageInspectors()).toBe(true);
    expect(page.canManageAllAccounts()).toBe(false);
    expect(page.canAssignRoles()).toBe(false);
    page.openCreateInspector();
    expect(page.inviteForm.controls.roles.value).toEqual(['INSPECTOR']);
  });

  it('creates an inspector for SIMERTPI_ADMIN with a fixed existing role and mandatory reason', () => {
    activeRoles = ['SIMERTPI_ADMIN'];
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreateInspector();
    page.inviteForm.setValue({ username: 'new.inspector', email: 'new@example.invalid', firstName: 'New', lastName: 'Inspector', phone: '', roles: ['INSPECTOR'], reason: 'Alta de inspector' });
    page.createInspector();
    expect(api.createAccount).toHaveBeenCalledWith({
      username: 'new.inspector', email: 'new@example.invalid', firstName: 'New', lastName: 'Inspector',
      phone: null, roles: ['INSPECTOR'], reason: 'Alta de inspector',
    });
    expect(page.deliveryResult()?.status).toBe('ACCEPTED_BY_PROVIDER');
    expect(page.notice()?.kind).toBe('success');
  });

  it('reissues a pending administrative invitation only as Superadministrador and requires a reason', () => {
    const pendingAdmin = {
      ...accountPage.content[0], id: 'pending-admin', username: 'pending.admin', roles: ['SIMERTPI_ADMIN'],
      activationPending: true, enabled: false,
    };
    api.users.mockReturnValue(of({ ...accountPage, content: [pendingAdmin] }));
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.canReissueActivation(pendingAdmin)).toBe(true);
    page.openActivation(pendingAdmin);
    page.reissueActivation();
    expect(api.reissueAdminActivation).not.toHaveBeenCalled();
    expect(page.operationError()).toContain('motivo');
    page.activationForm.controls.reason.setValue('Enlace vencido');
    page.reissueActivation();
    expect(api.reissueAdminActivation).toHaveBeenCalledWith('pending-admin', {
      reason: 'Enlace vencido', updatedAt: pendingAdmin.updatedAt,
    });
    expect(page.deliveryResult()?.status).toBe('ACCEPTED_BY_PROVIDER');
    expect(fixture.nativeElement.textContent).not.toContain('admin-token');
    expect(page.notice()?.kind).toBe('success');
    activeRoles = ['SIMERTPI_ADMIN'];
    const municipal = TestBed.createComponent(UsersRoles).componentInstance;
    expect(municipal.canReissueActivation(pendingAdmin)).toBe(false);
  });

  it('does not offer self role assignment or self disabling controls', () => {
    const self = { ...accountPage.content[0], username: 'admin', roles: ['IT_ADMIN'], enabled: true, activationPending: false };
    api.users.mockReturnValue(of({ ...accountPage, content: [self] }));
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.canManageAccount(self)).toBe(true);
    expect(page.canChangeStatus(self)).toBe(false);
    expect(page.canChangeRoles(self)).toBe(false);
    page.openStatus(self);
    page.openAssignRoles(self);
    expect(page.operation()).toBeNull();
    const actions = fixture.nativeElement.querySelector('.row-actions')?.textContent ?? '';
    expect(actions).toContain('Editar');
    expect(actions).not.toContain('Deshabilitar');
    expect(actions).not.toContain('Roles');
  });

  it('assigns existing roles with reason and optimistic updatedAt, then refreshes', () => {
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openAssignRoles(accountPage.content[0]);
    page.rolesForm.setValue({ roles: ['INSPECTOR'], reason: 'Asignación autorizada' });
    page.saveRoles();
    expect(api.assignRoles).toHaveBeenCalledWith('account-1', {
      roles: ['INSPECTOR'], reason: 'Asignación autorizada', updatedAt: accountPage.content[0].updatedAt,
    });
    expect(page.notice()?.kind).toBe('success');
    expect(page.operation()).toBeNull();
  });

  it('keeps account creation dialog open and does not show success when the backend rejects it', () => {
    activeRoles = ['SIMERTPI_ADMIN'];
    api.createAccount.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'USERNAME_EXISTS' } })));
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreateInspector();
    page.inviteForm.setValue({ username: 'new.inspector', email: 'new@example.invalid', firstName: 'New', lastName: 'Inspector', phone: '', roles: ['INSPECTOR'], reason: 'Alta autorizada' });
    page.createInspector();
    expect(page.operation()).toBe('invite');
    expect(page.activationSecret()).toBeNull();
    expect(page.notice()?.kind).toBe('error');
  });

  it('formats account dates in Ecuador rather than the browser timezone', () => {
    const page = TestBed.createComponent(UsersRoles).componentInstance;
    expect(page.formatCreatedAt('2026-10-18T04:30:00Z')).toBe('17 oct 2026');
  });

  it('presents empty and error states and supports a deliberate retry', () => {
    api.users.mockReturnValueOnce(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(UsersRoles);
    fixture.detectChanges();
    expect(fixture.componentInstance.usersState()).toBe('error');
    expect(fixture.nativeElement.textContent).toContain('No se pudo cargar el directorio');
    api.users.mockReturnValueOnce(
      of({ ...accountPage, content: [], totalElements: 0, totalPages: 0 }),
    );
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
