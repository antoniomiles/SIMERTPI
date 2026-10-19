import { inject, Injectable } from '@angular/core';
import { ApiClient } from '../../core/http/api-client';

export interface ConfiguredTariff {
  id: string;
  code: string;
  name: string;
  zoneId: string | null;
  zoneCode: string | null;
  zoneName: string | null;
  currency: string | null;
  amount: number;
  durationMinutes: number;
  minMinutes: number;
  maxContinuousMinutes: number | null;
  gracePeriodMinutes: number | null;
  roundingMode: string | null;
  active: boolean;
  validFrom: string;
  validTo: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ConfiguredSchedule {
  id: string;
  zoneId: string | null;
  zoneCode: string | null;
  zoneName: string | null;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  active: boolean;
  validFrom: string | null;
  validTo: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CalendarException {
  id: string;
  holidayDate: string;
  name: string;
  zoneId: string | null;
  zoneCode: string | null;
  zoneName: string | null;
  holidayType: string;
  tariffed: boolean;
  startTime: string | null;
  endTime: string | null;
  normativeReference: string | null;
  validFrom: string;
  validTo: string | null;
  active: boolean;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class TariffsApi {
  private readonly api = inject(ApiClient);

  listTariffs() {
    return this.api.get<ConfiguredTariff[]>('admin/parking/tariffs');
  }

  listSchedules() {
    return this.api.get<ConfiguredSchedule[]>('admin/parking/schedules');
  }

  listCalendarExceptions() {
    return this.api.get<CalendarException[]>('admin/parking/calendar-exceptions');
  }
}
