import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../../core/config/portal-config';

export interface AdminAccount {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
  roles: string[];
  enabled: boolean;
  activationPending: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateAdminAccountRequest {
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
  reason: string;
  updatedAt: string;
}

export interface CreateAdminAccountRequest {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
  roles: string[];
  reason: string;
}

export interface AssignAdminRolesRequest {
  roles: string[];
  reason: string;
  updatedAt: string;
}

export interface ReissueAdminActivationRequest {
  reason: string;
  updatedAt: string;
}

export interface AdminAccountStatusRequest {
  enabled: boolean;
  reason: string;
  updatedAt: string;
}

export interface CreateInspectorRequest {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
}

export interface CreatedInspectorAccount extends Omit<AdminAccount, 'createdAt' | 'updatedAt'> {
  activationToken: string;
  activationExpiresAt: string;
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
  displayName?: string;
  description: string | null;
  assignedAccounts: number;
}

export interface AdminRolePermissions {
  roleCode: string;
  roleName: string;
  permissions: string[];
}

export interface AdminPermissionCatalog {
  roles: AdminRolePermissions[];
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

  permissions() {
    return this.http.get<AdminPermissionCatalog>(`${this.base}/permissions`);
  }

  createAccount(request: CreateAdminAccountRequest) {
    return this.http.post<CreatedInspectorAccount>(`${this.base}/users`, request);
  }

  assignRoles(id: string, request: AssignAdminRolesRequest) {
    return this.http.put<AdminAccount>(`${this.base}/users/${encodeURIComponent(id)}/roles`, request);
  }

  updateAccount(id: string, request: UpdateAdminAccountRequest) {
    return this.http.put<AdminAccount>(`${this.base}/users/${encodeURIComponent(id)}`, request);
  }

  updateStatus(id: string, request: AdminAccountStatusRequest) {
    return this.http.put<AdminAccount>(`${this.base}/users/${encodeURIComponent(id)}/status`, request);
  }

  createInspector(request: CreateInspectorRequest) {
    const base = `${this.config.apiBaseUrl.replace(/\/$/, '')}/admin/inspectors`;
    return this.http.post<CreatedInspectorAccount>(base, request);
  }

  reissueInspectorActivation(id: string) {
    const base = `${this.config.apiBaseUrl.replace(/\/$/, '')}/admin/inspectors`;
    return this.http.post<{ activationToken: string; expiresAt: string }>(
      `${base}/${encodeURIComponent(id)}/activation`,
      {},
    );
  }

  reissueAdminActivation(id: string, request: ReissueAdminActivationRequest) {
    return this.http.post<{ activationToken: string; activationExpiresAt: string }>(
      `${this.base}/users/${encodeURIComponent(id)}/activation`, request,
    );
  }
}
