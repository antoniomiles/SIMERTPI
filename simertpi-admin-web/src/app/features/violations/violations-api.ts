import { HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export type ViolationStatus = 'OPEN' | 'NOTIFIED' | 'PAID' | 'CANCELLED' | 'DISPUTED';

export interface AdminEvidence {
  id: string;
  violationId: string;
  evidenceType: 'PHOTO' | 'DOCUMENT' | string;
  contentType: string;
  fileSize: number;
  capturedAt: string | null;
  latitude: number | null;
  longitude: number | null;
  createdAt: string;
}

export interface AdminViolation {
  id: string;
  plate: string | null;
  violationType: string;
  status: ViolationStatus;
  occurredAt: string;
  inspectionId: string;
  parkingSessionId: string | null;
  zoneCode: string;
  zoneName: string;
  streetCode: string;
  streetName: string;
  spaceCode: string;
  spaceNumber: string;
  inspectorUsername: string;
  evidenceCount: number;
  createdAt: string;
  notifiedAt: string | null;
  paidAt: string | null;
}

export interface AdminViolationPage {
  items: AdminViolation[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ViolationSearch {
  page: number;
  size: number;
  status?: ViolationStatus;
  plate?: string;
  zone?: string;
  q?: string;
  from?: string;
  to?: string;
}

@Injectable({ providedIn: 'root' })
export class ViolationsApi {
  private readonly api = inject(ApiClient);

  search(filters: ViolationSearch) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size);
    for (const key of ['status', 'plate', 'zone', 'q', 'from', 'to'] as const) {
      const value = filters[key];
      if (value) params = params.set(key, value);
    }
    return this.api.get<AdminViolationPage>('admin/violations', { params });
  }

  detail(id: string) {
    return this.api.get<AdminViolation>(`admin/violations/${encodeURIComponent(id)}`);
  }

  evidence(violationId: string) {
    return this.api.get<AdminEvidence[]>(
      `admin/violations/${encodeURIComponent(violationId)}/evidence`,
    );
  }

  downloadEvidence(evidenceId: string) {
    return this.api.getBlob(`admin/evidence/${encodeURIComponent(evidenceId)}/content`);
  }
}
