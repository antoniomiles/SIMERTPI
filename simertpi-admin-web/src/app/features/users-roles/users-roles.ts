import { NgClass } from '@angular/common';
import { Component, DestroyRef, HostListener, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { AdminSession } from '../../core/auth/admin-session';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { AdminAccessApi, AdminAccountPage, AdminRoleSummary, AdminPermissionDefinition, AdminRoleHistoryEntry, DeliveryStatus } from './admin-access-api';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';
type AccountStatus = 'all' | 'enabled' | 'disabled';

@Component({
  selector: 'app-users-roles',
  imports: [ReactiveFormsModule, NgClass, DataState, PageHeading],
  templateUrl: './users-roles.html',
  styleUrl: './users-roles.scss',
})
export class UsersRoles implements OnInit {
  private readonly api = inject(AdminAccessApi);
  private readonly builder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly tab = signal<'users' | 'roles'>('users');
  readonly usersState = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly rolesState = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly directory = signal<AdminAccountPage | null>(null);
  readonly roleCatalog = signal<AdminRoleSummary[]>([]);
  readonly permissionCatalog = signal<AdminPermissionDefinition[]>([]);
  readonly roleDialog = signal<'create' | 'edit' | 'status' | 'detail' | 'history' | null>(null);
  readonly selectedRole = signal<AdminRoleSummary | null>(null);
  readonly roleHistory = signal<AdminRoleHistoryEntry[]>([]);
  readonly roleDialogError = signal('');
  readonly selectedAccount = signal<AdminAccountPage['content'][number] | null>(null);
  readonly operation = signal<'edit' | 'status' | 'invite' | 'activation' | 'roles' | null>(null);
  readonly identity = inject(AdminSession).identity;
  readonly saving = signal(false);
  readonly operationError = signal('');
  readonly notice = signal<{ kind: 'success' | 'error'; message: string } | null>(null);
  readonly deliveryResult = signal<{ status: DeliveryStatus; expiresAt: string } | null>(null);
  // Kept null-only for the legacy dialog template; invitation secrets are no longer returned by the API.
  readonly activationSecret = signal<{ token: string; expiresAt: string } | null>(null);
  readonly profileForm = this.builder.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    phone: ['', [Validators.maxLength(30), Validators.pattern(/^[+0-9 ()-]*$/)]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });
  readonly statusForm = this.builder.nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });
  readonly activationForm = this.builder.nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });
  readonly inviteForm = this.builder.nonNullable.group({
    username: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    phone: ['', [Validators.maxLength(30), Validators.pattern(/^[+0-9 ()-]*$/)]],
    roles: [[] as string[]],
    reason: ['', [Validators.maxLength(500)]],
  });
  readonly rolesForm = this.builder.nonNullable.group({
    roles: [[] as string[]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });
  readonly roleForm = this.builder.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]{1,49}$/)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: ['', [Validators.maxLength(500)]],
    permissions: [[] as string[]],
    reason: ['', [Validators.maxLength(500)]],
  });
  readonly roleStatusForm = this.builder.nonNullable.group({ reason: ['', [Validators.required, Validators.maxLength(500)]] });
  readonly usersError = signal('');
  readonly rolesError = signal('');
  readonly filters = this.builder.nonNullable.group({
    query: [''],
    role: [''],
    status: ['all' as AccountStatus],
  });
  private page = 0;
  private noticeTimer: ReturnType<typeof setTimeout> | undefined;

  ngOnInit() {
    if (this.preview || (!this.isSuperAdmin() && !this.isAdmin())) return;
    if (this.isSuperAdmin()) {
      this.loadRoles();
      this.loadPermissions();
    }
    this.loadUsers();
    this.filters.valueChanges
      .pipe(
        debounceTime(250),
        distinctUntilChanged(
          (a, b) => a.query === b.query && a.role === b.role && a.status === b.status,
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.loadUsers(0));
  }

  loadUsers(page = this.page) {
    if (this.preview || (!this.isSuperAdmin() && !this.isAdmin())) return;
    this.page = Math.max(0, page);
    this.usersError.set('');
    this.usersState.set('loading');
    const { query, role, status } = this.filters.getRawValue();
    const enabled = status === 'all' ? null : status === 'enabled';
    this.api.users(query, role, enabled, this.page).subscribe({
      next: (result) => {
        this.directory.set(result);
        this.page = result.page;
        this.usersState.set(result.content.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.usersError.set(normalizeError(error).message || 'No se pudo consultar el directorio.');
        this.usersState.set('error');
      },
    });
  }

  loadRoles() {
    if (this.preview || !this.isSuperAdmin()) return;
    this.rolesError.set('');
    this.rolesState.set('loading');
    this.api.roles().subscribe({
      next: (roles) => {
        this.roleCatalog.set(roles);
        this.rolesState.set(roles.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.rolesError.set(
          normalizeError(error).message || 'No se pudo consultar el catálogo de roles.',
        );
        this.rolesState.set('error');
      },
    });
  }

  loadPermissions() {
    if (this.preview || !this.isSuperAdmin()) return;
    this.api.permissions().subscribe({
      next: (result) => this.permissionCatalog.set(result.filter((permission) => permission.assignable)),
      error: (error: unknown) => this.showNotice(
        'error', normalizeError(error).message || 'No se pudieron consultar los permisos.',
      ),
    });
  }

  isSuperAdmin() {
    return this.identity()?.roles.includes('IT_ADMIN') ?? false;
  }

  isAdmin() {
    return this.identity()?.roles.includes('SIMERTPI_ADMIN') ?? false;
  }

  canManageAllAccounts() {
    return this.isSuperAdmin();
  }

  canManageInspectors() {
    return this.isAdmin() || this.isSuperAdmin();
  }

  canAssignRoles() {
    return this.isSuperAdmin();
  }

  canManageAccount(account: AdminAccountPage['content'][number]) {
    return account.roles.includes('INSPECTOR') ? this.canManageInspectors() : this.canManageAllAccounts();
  }

  canChangeStatus(account: AdminAccountPage['content'][number]) {
    return this.canManageAccount(account)
      && account.username.toLowerCase() !== this.identity()?.username.toLowerCase();
  }

  canChangeRoles(account: AdminAccountPage['content'][number]) {
    return this.canAssignRoles()
      && account.username.toLowerCase() !== this.identity()?.username.toLowerCase();
  }

  canReissueActivation(account: AdminAccountPage['content'][number]) {
    if (!account.activationPending) return false;
    if (account.roles.includes('INSPECTOR')) return this.canManageInspectors();
    return this.canManageAllAccounts()
      && account.roles.some((role) => role === 'IT_ADMIN' || role === 'SIMERTPI_ADMIN');
  }

  hasCapability(capability: string) {
    const identity = this.identity();
    if (!identity) return false;
    if (identity.permissions !== undefined) return identity.permissions.includes(capability);
    return identity.roles.some((role) => role === 'IT_ADMIN' || role === 'SIMERTPI_ADMIN');
  }

  visibleRoleOptions() {
    return this.roleCatalog().filter((role) => this.isSuperAdmin() || role.code === 'INSPECTOR');
  }

  toggleInviteRole(code: string, selected: boolean) {
    const roles = new Set(this.inviteForm.controls.roles.value);
    if (selected) roles.add(code);
    else roles.delete(code);
    this.inviteForm.controls.roles.setValue([...roles]);
  }

  toggleAssignedRole(code: string, selected: boolean) {
    const roles = new Set(this.rolesForm.controls.roles.value);
    if (selected) roles.add(code);
    else roles.delete(code);
    this.rolesForm.controls.roles.setValue([...roles]);
  }

  permissionLabel(permission: string) {
    const labels: Record<string, string> = {
      USERS_READ: 'Consultar cuentas',
      USERS_MANAGE_ALL: 'Administrar cuentas y asignar roles autorizados',
      USERS_MANAGE_INSPECTORS: 'Administrar cuentas de inspectores',
      ROLES_ASSIGN: 'Asignar y revocar roles existentes',
      CONFIGURATION_MANAGE: 'Administrar configuración',
      INSPECTIONS_REVIEW: 'Revisar inspecciones',
      AUDIT_READ: 'Consultar auditoría',
      'inspection:read': 'Consultar inspecciones',
      'inspection:write': 'Registrar inspecciones',
      'violation:read': 'Consultar posibles infracciones',
      'violation:write': 'Registrar posibles infracciones',
      'evidence:read': 'Consultar evidencias autorizadas',
      'evidence:write': 'Registrar evidencias',
      'configuration:read': 'Consultar configuración administrativa',
      'configuration:write': 'Administrar configuración',
      'user:read': 'Consultar cuentas',
      'user:write': 'Administrar cuentas',
    };
    return labels[permission] ?? permission.replace(/[:_.-]+/g, ' ');
  }

  setTab(tab: 'users' | 'roles') {
    if (tab === 'roles' && !this.canManageAllAccounts()) return;
    this.tab.set(tab);
  }

  openCreateInspector() {
    if (!this.canManageAllAccounts() && !this.canManageInspectors()) return;
    this.inviteForm.reset({ username: '', email: '', firstName: '', lastName: '', phone: '', roles: this.canAssignRoles() ? [] : ['INSPECTOR'], reason: '' });
    this.startOperation('invite');
  }

  openAssignRoles(account: AdminAccountPage['content'][number]) {
    if (!this.canChangeRoles(account)) return;
    this.selectedAccount.set(account);
    this.rolesForm.reset({ roles: [...account.roles], reason: '' });
    this.startOperation('roles');
  }

  openEdit(account: AdminAccountPage['content'][number]) {
    if (!this.canManageAccount(account)) return;
    this.selectedAccount.set(account);
    this.profileForm.reset({
      email: account.email,
      firstName: account.firstName,
      lastName: account.lastName,
      phone: account.phone ?? '',
      reason: '',
    });
    this.startOperation('edit');
  }

  openStatus(account: AdminAccountPage['content'][number]) {
    if (!this.canChangeStatus(account)) return;
    this.selectedAccount.set(account);
    this.statusForm.reset({ reason: '' });
    this.startOperation('status');
  }

  openActivation(account: AdminAccountPage['content'][number]) {
    if (!this.canReissueActivation(account)) return;
    this.selectedAccount.set(account);
    this.deliveryResult.set(null);
    this.activationForm.reset({ reason: '' });
    this.operationError.set('');
    this.startOperation('activation');
  }

  closeOperation() {
    if (this.saving()) return;
    this.operation.set(null);
    this.operationError.set('');
    this.selectedAccount.set(null);
    this.deliveryResult.set(null);
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.operation() === 'activation' && this.deliveryResult()) {
      this.clearActivationSecret();
      return;
    }
    this.closeOperation();
  }

  saveProfile() {
    const account = this.selectedAccount();
    if (!account || this.saving()) return;
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      this.operationError.set('Revisa los campos obligatorios y el motivo de modificación.');
      return;
    }
    this.saving.set(true);
    this.operationError.set('');
    this.api.updateAccount(account.id, { ...this.profileForm.getRawValue(), phone: this.profileForm.controls.phone.value || null, updatedAt: account.updatedAt }).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeOperation();
        this.showNotice('success', 'Los datos de la cuenta se actualizaron correctamente.');
        this.loadUsers();
      },
      error: (error: unknown) => this.failOperation(error, 'No se pudieron guardar los cambios de la cuenta.'),
    });
  }

  saveStatus() {
    const account = this.selectedAccount();
    if (!account || this.saving()) return;
    if (this.statusForm.invalid) {
      this.statusForm.markAllAsTouched();
      this.operationError.set('Ingresa el motivo para cambiar el estado de la cuenta.');
      return;
    }
    this.saving.set(true);
    this.operationError.set('');
    this.api.updateStatus(account.id, {
      enabled: !account.enabled,
      reason: this.statusForm.controls.reason.value,
      updatedAt: account.updatedAt,
    }).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeOperation();
        this.showNotice('success', 'El estado de la cuenta se actualizó correctamente.');
        this.loadUsers();
        this.loadRoles();
      },
      error: (error: unknown) => this.failOperation(error, 'No se pudo cambiar el estado de la cuenta.'),
    });
  }

  createInspector() {
    if (this.saving()) return;
    if (!this.canManageAllAccounts() && !this.canManageInspectors()) return;
    if (this.inviteForm.invalid) {
      this.inviteForm.markAllAsTouched();
      this.operationError.set('Revisa los datos obligatorios del inspector.');
      return;
    }
    const form = this.inviteForm.getRawValue();
    const selectedRoles = this.canAssignRoles() ? form.roles : ['INSPECTOR'];
    if (this.canAssignRoles() && !selectedRoles.length) {
      this.operationError.set('Selecciona al menos un rol existente para la cuenta.');
      return;
    }
    if (!form.reason.trim()) {
      this.inviteForm.controls.reason.markAsTouched();
      this.operationError.set('Ingresa el motivo de creación de la cuenta.');
      return;
    }
    this.saving.set(true);
    this.operationError.set('');
    this.api.createAccount({
      username: form.username, email: form.email, firstName: form.firstName, lastName: form.lastName,
      phone: form.phone || null, roles: selectedRoles, reason: form.reason.trim(),
    }).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.deliveryResult.set({ status: created.deliveryStatus, expiresAt: created.activationExpiresAt });
        this.operation.set(null);
        this.showNotice(created.deliveryStatus === 'FAILED' ? 'error' : 'success', this.deliveryMessage(created.deliveryStatus, 'La cuenta fue creada'));
        this.loadUsers(0);
        this.loadRoles();
      },
      error: (error: unknown) => this.failOperation(error, 'No se pudo crear la cuenta.'),
    });
  }

  saveRoles() {
    const account = this.selectedAccount();
    if (!account || this.saving() || !this.canAssignRoles()) return;
    if (!this.rolesForm.controls.roles.value.length) {
      this.operationError.set('La cuenta debe conservar al menos un rol existente.');
      return;
    }
    if (this.rolesForm.invalid) {
      this.rolesForm.markAllAsTouched();
      this.operationError.set('Ingresa el motivo del cambio de roles.');
      return;
    }
    this.saving.set(true);
    this.operationError.set('');
    this.api.assignRoles(account.id, {
      roles: this.rolesForm.controls.roles.value,
      reason: this.rolesForm.controls.reason.value,
      updatedAt: account.updatedAt,
    }).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeOperation();
        this.showNotice('success', 'Los roles de la cuenta se actualizaron correctamente.');
        this.loadUsers();
        this.loadRoles();
      },
      error: (error: unknown) => this.failOperation(error, 'No se pudieron actualizar los roles de la cuenta.'),
    });
  }

  reissueActivation() {
    const account = this.selectedAccount();
    if (!account || this.saving() || !this.canReissueActivation(account)) return;
    if (!account.roles.includes('INSPECTOR') && this.activationForm.invalid) {
      this.activationForm.markAllAsTouched();
      this.operationError.set('Ingresa el motivo para reemitir la invitación administrativa.');
      return;
    }
    this.saving.set(true);
    this.operationError.set('');
    const result = account.roles.includes('INSPECTOR')
      ? this.api.reissueInspectorActivation(account.id)
      : this.api.reissueAdminActivation(account.id, {
          reason: this.activationForm.controls.reason.value.trim(),
          updatedAt: account.updatedAt,
        });
    result.subscribe({
      next: (issued) => {
        this.saving.set(false);
        this.deliveryResult.set({ status: issued.deliveryStatus, expiresAt: issued.activationExpiresAt });
        this.operation.set(null);
        this.showNotice(issued.deliveryStatus === 'FAILED' ? 'error' : 'success', this.deliveryMessage(issued.deliveryStatus, 'Se registró una nueva invitación'));
      },
      error: (error: unknown) => this.failOperation(error, 'No se pudo emitir el enlace de activación.'),
    });
  }

  clearActivationSecret() {
    this.deliveryResult.set(null);
    this.closeOperation();
  }

  deliveryMessage(status: DeliveryStatus, action: string) {
    if (status === 'ACCEPTED_BY_PROVIDER') return `${action}. El proveedor aceptó el mensaje; esto no confirma su entrega.`;
    if (status === 'UNCONFIGURED') return `${action}, pero el correo no se envió porque el proveedor no está configurado.`;
    return `${action}, pero falló el envío del correo. Reintenta la invitación.`;
  }

  openRoleCreate() {
    if (!this.isSuperAdmin()) return;
    this.selectedRole.set(null);
    this.roleForm.reset({ code: '', name: '', description: '', permissions: [], reason: '' });
    this.roleForm.controls.reason.clearValidators();
    this.roleForm.controls.reason.updateValueAndValidity();
    this.roleDialogError.set('');
    this.roleDialog.set('create');
  }

  openRoleEdit(role: AdminRoleSummary) {
    if (!this.isSuperAdmin() || role.protectedRole) return;
    this.selectedRole.set(role);
    this.roleForm.reset({ code: role.code, name: role.name, description: role.description ?? '', permissions: [...role.permissions], reason: '' });
    this.roleForm.controls.reason.setValidators([Validators.required, Validators.maxLength(500)]);
    this.roleForm.controls.reason.updateValueAndValidity();
    this.roleDialogError.set('');
    this.roleDialog.set('edit');
  }

  openRoleStatus(role: AdminRoleSummary) {
    if (!this.isSuperAdmin() || role.protectedRole || role.userCount > 0) return;
    this.selectedRole.set(role);
    this.roleStatusForm.reset({ reason: '' });
    this.roleDialogError.set('');
    this.roleDialog.set('status');
  }

  openRoleDetail(role: AdminRoleSummary) {
    if (!this.isSuperAdmin()) return;
    this.selectedRole.set(role);
    this.roleDialogError.set('');
    this.roleDialog.set('detail');
    this.api.role(role.code).subscribe({ next: value => this.selectedRole.set(value), error: error => this.roleDialogError.set(normalizeError(error).message) });
  }

  openRoleHistory(role: AdminRoleSummary) {
    if (!this.isSuperAdmin()) return;
    this.selectedRole.set(role);
    this.roleHistory.set([]);
    this.roleDialogError.set('');
    this.roleDialog.set('history');
    this.api.roleHistory(role.code).subscribe({ next: value => this.roleHistory.set(value), error: error => this.roleDialogError.set(normalizeError(error).message) });
  }

  toggleRolePermission(code: string, selected: boolean) {
    const permissions = new Set(this.roleForm.controls.permissions.value);
    if (selected) permissions.add(code); else permissions.delete(code);
    this.roleForm.controls.permissions.setValue([...permissions]);
  }

  saveRole() {
    if (!this.isSuperAdmin() || this.saving()) return;
    this.roleForm.markAllAsTouched();
    if (this.roleForm.invalid || (this.selectedRole() && !this.roleForm.controls.reason.value.trim())) { this.roleDialogError.set('Completa el código, nombre y motivo del cambio.'); return; }
    const value = this.roleForm.getRawValue();
    const existing = this.selectedRole();
    this.saving.set(true); this.roleDialogError.set('');
    const request = { name: value.name.trim(), description: value.description.trim(), permissions: value.permissions };
    const mutation = existing
      ? this.api.updateRole(existing.code, { ...request, updatedAt: existing.updatedAt, rowVersion: existing.rowVersion, reason: value.reason.trim() })
      : this.api.createRole({ ...request, code: value.code.trim() });
    mutation.subscribe({
      next: () => { this.saving.set(false); this.roleDialog.set(null); this.showNotice('success', existing ? 'El rol se actualizó correctamente.' : 'El rol se creó correctamente.'); this.loadRoles(); this.loadPermissions(); },
      error: error => { this.saving.set(false); this.roleDialogError.set(normalizeError(error).message || 'No se pudo guardar el rol.'); },
    });
  }

  saveRoleStatus() {
    const role = this.selectedRole();
    if (!role || role.protectedRole || role.userCount > 0 || this.saving()) return;
    this.roleStatusForm.markAllAsTouched();
    if (this.roleStatusForm.invalid) { this.roleDialogError.set('Indica el motivo del cambio de estado.'); return; }
    this.saving.set(true); this.roleDialogError.set('');
    this.api.updateRoleStatus(role.code, { enabled: !role.enabled, updatedAt: role.updatedAt, rowVersion: role.rowVersion, reason: this.roleStatusForm.controls.reason.value.trim() }).subscribe({
      next: () => { this.saving.set(false); this.roleDialog.set(null); this.showNotice('success', `El rol se ${role.enabled ? 'desactivó' : 'activó'} correctamente.`); this.loadRoles(); },
      error: error => { this.saving.set(false); this.roleDialogError.set(normalizeError(error).message || 'No se pudo cambiar el estado del rol.'); },
    });
  }

  closeRoleDialog() { if (!this.saving()) { this.roleDialog.set(null); this.selectedRole.set(null); this.roleDialogError.set(''); } }

  private startOperation(operation: 'edit' | 'status' | 'invite' | 'activation' | 'roles') {
    this.operationError.set('');
    this.operation.set(operation);
  }

  private failOperation(error: unknown, fallback: string) {
    this.saving.set(false);
    this.operationError.set(normalizeError(error).message || fallback);
    this.showNotice('error', this.operationError());
  }

  private showNotice(kind: 'success' | 'error', message: string) {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.notice.set({ kind, message });
    this.noticeTimer = setTimeout(() => this.notice.set(null), 5000);
  }

  dismissNotice() {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.notice.set(null);
  }

  clearFilters() {
    this.filters.reset({ query: '', role: '', status: 'all' });
    this.loadUsers(0);
  }

  roleName(code: string) {
    const role = this.roleCatalog().find((value) => value.code === code);
    return role?.displayName || role?.name || code;
  }

  accountStatus(enabled: boolean, activationPending: boolean) {
    if (activationPending) return 'Pendiente de activación';
    return enabled ? 'Habilitada' : 'Deshabilitada';
  }

  accountStatusClass(enabled: boolean, activationPending: boolean) {
    if (activationPending) return 'pending';
    return enabled ? 'enabled' : 'disabled';
  }

  formatCreatedAt(value: string) {
    return new Intl.DateTimeFormat('es-EC', {
      dateStyle: 'medium',
      timeZone: 'America/Guayaquil',
    }).format(new Date(value));
  }

  formatTimestamp(value: string) {
    return new Intl.DateTimeFormat('es-EC', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Guayaquil' }).format(new Date(value));
  }

  formatHistoryMetadata(metadata: Record<string, unknown>) {
    return JSON.stringify(metadata, null, 2);
  }

  enabledOnPage() {
    return (
      this.directory()?.content.filter((account) => account.enabled && !account.activationPending)
        .length ?? 0
    );
  }

  pendingOnPage() {
    return this.directory()?.content.filter((account) => account.activationPending).length ?? 0;
  }

  assignedAccountCount() {
    return this.roleCatalog().reduce((total, role) => total + role.userCount, 0);
  }

  previousPage() {
    if (this.page > 0) this.loadUsers(this.page - 1);
  }

  nextPage() {
    if ((this.directory()?.totalPages ?? 0) > this.page + 1) this.loadUsers(this.page + 1);
  }

  ngOnDestroy() {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.deliveryResult.set(null);
  }

  hasPrevious() {
    return this.page > 0;
  }

  hasNext() {
    return this.page + 1 < (this.directory()?.totalPages ?? 0);
  }
}
