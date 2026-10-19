import { HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export interface AdminReportResponse {
  from: string;
  to: string;
  timeZone: string;
  operational: {
    sessionsStarted: number;
    sessionsByStatus: { code: string; count: number }[];
    sessionsByZone: { zoneCode: string; zoneName: string; count: number }[];
    dailySessions: { date: string; count: number }[];
    recordedDuration: { endedSessionCount: number; totalSeconds: string; averageSeconds: string | null };
    spacesByZone: { zoneCode: string; zoneName: string; configuredSpaces: number; activeSpaces: number }[];
  };
  financial: {
    paymentsByCurrentStatus: { status: string; currency: string; count: number; amount: string }[];
    approvedPaymentsByPaidAt: { currency: string; count: number; amount: string }[];
    dailyApprovedPayments: { date: string; currency: string; count: number; amount: string }[];
    approvedPaymentsByZone: { zoneCode: string; zoneName: string; currency: string; count: number; amount: string }[];
  };
}

@Injectable({ providedIn: 'root' })
export class ReportsApi {
  private readonly api = inject(ApiClient);

  load(from: string, to: string) {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.api.get<AdminReportResponse>('admin/reports', { params });
  }
}
