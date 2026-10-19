import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import {
  AdminParkingSession,
  AdminParkingSessionPage,
  ParkingSessionStatus,
  ParkingSessionsApi,
} from './parking-sessions-api';

const PAGE_SIZE = 25;
type ViewState = 'loading' | 'error' | 'empty' | 'ready';
const STATUS_LABELS: Record<ParkingSessionStatus, string> = {
  PENDING_PAYMENT: 'Pendiente de pago',
  ACTIVE: 'Activa',
  EXTENDED: 'Extendida',
  EXPIRED: 'Vencida',
  MAX_TIME_REACHED: 'Tiempo máximo alcanzado',
  COMPLETED: 'Finalizada',
  CANCELLED: 'Anulada',
};

@Component({
  selector: 'app-parking-sessions',
  imports: [ReactiveFormsModule, DatePipe, DataState, PageHeading],
  templateUrl: './parking-sessions.html',
  styleUrl: './parking-sessions.scss',
})
export class ParkingSessions {
  private readonly api = inject(ParkingSessionsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly result = signal<AdminParkingSessionPage | null>(null);
  readonly errorMessage = signal('');
  readonly page = signal(0);
  readonly pageCount = computed(() => this.result()?.totalPages ?? 0);
  readonly filters = this.builder.nonNullable.group({
    q: ['', [Validators.maxLength(100)]],
    status: [''],
    from: [''],
    to: [''],
  });

  constructor() {
    if (!this.preview) this.load();
  }

  load(page = this.page()) {
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const values = this.filters.getRawValue();
    if (values.from && values.to && values.from > values.to) {
      this.errorMessage.set('La fecha inicial debe ser anterior o igual a la fecha final.');
      this.state.set('error');
      return;
    }
    this.page.set(Math.max(0, page));
    this.errorMessage.set('');
    this.state.set('loading');
    this.api
      .search({
        page: this.page(),
        size: PAGE_SIZE,
        q: values.q.trim() || undefined,
        status: (values.status || undefined) as ParkingSessionStatus | undefined,
        from: values.from || undefined,
        to: values.to || undefined,
      })
      .subscribe({
        next: (result) => {
          this.result.set(result);
          this.state.set(result.items.length ? 'ready' : 'empty');
        },
        error: (error: unknown) => {
          this.errorMessage.set(
            normalizeError(error).message || 'No se pudieron consultar las sesiones.',
          );
          this.state.set('error');
        },
      });
  }

  applyFilters() {
    this.load(0);
  }
  clearFilters() {
    this.filters.reset({ q: '', status: '', from: '', to: '' });
    this.load(0);
  }
  previous() {
    if (this.page() > 0) this.load(this.page() - 1);
  }
  next() {
    if (this.page() + 1 < this.pageCount()) this.load(this.page() + 1);
  }
  statusLabel(status: ParkingSessionStatus) {
    return STATUS_LABELS[status] ?? status;
  }
  location(row: AdminParkingSession) {
    return `${row.zoneCode} · ${row.zoneName} / ${row.streetCode} · ${row.streetName}`;
  }
}
