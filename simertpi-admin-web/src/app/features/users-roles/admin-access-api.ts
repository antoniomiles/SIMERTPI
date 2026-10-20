import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../../core/config/portal-config';

export interface AdminAccount {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  roles: string[];
  enabled: boolean;
  activationPending: boolean;
  createdAt: string;
}

export interface AdminAccountPage {
  content: AdminAccount[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AdminRoleSummary {
  code: string;
  name: string;
  description: string | null;
  assignedAccounts: number;
}

@Injectable({ providedIn: 'root' })
export class AdminAccessApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(PORTAL_CONFIG);
  private readonly base = `${this.config.apiBaseUrl.replace(/\/$/, '')}/admin/access`;

  users(query: string, role: string, enabled: boolean | null, page: number, size = 25) {
    let params = new HttpParams().set('page', page).set('size', size);
    if (query.trim()) params = params.set('q', query.trim());
    if (role) params = params.set('role', role);
    if (enabled !== null) params = params.set('enabled', enabled);
    return this.http.get<AdminAccountPage>(`${this.base}/users`, { params });
  }

  roles() {
    return this.http.get<AdminRoleSummary[]>(`${this.base}/roles`);
  }
}
