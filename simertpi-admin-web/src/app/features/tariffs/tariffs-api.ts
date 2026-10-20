import { inject, Injectable } from '@angular/core';
import { HttpParams } from '@angular/common/http';
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
  normativeReference: string | null;
  active: boolean;
  validFrom: string;
  validTo: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BillingCalendar {
  id: string;
  zoneId: string | null;
  zoneCode: string | null;
  zoneName: string | null;
  validFrom: string;
  validTo: string | null;
  normativeReference: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  days: Record<string, boolean>;
}

export interface EffectiveConfiguration {
  at: string;
  generalTariff: EffectiveTariff | null;
  generalBillingCalendarId: string | null;
  generalChargeable: boolean;
  generalBillingDaysConfigured: boolean;
  generalBillingNormativeReference: string | null;
  zones: EffectiveZone[];
}
export interface EffectiveTariff {
  id: string; code: string; name: string; currency: string; amount: string;
  durationMinutes: number; minMinutes: number; maxContinuousMinutes: number;
  gracePeriodMinutes: number; roundingMode: string; validFrom: string; validTo: string | null;
  normativeReference: string | null;
}
export interface EffectiveZone {
  zoneId: string; zoneCode: string; zoneName: string; tariff: EffectiveTariff | null;
  tariffSource: 'ZONE' | 'GENERAL' | 'UNCONFIGURED'; billingCalendarId: string | null;
  chargeable: boolean; billingDaysConfigured: boolean;
  billingDaysSource: 'ZONE' | 'GENERAL' | 'LEGACY_UNCONFIGURED'; billingDaysNormativeReference: string | null;
}
export interface CreateTariff {
  code: string; name: string; zoneId: string | null; currency: string; amount: string;
  durationMinutes: number; minMinutes: number; maxContinuousMinutes: number; gracePeriodMinutes: number;
  roundingMode: string; validFrom: string; validTo: string | null; normativeReference: string;
}
export interface CreateBillingCalendar {
  zoneId: string | null; validFrom: string; validTo: string | null;
  chargeableDays: number[]; normativeReference: string;
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

  listBillingCalendars() {
    return this.api.get<BillingCalendar[]>('admin/parking/billing-day-calendars');
  }

  effective(at: string) {
    return this.api.get<EffectiveConfiguration>('admin/parking/configuration/effective', { params: new HttpParams().set('at', at) });
  }

  createTariff(body: CreateTariff) {
    return this.api.post<ConfiguredTariff>('admin/parking/tariffs', body);
  }

  endTariff(id: string, effectiveAt: string) {
    return this.api.post<ConfiguredTariff>(`admin/parking/tariffs/${encodeURIComponent(id)}/end`, { effectiveAt });
  }

  createBillingCalendar(body: CreateBillingCalendar) {
    return this.api.post<string>('admin/parking/billing-day-calendars', body);
  }

  endBillingCalendar(id: string, effectiveFrom: string) {
    return this.api.post<string>(`admin/parking/billing-day-calendars/${encodeURIComponent(id)}/end`, { effectiveFrom });
  }
}
