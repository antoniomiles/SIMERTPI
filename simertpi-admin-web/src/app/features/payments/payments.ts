import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import {
  AdminPaymentItem,
  AdminPaymentPage,
  AdminPaymentStatus,
  PaymentsApi,
} from './payments-api';

const PAGE_SIZE = 25;
type ViewState = 'loading' | 'error' | 'empty' | 'ready';
const STATUS_LABELS: Record<AdminPaymentStatus, string> = {
  PENDING: 'Pendiente',
  PROCESSING: 'En proceso',
  APPROVED: 'Aprobado por proveedor',
  DECLINED: 'Rechazado',
  FAILED: 'Fallido',
  REFUNDED: 'Reembolsado',
  CANCELLED: 'Cancelado',
};

@Component({
  selector: 'app-payments',
  imports: [ReactiveFormsModule, DatePipe, DataState, PageHeading],
  templateUrl: './payments.html',
  styleUrl: './payments.scss',
})
export class Payments {
  private readonly api = inject(PaymentsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly result = signal<AdminPaymentPage | null>(null);
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
        status: (values.status || undefined) as AdminPaymentStatus | undefined,
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
            normalizeError(error).message || 'No se pudieron consultar los pagos.',
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

  statusLabel(status: AdminPaymentStatus) {
    return STATUS_LABELS[status] ?? status;
  }

  formatAmount(amount: string, currency: string) {
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) return `${currency} ${amount}`;
    const [whole, fraction = ''] = amount.split('.');
    const grouped = new Intl.NumberFormat('es-EC', { maximumFractionDigits: 0 }).format(BigInt(whole));
    return `${currency} ${grouped},${fraction.padEnd(2, '0')}`;
  }

  paymentReference(payment: AdminPaymentItem) {
    return payment.id;
  }
}
