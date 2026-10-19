import { HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export type AdminPaymentStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'APPROVED'
  | 'DECLINED'
  | 'FAILED'
  | 'REFUNDED'
  | 'CANCELLED';

export interface AdminPaymentItem {
  id: string;
  parkingSessionId: string;
  provider: string;
  amount: string;
  currency: string;
  status: AdminPaymentStatus;
  createdAt: string;
  paidAt: string | null;
}

export interface PaymentCurrencySummary {
  currency: string;
  approvedCount: number;
  approvedAmount: string;
  pendingCount: number;
  pendingAmount: string;
  failedCount: number;
  failedAmount: string;
  refundedCount: number;
  refundedAmount: string;
  cancelledCount: number;
  cancelledAmount: string;
}

export interface AdminPaymentPage {
  items: AdminPaymentItem[];
  summaryByCurrency: PaymentCurrencySummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface PaymentSearch {
  page: number;
  size: number;
  status?: AdminPaymentStatus;
  q?: string;
  from?: string;
  to?: string;
}

@Injectable({ providedIn: 'root' })
export class PaymentsApi {
  private readonly api = inject(ApiClient);

  search(filters: PaymentSearch) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size);
    for (const key of ['status', 'q', 'from', 'to'] as const) {
      const value = filters[key];
      if (value) params = params.set(key, value);
    }
    return this.api.get<AdminPaymentPage>('admin/payments', { params });
  }
}
