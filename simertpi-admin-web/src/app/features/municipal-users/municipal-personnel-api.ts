import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { map } from 'rxjs';

export interface InspectorDirectoryItem {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
  role: 'INSPECTOR';
  enabled: boolean;
  activationPending: boolean;
  createdAt: string;
}

export interface InspectorDirectory {
  content: InspectorDirectoryItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateInspector {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phone?: string;
}

export interface InspectorCreated {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
  enabled: boolean;
  activationPending: boolean;
  deliveryStatus: 'ACCEPTED_BY_PROVIDER' | 'UNCONFIGURED' | 'FAILED';
  activationExpiresAt: string;
}

export interface InspectorActivation {
  deliveryStatus: 'ACCEPTED_BY_PROVIDER' | 'UNCONFIGURED' | 'FAILED';
  activationExpiresAt: string;
}

@Injectable({ providedIn: 'root' })
export class MunicipalPersonnelApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(PORTAL_CONFIG);
  private readonly endpoint = `${this.config.apiBaseUrl.replace(/\/$/, '')}/admin/inspectors`;

  list(query: string, status: 'all' | 'enabled' | 'disabled', page: number, size = 25) {
    let params = new HttpParams().set('page', page).set('size', size);
    const q = query.trim();
    if (q) params = params.set('q', q);
    if (status !== 'all') params = params.set('enabled', status === 'enabled');
    return this.http.get<InspectorDirectory>(this.endpoint, { params });
  }

  create(inspector: CreateInspector) {
    return this.http.post<InspectorCreated>(this.endpoint, inspector);
  }

  reissueActivation(id: string) {
    return this.http.post<{ deliveryStatus: InspectorActivation['deliveryStatus']; expiresAt?: string; activationExpiresAt?: string }>(`${this.endpoint}/${encodeURIComponent(id)}/activation`, {}).pipe(
      map((response) => ({ deliveryStatus: response.deliveryStatus, activationExpiresAt: response.activationExpiresAt ?? response.expiresAt ?? '' })),
    );
  }
}
