import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { AdminReportResponse, ReportsApi } from './reports-api';

type ViewState = 'loading' | 'error' | 'ready';
const MAX_RANGE_DAYS = 366;

function municipalToday() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Guayaquil',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function shiftDate(value: string, days: number) {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

@Component({
  selector: 'app-reports',
  imports: [ReactiveFormsModule, DataState, PageHeading],
  templateUrl: './reports.html',
  styleUrl: './reports.scss',
})
export class Reports {
  private readonly api = inject(ReportsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly state = signal<ViewState>('loading');
  readonly result = signal<AdminReportResponse | null>(null);
  readonly errorMessage = signal('');
  readonly selectedCurrency = signal('');
  readonly today = municipalToday();
  readonly filters = this.builder.nonNullable.group({
    from: [shiftDate(this.today, -29), Validators.required],
    to: [this.today, Validators.required],
  });
  readonly selectedDailyApprovals = computed(() =>
    (this.result()?.financial.dailyApprovedPayments ?? []).filter(
      (item) => item.currency === this.selectedCurrency(),
    ),
  );
  readonly maxSessionsPerDay = computed(() =>
    Math.max(0, ...(this.result()?.operational.dailySessions.map((item) => item.count) ?? [])),
  );
  readonly maxApprovedPerDay = computed(() =>
    Math.max(0, ...this.selectedDailyApprovals().map((item) => Number(item.amount))),
  );

  constructor() {
    if (!this.preview) this.load();
  }

  load() {
    if (this.preview || this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const { from, to } = this.filters.getRawValue();
    const duration = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000;
    if (!from || !to || duration < 0 || duration >= MAX_RANGE_DAYS) {
      this.errorMessage.set('Selecciona un rango válido de hasta 366 días.');
      this.state.set('error');
      return;
    }
    this.errorMessage.set('');
    this.state.set('loading');
    this.api.load(from, to).subscribe({
      next: (result) => {
        this.result.set(result);
        const firstCurrency = result.financial.approvedPaymentsByPaidAt[0]?.currency ?? '';
        this.selectedCurrency.set(firstCurrency);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.errorMessage.set(normalizeError(error).message || 'No se pudieron generar los reportes.');
        this.state.set('error');
      },
    });
  }

  formatAmount(amount: string, currency: string) {
    if (!/^-?\d+(\.\d{1,2})?$/.test(amount)) return `${currency} ${amount}`;
    const [whole, fraction = ''] = amount.split('.');
    const grouped = new Intl.NumberFormat('es-EC', { maximumFractionDigits: 0 }).format(BigInt(whole));
    return `${currency} ${grouped},${fraction.padEnd(2, '0')}`;
  }

  formatDuration(seconds: string | null) {
    if (seconds === null) return 'Sin duración final registrada';
    const total = Math.max(0, Math.round(Number(seconds)));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    return hours ? `${hours} h ${minutes} min` : `${minutes} min`;
  }

  sessionBarHeight(count: number) {
    return this.maxSessionsPerDay() ? Math.max(2, (count / this.maxSessionsPerDay()) * 100) : 0;
  }

  paymentBarHeight(amount: string) {
    const value = Number(amount);
    return this.maxApprovedPerDay() ? Math.max(2, (value / this.maxApprovedPerDay()) * 100) : 0;
  }

  exportCsv() {
    const data = this.result();
    if (!data) return;
    const rows: string[][] = [['Tipo', 'Fecha', 'Estado o zona', 'Moneda', 'Cantidad', 'Importe']];
    for (const item of data.operational.sessionsByStatus) rows.push(['Sesiones por estado', '', item.code, '', String(item.count), '']);
    for (const item of data.operational.sessionsByZone) rows.push(['Sesiones por zona', '', `${item.zoneCode} ${item.zoneName}`, '', String(item.count), '']);
    for (const item of data.operational.dailySessions) rows.push(['Sesiones iniciadas', item.date, '', '', String(item.count), '']);
    for (const item of data.operational.spacesByZone) rows.push(['Espacios configurados', '', `${item.zoneCode} ${item.zoneName}`, '', String(item.configuredSpaces), '']);
    for (const item of data.financial.paymentsByCurrentStatus) rows.push(['Pagos por estado actual y creación', '', item.status, item.currency, String(item.count), item.amount]);
    for (const item of data.financial.approvedPaymentsByPaidAt) rows.push(['Pagos aprobados por fecha de pago', '', 'APPROVED', item.currency, String(item.count), item.amount]);
    for (const item of data.financial.dailyApprovedPayments) rows.push(['Pagos aprobados diarios', item.date, 'APPROVED', item.currency, String(item.count), item.amount]);
    for (const item of data.financial.approvedPaymentsByZone) rows.push(['Pagos aprobados por zona', '', `${item.zoneCode} ${item.zoneName}`, item.currency, String(item.count), item.amount]);
    const csv = rows.map((row) => row.map(Reports.csvCell).join(',')).join('\r\n');
    const url = URL.createObjectURL(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `simertpi-reportes-${data.from}-${data.to}.csv`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  private static csvCell(value: string) {
    const safe = /^[\s=+\-@]/.test(value) ? `'${value}` : value;
    return `"${safe.replaceAll('"', '""')}"`;
  }
}
