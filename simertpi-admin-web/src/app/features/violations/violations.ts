import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { AdminViolation, AdminViolationPage, ViolationStatus, ViolationsApi } from './violations-api';

const PAGE_SIZE = 25;
const MAX_RANGE_DAYS = 366;
type ViewState = 'loading' | 'error' | 'empty' | 'ready';
type DetailState = 'idle' | 'loading' | 'error';
const STATUS_LABELS: Record<ViolationStatus, string> = {
  OPEN: 'Abierta',
  NOTIFIED: 'Notificada',
  PAID: 'Marcada como pagada',
  CANCELLED: 'Cancelada',
  DISPUTED: 'En disputa',
};

@Component({
  selector: 'app-violations',
  imports: [DatePipe, DataState, PageHeading, ReactiveFormsModule],
  templateUrl: './violations.html',
  styleUrl: './violations.scss',
})
export class Violations {
  private readonly api = inject(ViolationsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly result = signal<AdminViolationPage | null>(null);
  readonly errorMessage = signal('');
  readonly page = signal(0);
  readonly pageCount = computed(() => this.result()?.totalPages ?? 0);
  readonly selected = signal<AdminViolation | null>(null);
  readonly detailState = signal<DetailState>('idle');
  readonly detailError = signal('');
  readonly filters = this.builder.nonNullable.group({
    q: ['', [Validators.maxLength(100)]],
    plate: ['', [Validators.maxLength(10)]],
    zone: ['', [Validators.maxLength(100)]],
    status: [''],
    from: [''],
    to: [''],
  });

  constructor() {
    if (!this.preview) this.load();
  }

  load(page = this.page()) {
    if (this.preview || this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const values = this.filters.getRawValue();
    const range = values.from && values.to
      ? (Date.parse(`${values.to}T00:00:00Z`) - Date.parse(`${values.from}T00:00:00Z`)) / 86_400_000
      : 0;
    if ((values.from && values.to && (values.from > values.to || range >= MAX_RANGE_DAYS))) {
      this.errorMessage.set('El rango debe ser válido y no superar 366 días.');
      this.state.set('error');
      return;
    }
    this.page.set(Math.max(0, page));
    this.errorMessage.set('');
    this.selected.set(null);
    this.state.set('loading');
    this.api.search({
      page: this.page(),
      size: PAGE_SIZE,
      q: values.q.trim() || undefined,
      plate: values.plate.trim() || undefined,
      zone: values.zone.trim() || undefined,
      status: (values.status || undefined) as ViolationStatus | undefined,
      from: values.from || undefined,
      to: values.to || undefined,
    }).subscribe({
      next: result => {
        this.result.set(result);
        this.state.set(result.items.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.errorMessage.set(normalizeError(error).message || 'No se pudieron consultar las infracciones.');
        this.state.set('error');
      },
    });
  }

  applyFilters() {
    this.load(0);
  }

  clearFilters() {
    this.filters.reset({ q: '', plate: '', zone: '', status: '', from: '', to: '' });
    this.load(0);
  }

  openDetail(row: AdminViolation) {
    this.selected.set(row);
    this.detailState.set('loading');
    this.detailError.set('');
    this.api.detail(row.id).subscribe({
      next: detail => {
        this.selected.set(detail);
        this.detailState.set('idle');
      },
      error: (error: unknown) => {
        this.detailError.set(normalizeError(error).message || 'No se pudo cargar el detalle.');
        this.detailState.set('error');
      },
    });
  }

  closeDetail() {
    this.selected.set(null);
    this.detailState.set('idle');
    this.detailError.set('');
  }

  previous() {
    if (this.page() > 0) this.load(this.page() - 1);
  }

  next() {
    if (this.page() + 1 < this.pageCount()) this.load(this.page() + 1);
  }

  statusLabel(status: ViolationStatus) {
    return STATUS_LABELS[status] ?? status;
  }

  location(row: AdminViolation) {
    return `${row.zoneCode} · ${row.zoneName} / ${row.streetCode} · ${row.streetName}`;
  }
}
