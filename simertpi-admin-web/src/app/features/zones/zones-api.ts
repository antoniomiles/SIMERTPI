import { inject, Injectable } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { ApiClient } from '../../core/http/api-client';

export interface ParkingZone {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ParkingStreet {
  id: string;
  zoneId: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ParkingSpace {
  id: string;
  streetId: string;
  code: string;
  qrCode: string;
  spaceNumber: string;
  latitude: number | null;
  longitude: number | null;
  spaceType: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateZone {
  code: string;
  name: string;
  description?: string;
}

export interface CreateStreet {
  code: string;
  name: string;
  description?: string;
}

export interface CreateParkingSpace {
  code: string;
  spaceNumber: string;
  latitude?: number;
  longitude?: number;
  spaceType?: string;
}

export interface CatalogPage<T> { page: number; size: number; totalElements: number; totalPages: number; content: T[]; }
export interface CatalogFilters { q?: string; active?: boolean | null; zoneId?: string; streetId?: string; operational?: string; georeferenced?: boolean | null; page: number; size: number; }
export interface ZoneCatalog extends ParkingZone { streetCount: number; spaceCount: number; activeSpaceCount: number; }
export interface StreetCatalog extends ParkingStreet { zoneCode: string; zoneName: string; spaceCount: number; }
export type ParkingMapStatus = 'ACTIVE' | 'ENDING_SOON' | 'EXPIRED' | 'MAX_TIME_REACHED' | 'NO_ACTIVE_SESSION' | 'DISABLED' | 'PAYMENT_PENDING' | 'UNKNOWN';
export interface SpaceCatalog extends ParkingSpace { zoneId: string; zoneCode: string; zoneName: string; streetCode: string; streetName: string; operationalStatus: 'AVAILABLE' | 'OCCUPIED' | 'DISABLED'; mapStatus: ParkingMapStatus; }
export type UpdateZone = Omit<CreateZone, never> & { active: boolean; expectedUpdatedAt: string };
export type UpdateStreet = Omit<CreateStreet, never> & { active: boolean; expectedUpdatedAt: string };
export type UpdateParkingSpace = Omit<CreateParkingSpace, 'qrCode'> & { active: boolean; expectedUpdatedAt: string };
export interface MapMarker extends Pick<SpaceCatalog, 'id'|'code'|'spaceNumber'|'zoneId'|'zoneCode'|'zoneName'|'streetId'|'streetCode'|'streetName'|'active'|'operationalStatus'|'mapStatus'|'latitude'|'longitude'> { }
export interface MapCoverage { totalSpaces: number; georeferencedSpaces: number; pendingGeoreference: number; evaluatedAt: string; refreshIntervalSeconds: number; markers: MapMarker[]; }
export interface NearbySpace { code: string; spaceNumber: string; distanceMeters: number; }

@Injectable({ providedIn: 'root' })
export class ZonesApi {
  private readonly api = inject(ApiClient);

  listZones() {
    return this.api.get<ParkingZone[]>('admin/parking/zones');
  }

  catalogZones(filters: CatalogFilters) {
    return this.api.get<CatalogPage<ZoneCatalog>>('admin/parking/catalog/zones', { params: this.params(filters) });
  }
  catalogStreets(filters: CatalogFilters) {
    return this.api.get<CatalogPage<StreetCatalog>>('admin/parking/catalog/streets', { params: this.params(filters) });
  }
  catalogSpaces(filters: CatalogFilters) {
    return this.api.get<CatalogPage<SpaceCatalog>>('admin/parking/catalog/spaces', { params: this.params(filters) });
  }
  mapCoverage(zoneId?: string, streetId?: string) {
    let params = new HttpParams().set('limit', '1000');
    if (zoneId) params=params.set('zoneId',zoneId);
    if (streetId) params=params.set('streetId',streetId);
    return this.api.get<MapCoverage>('admin/parking/catalog/map', { params });
  }
  nearby(latitude:number,longitude:number,excludeId?:string) {
    let params=new HttpParams().set('latitude',latitude).set('longitude',longitude).set('radiusMeters',3);
    if(excludeId)params=params.set('excludeId',excludeId);
    return this.api.get<NearbySpace[]>('admin/parking/catalog/spaces/nearby',{params});
  }

  createZone(body: CreateZone) {
    return this.api.post<ParkingZone>('admin/parking/zones', body);
  }
  updateZone(id: string, body: UpdateZone) { return this.api.put<ParkingZone>(`admin/parking/zones/${encodeURIComponent(id)}`, body); }

  listStreets(zoneId: string) {
    return this.api.get<ParkingStreet[]>(`admin/parking/zones/${encodeURIComponent(zoneId)}/streets`);
  }

  createStreet(zoneId: string, body: CreateStreet) {
    return this.api.post<ParkingStreet>(
      `admin/parking/zones/${encodeURIComponent(zoneId)}/streets`,
      body,
    );
  }
  updateStreet(id: string, body: UpdateStreet) { return this.api.put<ParkingStreet>(`admin/parking/streets/${encodeURIComponent(id)}`, body); }

  listSpaces(streetId: string) {
    return this.api.get<ParkingSpace[]>(
      `admin/parking/streets/${encodeURIComponent(streetId)}/spaces`,
    );
  }

  createSpace(streetId: string, body: CreateParkingSpace) {
    return this.api.post<ParkingSpace>(
      `admin/parking/streets/${encodeURIComponent(streetId)}/spaces`,
      body,
    );
  }
  updateSpace(id: string, body: UpdateParkingSpace) { return this.api.put<ParkingSpace>(`admin/parking/spaces/${encodeURIComponent(id)}`, body); }

  private params(filters: CatalogFilters) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size);
    if (filters.q?.trim()) params = params.set('q', filters.q.trim());
    if (filters.active !== null && filters.active !== undefined) params = params.set('active', filters.active);
    if (filters.zoneId) params = params.set('zoneId', filters.zoneId);
    if (filters.streetId) params = params.set('streetId', filters.streetId);
    if (filters.operational) params = params.set('operational', filters.operational);
    if (filters.georeferenced !== null && filters.georeferenced !== undefined) params = params.set('georeferenced', filters.georeferenced);
    return params;
  }
}
