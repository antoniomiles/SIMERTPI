import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuditApi, AuditRecord } from './audit-api';
import { PortalError, normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';

const PAGE_SIZE = 50;

@Component({
  selector: 'app-audit',
  imports: [ReactiveFormsModule, DatePipe, DataState, PageHeading],
  templateUrl: './audit.html',
  styleUrl: './audit.scss',
})
export class Audit {
  private readonly api = inject(AuditApi);
  private readonly builder = inject(FormBuilder);
  readonly state = signal<'loading' | 'error' | 'empty' | 'ready'>('loading');
  readonly rows = signal<AuditRecord[]>([]);
  readonly errorMessage = signal('');
  readonly offset = signal(0);
  readonly hasNext = signal(false);
  readonly filters = this.builder.nonNullable.group({
    action: ['', [Validators.maxLength(120)]],
    resourceType: ['', [Validators.maxLength(80)]],
    result: [''],
    correlationId: ['', [Validators.maxLength(128)]],
    from: [''],
    to: [''],
  });

  constructor() {
    this.load();
  }

  load(offset = 0) {
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const values = this.filters.getRawValue();
    if (values.from && values.to && new Date(values.from) > new Date(values.to)) {
      this.errorMessage.set('El inicio del período debe ser anterior al final.');
      this.state.set('error');
      return;
    }
    this.offset.set(Math.max(0, offset));
    this.errorMessage.set('');
    this.state.set('loading');
    this.api
      .search({
        action: values.action,
        resourceType: values.resourceType,
        result: values.result || undefined,
        correlationId: values.correlationId,
        from: this.toIso(values.from),
        to: this.toIso(values.to),
        offset: this.offset(),
        limit: PAGE_SIZE + 1,
      })
      .subscribe({
        next: (rows) => {
          this.rows.set(rows.slice(0, PAGE_SIZE));
          this.hasNext.set(rows.length > PAGE_SIZE);
          this.state.set(rows.length ? 'ready' : 'empty');
        },
        error: (error: unknown) => {
          const normalized = normalizeError(error);
          this.errorMessage.set(
            normalized instanceof PortalError
              ? normalized.message
              : 'No se pudo consultar la auditoría.',
          );
          this.state.set('error');
        },
      });
  }

  applyFilters() {
    this.load(0);
  }

  clearFilters() {
    this.filters.reset({
      action: '',
      resourceType: '',
      result: '',
      correlationId: '',
      from: '',
      to: '',
    });
    this.load(0);
  }

  previous() {
    if (this.offset() > 0) this.load(this.offset() - PAGE_SIZE);
  }

  next() {
    if (this.hasNext()) this.load(this.offset() + PAGE_SIZE);
  }

  private toIso(value: string): string | undefined {
    if (!value) return undefined;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
  }
}
