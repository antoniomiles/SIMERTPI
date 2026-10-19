import { HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export type ParkingSessionStatus =
  | 'PENDING_PAYMENT'
  | 'ACTIVE'
  | 'EXTENDED'
  | 'EXPIRED'
  | 'MAX_TIME_REACHED'
  | 'COMPLETED'
  | 'CANCELLED';

export interface AdminParkingSession {
  id: string;
  plate: string;
  zoneCode: string;
  zoneName: string;
  streetCode: string;
  streetName: string;
  spaceCode: string;
  spaceNumber: string;
  spaceEnabled: boolean;
  sessionStatus: ParkingSessionStatus;
  startedAt: string;
  expectedEndAt: string | null;
  endedAt: string | null;
  durationMinutes: number;
  paymentStatus: string | null;
}

export interface AdminParkingSessionPage {
  items: AdminParkingSession[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ParkingSessionSearch {
  page: number;
  size: number;
  status?: ParkingSessionStatus;
  q?: string;
  from?: string;
  to?: string;
}

@Injectable({ providedIn: 'root' })
export class ParkingSessionsApi {
  private readonly api = inject(ApiClient);

  search(filters: ParkingSessionSearch) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size);
    for (const key of ['status', 'q', 'from', 'to'] as const) {
      const value = filters[key];
      if (value) params = params.set(key, value);
    }
    return this.api.get<AdminParkingSessionPage>('admin/parking/sessions', { params });
  }
}
