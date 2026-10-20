import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { AdminAccessApi, AdminAccountPage, AdminRoleSummary } from './admin-access-api';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';
type AccountStatus = 'all' | 'enabled' | 'disabled';

@Component({
  selector: 'app-users-roles',
  imports: [ReactiveFormsModule, DatePipe, DataState, PageHeading],
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
  readonly usersError = signal('');
  readonly rolesError = signal('');
  readonly filters = this.builder.nonNullable.group({
    query: [''],
    role: [''],
    status: ['all' as AccountStatus],
  });
  private page = 0;

  ngOnInit() {
    if (this.preview) return;
    this.loadRoles();
    this.loadUsers();
    this.filters.valueChanges
      .pipe(
        debounceTime(250),
        distinctUntilChanged((a, b) =>
          a.query === b.query && a.role === b.role && a.status === b.status,
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.loadUsers(0));
  }

  loadUsers(page = this.page) {
    if (this.preview) return;
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
    if (this.preview) return;
    this.rolesError.set('');
    this.rolesState.set('loading');
    this.api.roles().subscribe({
      next: (roles) => {
        this.roleCatalog.set(roles);
        this.rolesState.set(roles.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.rolesError.set(normalizeError(error).message || 'No se pudo consultar el catálogo de roles.');
        this.rolesState.set('error');
      },
    });
  }

  setTab(tab: 'users' | 'roles') {
    this.tab.set(tab);
  }

  roleName(code: string) {
    return this.roleCatalog().find((role) => role.code === code)?.name ?? code;
  }

  accountStatus(enabled: boolean, activationPending: boolean) {
    if (activationPending) return 'Pendiente de activación';
    return enabled ? 'Habilitada' : 'Deshabilitada';
  }

  previousPage() {
    if (this.page > 0) this.loadUsers(this.page - 1);
  }

  nextPage() {
    if ((this.directory()?.totalPages ?? 0) > this.page + 1) this.loadUsers(this.page + 1);
  }

  hasPrevious() {
    return this.page > 0;
  }

  hasNext() {
    return this.page + 1 < (this.directory()?.totalPages ?? 0);
  }
}
