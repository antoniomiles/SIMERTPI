import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { AdminReportResponse, ReportsApi } from '../reports/reports-api';
import { DashboardVisualizations } from './dashboard-visualizations';
import { formatDashboardAmount } from './dashboard-formatters';

type ViewState = 'idle' | 'loading' | 'error' | 'ready';
const MAX_RANGE_DAYS = 366;
const MUNICIPAL_TIME_ZONE = 'America/Guayaquil';
const IN_FLIGHT_PAYMENT_STATUSES = new Set(['PENDING', 'PROCESSING']);

function municipalToday() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: MUNICIPAL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function shiftDate(value: string, days: number) {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

@Component({
  selector: 'app-dashboard',
  imports: [DashboardVisualizations, DataState, PageHeading, ReactiveFormsModule, RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly api = inject(ReportsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly today = municipalToday();
  readonly state = signal<ViewState>('idle');
  readonly result = signal<AdminReportResponse | null>(null);
  readonly errorMessage = signal('');
  readonly lastSuccessfulAt = signal<Date | null>(null);
  readonly lastSuccessfulRange = signal<{ from: string; to: string } | null>(null);
  readonly filters = this.builder.nonNullable.group({
    from: [shiftDate(this.today, -6), Validators.required],
    to: [this.today, Validators.required],
  });
  readonly currentSpaces = computed(() =>
    (this.result()?.operational.spacesByZone ?? []).reduce(
      (totals, zone) => ({
        configured: totals.configured + zone.configuredSpaces,
        active: totals.active + zone.activeSpaces,
      }),
      { configured: 0, active: 0 },
    ),
  );
  readonly pendingPayments = computed(() =>
    (this.result()?.financial.paymentsByCurrentStatus ?? [])
      .filter((item) => IN_FLIGHT_PAYMENT_STATUSES.has(item.status))
      .reduce((count, item) => count + item.count, 0),
  );
  constructor() {
    if (!this.preview) this.load();
  }

  load() {
    if (this.preview || this.state() === 'loading') return;
    const { from, to } = this.filters.getRawValue();
    const duration = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000;
    if (this.filters.invalid || !from || !to || duration < 0 || duration >= MAX_RANGE_DAYS) {
      this.filters.markAllAsTouched();
      this.errorMessage.set('Selecciona un rango válido de hasta 366 días.');
      this.state.set('error');
      return;
    }

    this.errorMessage.set('');
    this.result.set(null);
    this.state.set('loading');
    this.api.load(from, to).subscribe({
      next: (result) => {
        this.result.set(result);
        this.lastSuccessfulAt.set(new Date());
        this.lastSuccessfulRange.set({ from, to });
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.errorMessage.set(normalizeError(error).message || 'No se pudo cargar el dashboard.');
        this.state.set('error');
      },
    });
  }

  formatLastSuccessful(value: Date | null) {
    if (!value) return 'Aún no hay una consulta exitosa.';
    return new Intl.DateTimeFormat('es-EC', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: MUNICIPAL_TIME_ZONE,
    }).format(value);
  }

  formatAmount(amount: string, currency: string) {
    return formatDashboardAmount(amount, currency);
  }
}
