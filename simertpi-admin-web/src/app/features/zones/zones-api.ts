import { inject, Injectable } from '@angular/core';
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
  qrCode: string;
  spaceNumber: string;
  latitude?: number;
  longitude?: number;
  spaceType?: string;
}

@Injectable({ providedIn: 'root' })
export class ZonesApi {
  private readonly api = inject(ApiClient);

  listZones() {
    return this.api.get<ParkingZone[]>('admin/parking/zones');
  }

  createZone(body: CreateZone) {
    return this.api.post<ParkingZone>('admin/parking/zones', body);
  }

  listStreets(zoneId: string) {
    return this.api.get<ParkingStreet[]>(`admin/parking/zones/${encodeURIComponent(zoneId)}/streets`);
  }

  createStreet(zoneId: string, body: CreateStreet) {
    return this.api.post<ParkingStreet>(
      `admin/parking/zones/${encodeURIComponent(zoneId)}/streets`,
      body,
    );
  }

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
}
