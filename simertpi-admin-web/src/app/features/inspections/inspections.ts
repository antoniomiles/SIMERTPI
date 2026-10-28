import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import {
  AdminInspection,
  AdminInspectionDetail,
  AdminInspectionPage,
  InspectionsApi,
} from './inspections-api';

const PAGE_SIZE = 25;
const MAX_RANGE_DAYS = 366;
type State = 'loading' | 'error' | 'empty' | 'ready';

@Component({
  selector: 'app-inspections',
  imports: [DataState, PageHeading, ReactiveFormsModule],
  templateUrl: './inspections.html',
  styleUrl: './inspections.scss',
})
export class Inspections {
  private readonly api = inject(InspectionsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly state = signal<State>(this.preview ? 'empty' : 'loading');
  readonly result = signal<AdminInspectionPage | null>(null);
  readonly errorMessage = signal('');
  readonly detailError = signal('');
  readonly selected = signal<AdminInspection | null>(null);
  readonly detail = signal<AdminInspectionDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly page = signal(0);
  readonly filters = this.builder.nonNullable.group({
    plate: ['', [Validators.maxLength(10)]],
    result: [''],
    zone: ['', [Validators.maxLength(100)]],
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
    if (values.from && values.to && (values.from > values.to || range >= MAX_RANGE_DAYS)) {
      this.errorMessage.set('El rango debe ser válido y no superar 366 días.');
      this.state.set('error');
      return;
    }
    this.page.set(Math.max(0, page));
    this.state.set('loading');
    this.errorMessage.set('');
    this.selected.set(null);
    this.detail.set(null);
    this.api
      .search({
        page: this.page(),
        size: PAGE_SIZE,
        plate: values.plate.trim() || undefined,
        result: values.result || undefined,
        zone: values.zone.trim() || undefined,
        from: values.from || undefined,
        to: values.to || undefined,
      })
      .subscribe({
        next: (response) => {
          this.result.set(response);
          this.state.set(response.items.length ? 'ready' : 'empty');
        },
        error: (error) => {
          this.errorMessage.set(
            normalizeError(error).message || 'No se pudieron consultar las inspecciones.',
          );
          this.state.set('error');
        },
      });
  }

  applyFilters() {
    this.load(0);
  }
  clearFilters() {
    this.filters.reset({ plate: '', result: '', zone: '', from: '', to: '' });
    this.load(0);
  }
  openDetail(row: AdminInspection) {
    this.selected.set(row);
    this.detail.set(null);
    this.detailLoading.set(true);
    this.detailError.set('');
    this.api.detail(row.id).subscribe({
      next: (value) => {
        this.detail.set(value);
        this.detailLoading.set(false);
      },
      error: (error) => {
        this.detailError.set(normalizeError(error).message || 'No se pudo consultar el detalle.');
        this.detailLoading.set(false);
      },
    });
  }
  closeDetail() {
    this.selected.set(null);
    this.detail.set(null);
  }
  previous() {
    if (this.page() > 0) this.load(this.page() - 1);
  }
  next() {
    if (!this.result()?.last) this.load(this.page() + 1);
  }
  location(row: AdminInspection) {
    return `${row.zoneCode} · ${row.zoneName} / ${row.streetCode} · ${row.streetName}`;
  }
  resultLabel(value: string) {
    return (
      (
        {
          VALID: 'Válida',
          EXPIRED: 'Sesión reportada como vencida',
          NO_PAYMENT: 'Sin pago observado',
          VIOLATION: 'Otro hecho observado',
        } as Record<string, string>
      )[value] ?? 'Resultado registrado'
    );
  }
  formatDate(value: string) {
    return new Intl.DateTimeFormat('es-EC', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'America/Guayaquil',
    }).format(new Date(value));
  }
}
