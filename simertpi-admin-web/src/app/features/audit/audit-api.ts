import { HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export interface AuditRecord {
  id: string;
  occurredAt: string;
  actorName: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string | null;
  result: string;
  correlationId: string | null;
}

export interface AuditFilters {
  action?: string;
  resourceType?: string;
  result?: string;
  correlationId?: string;
  from?: string;
  to?: string;
  offset: number;
  limit: number;
}

@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly api = inject(ApiClient);

  search(filters: AuditFilters) {
    let params = new HttpParams().set('limit', filters.limit).set('offset', filters.offset);
    for (const key of ['action', 'resourceType', 'result', 'correlationId'] as const) {
      const value = filters[key]?.trim();
      if (value) params = params.set(key, value);
    }
    if (filters.from) params = params.set('from', filters.from);
    if (filters.to) params = params.set('to', filters.to);
    return this.api.get<AuditRecord[]>('admin/audit', { params });
  }
}
