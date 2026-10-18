import { DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { DestroyRef, Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { PageHeading } from '../../shared/ui/page-heading';
import { DataState } from '../../shared/ui/data-state';
import { PortalError } from '../../core/http/portal-error';
import { InspectorCreated, InspectorDirectory, InspectorDirectoryItem, MunicipalPersonnelApi } from './municipal-personnel-api';

@Component({
  selector: 'app-personnel',
  imports: [ReactiveFormsModule, PageHeading, DataState, DatePipe],
  templateUrl: './personnel.html',
})
export class Personnel implements OnInit {
  readonly state = signal<'loading' | 'error' | 'empty' | 'ready'>('loading');
  readonly showForm = signal(false);
  readonly creating = signal(false);
  readonly errorMessage = signal('');
  readonly directoryError = signal('');
  readonly directory = signal<InspectorDirectory | null>(null);
  readonly activation = signal<InspectorCreated | null>(null);
  readonly reissuedActivation = signal<{ username: string; token: string; expiresAt: string } | null>(null);
  readonly previewMessage = signal('');
  readonly preview = inject(ActivatedRoute, { optional: true })?.snapshot.pathFromRoot.some((route) => route.data['preview'] === true) ?? false;
  private readonly api = inject(MunicipalPersonnelApi);
  private readonly builder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  readonly filters = this.builder.nonNullable.group({ search: [''], status: ['all' as 'all' | 'enabled' | 'disabled'] });
  readonly form = this.builder.nonNullable.group({
    username: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9][a-zA-Z0-9._-]{2,99}$/)]],
    firstName: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    lastName: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    phone: ['', [Validators.maxLength(30), Validators.pattern(/^[+0-9 ()-]*$/)]],
  });
  private page = 0;

  ngOnInit() {
    this.load();
    this.filters.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged((a, b) => a.search === b.search && a.status === b.status), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load(0));
  }

  openForm() {
    this.showForm.set(true);
    this.errorMessage.set('');
    this.previewMessage.set('');
  }

  closeForm() {
    if (this.creating()) return;
    this.showForm.set(false);
    this.form.reset();
    this.errorMessage.set('');
  }

  invalid(name: keyof typeof this.form.controls) {
    const control = this.form.controls[name];
    return control.touched && control.invalid;
  }

  load(page = this.page) {
    this.page = Math.max(0, page);
    this.directoryError.set('');
    if (this.preview) {
      this.directory.set(null);
      this.state.set('empty');
      return;
    }
    this.state.set('loading');
    const { search, status } = this.filters.getRawValue();
    this.api.list(search, status, this.page).subscribe({
      next: (value) => {
        this.directory.set(value);
        this.page = value.page;
        this.state.set(value.content.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.state.set('error');
        this.directoryError.set(this.message(error));
      },
    });
  }

  submit() {
    this.form.markAllAsTouched();
    if (this.creating() || this.form.invalid) return;
    if (this.preview) {
      this.previewMessage.set('Formato válido. No se creó una cuenta.');
      return;
    }
    this.creating.set(true);
    this.errorMessage.set('');
    this.reissuedActivation.set(null);
    const value = this.form.getRawValue();
    this.api.create({
      username: value.username.trim(),
      firstName: value.firstName.trim(),
      lastName: value.lastName.trim(),
      email: value.email.trim(),
      phone: value.phone.trim() || undefined,
    }).subscribe({
      next: (created) => {
        this.activation.set(created);
        this.creating.set(false);
        this.showForm.set(false);
        this.form.reset();
        this.load(0);
      },
      error: (error: unknown) => {
        this.creating.set(false);
        this.errorMessage.set(this.message(error));
      },
    });
  }

  reissue(row: InspectorDirectoryItem) {
    if (this.preview) return;
    this.reissuedActivation.set(null);
    this.api.reissueActivation(row.id).subscribe({
      next: (result) => this.reissuedActivation.set({ username: row.username, token: result.activationToken, expiresAt: result.expiresAt }),
      error: (error: unknown) => this.directoryError.set(this.message(error)),
    });
  }

  dismissActivation() {
    this.activation.set(null);
    this.reissuedActivation.set(null);
  }

  previousPage() { if (this.page > 0) this.load(this.page - 1); }
  nextPage() { if ((this.directory()?.totalPages ?? 0) > this.page + 1) this.load(this.page + 1); }
  hasPrevious() { return this.page > 0; }
  hasNext() { return this.page + 1 < (this.directory()?.totalPages ?? 0); }

  private message(error: unknown) {
    return error instanceof PortalError ? error.message : 'No se pudo completar la operación. Inténtelo nuevamente.';
  }
}
