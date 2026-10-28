import { HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export type InspectionResult = 'VALID' | 'EXPIRED' | 'NO_PAYMENT' | 'VIOLATION' | string;

export interface AdminInspection {
  id: string;
  observedAt: string;
  result: InspectionResult;
  plate: string | null;
  zoneCode: string;
  zoneName: string;
  streetCode: string;
  streetName: string;
  spaceCode: string;
  spaceNumber: string;
  inspectorUsername: string;
  violationId: string | null;
}

export interface AdminInspectionDetail extends AdminInspection {
  notes: string | null;
  latitude: number | null;
  longitude: number | null;
  createdAt: string;
}

export interface AdminInspectionPage {
  items: AdminInspection[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface InspectionSearch {
  page: number;
  size: number;
  plate?: string;
  result?: InspectionResult;
  zone?: string;
  from?: string;
  to?: string;
}

@Injectable({ providedIn: 'root' })
export class InspectionsApi {
  private readonly api = inject(ApiClient);

  search(filters: InspectionSearch) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size);
    for (const key of ['plate', 'result', 'zone', 'from', 'to'] as const) {
      const value = filters[key];
      if (value) params = params.set(key, value);
    }
    return this.api.get<AdminInspectionPage>('admin/inspections', { params });
  }

  detail(id: string) {
    return this.api.get<AdminInspectionDetail>(`admin/inspections/${encodeURIComponent(id)}`);
  }
}
