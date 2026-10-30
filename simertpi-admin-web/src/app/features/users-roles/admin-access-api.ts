import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { map } from 'rxjs';

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
  activationExpiresAt?: string | null;
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

export type DeliveryStatus = 'ACCEPTED_BY_PROVIDER' | 'UNCONFIGURED' | 'FAILED';
export interface AccountCreationResponse {
  account: AdminAccount;
  deliveryStatus: DeliveryStatus;
  activationExpiresAt: string;
}
export interface ActivationDeliveryResponse {
  deliveryStatus: DeliveryStatus;
  activationExpiresAt: string;
}
export interface CreatedInspectorAccount {
  id: string; username: string; email: string; firstName: string; lastName: string;
  phone: string | null; enabled: boolean; activationPending: boolean;
  deliveryStatus: DeliveryStatus; activationExpiresAt: string;
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
  enabled: boolean;
  protectedRole: boolean;
  portalEnabled: boolean;
  rowVersion: number;
  userCount: number;
  permissions: string[];
  createdAt: string;
  updatedAt: string;
  displayName?: string;
  assignedAccounts?: number;
}

export interface AdminRolePermissions {
  roleCode: string;
  roleName: string;
  permissions: string[];
}

export interface AdminPermissionCatalog {
  code: string;
  moduleCode: string;
  actionCode: string;
  name: string;
  description: string;
  assignable: boolean;
}
export interface AdminPermissionDefinition {
  code: string;
  name: string;
  description: string;
  moduleCode: string;
  actionCode: string;
  assignable: boolean;
}
export interface AdminRoleHistoryEntry {
  id: string;
  action: string;
  actor: string | null;
  occurredAt: string;
  result: string;
  metadata: Record<string, unknown>;
}
export interface RoleMutationRequest {
  name: string;
  description: string;
  permissions: string[];
  updatedAt?: string;
  rowVersion?: number;
  reason?: string;
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
    return this.http.get<AdminPermissionDefinition[]>(`${this.base}/permission-catalog`);
  }

  role(code: string) {
    return this.http.get<AdminRoleSummary>(`${this.base}/roles/${encodeURIComponent(code)}`);
  }

  roleHistory(code: string) {
    return this.http.get<AdminRoleHistoryEntry[]>(`${this.base}/roles/${encodeURIComponent(code)}/history`);
  }

  createRole(request: RoleMutationRequest & { code: string }) {
    return this.http.post<AdminRoleSummary>(`${this.base}/roles`, request);
  }

  updateRole(code: string, request: RoleMutationRequest) {
    return this.http.put<AdminRoleSummary>(`${this.base}/roles/${encodeURIComponent(code)}`, request);
  }

  updateRoleStatus(code: string, request: Pick<RoleMutationRequest, 'updatedAt' | 'rowVersion' | 'reason'> & { enabled: boolean }) {
    return this.http.put<AdminRoleSummary>(`${this.base}/roles/${encodeURIComponent(code)}/status`, request);
  }

  createAccount(request: CreateAdminAccountRequest) {
    return this.http.post<AccountCreationResponse>(`${this.base}/users`, request);
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
    return this.http.post<{ deliveryStatus: DeliveryStatus; expiresAt: string }>(
      `${base}/${encodeURIComponent(id)}/activation`,
      {},
    ).pipe(map((response) => ({ deliveryStatus: response.deliveryStatus, activationExpiresAt: response.expiresAt })));
  }

  reissueAdminActivation(id: string, request: ReissueAdminActivationRequest) {
    return this.http.post<ActivationDeliveryResponse>(
      `${this.base}/users/${encodeURIComponent(id)}/activation`, request,
    );
  }
}
