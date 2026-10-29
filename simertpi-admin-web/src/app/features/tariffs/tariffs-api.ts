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
  cancelledAt: string | null;
  cancelledByUserId: string | null;
  cancellationReason: string | null;
  cancelledByUsername: string | null;
  rowVersion: number;
  versionGroupId: string;
  previousVersionId: string | null;
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
  rowVersion: number;
  versionGroupId: string;
  previousCalendarId: string | null;
  cancelledAt: string | null;
  cancelledByUserId: string | null;
  cancellationReason: string | null;
  cancelledByUsername: string | null;
}
export interface BillingCalendarHistoryItem {
  id: string;
  calendarId: string;
  previousCalendarId: string | null;
  action: string;
  actorId: string | null;
  actorName: string | null;
  occurredAt: string;
  effectiveFrom: string | null;
  reason: string;
  beforeState: Record<string, unknown> | null;
  afterState: Record<string, unknown>;
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
  id: string;
  code: string;
  name: string;
  currency: string;
  amount: string | number;
  durationMinutes: number;
  minMinutes: number;
  maxContinuousMinutes: number;
  gracePeriodMinutes: number;
  roundingMode: string;
  validFrom: string;
  validTo: string | null;
  normativeReference: string | null;
}
export interface EffectiveZone {
  zoneId: string;
  zoneCode: string;
  zoneName: string;
  tariff: EffectiveTariff | null;
  tariffSource: 'ZONE' | 'GENERAL' | 'UNCONFIGURED';
  billingCalendarId: string | null;
  chargeable: boolean;
  billingDaysConfigured: boolean;
  billingDaysSource: 'ZONE' | 'GENERAL' | 'LEGACY_UNCONFIGURED';
  billingDaysNormativeReference: string | null;
}
export interface EffectiveOperationalCalendar {
  zoneId: string;
  date: string;
  time: string;
  operational: boolean;
  holiday: boolean;
  chargeable: boolean;
  windowStart: string | null;
  windowEnd: string | null;
  source: 'ZONE' | 'GENERAL' | 'EXCEPTION' | 'NONE';
  reasonCode: string;
}
export interface CreateTariff {
  code: string;
  name: string;
  zoneId: string | null;
  currency: string;
  amount: string;
  durationMinutes: number;
  minMinutes: number;
  maxContinuousMinutes: number;
  gracePeriodMinutes: number;
  roundingMode: string;
  validFrom: string;
  validTo: string | null;
  normativeReference: string;
  reason?: string;
  expectedVersion?: number;
}
export type ImmediateTariffRevision = Omit<CreateTariff, 'zoneId' | 'validFrom'>;
export interface TariffHistoryItem {
  id: string;
  tariffId: string;
  previousTariffId: string | null;
  action: string;
  actorId: string | null;
  actorName: string | null;
  occurredAt: string;
  effectiveAt: string | null;
  reason: string | null;
  beforeState: Record<string, unknown> | null;
  afterState: Record<string, unknown>;
}
export interface TariffHistoryPage {
  items: TariffHistoryItem[];
  page: number;
  size: number;
  total: number;
}
export interface CreateBillingCalendar {
  zoneId: string | null;
  validFrom: string;
  validTo: string | null;
  chargeableDays: number[];
  normativeReference: string;
}
export interface UpdateBillingCalendar extends Omit<CreateBillingCalendar, 'zoneId'> {
  reason: string;
  expectedVersion: number;
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

export interface ScheduleWriteRequest {
  zoneId: string | null;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  validFrom: string | null;
  validTo: string | null;
  reason: string;
}

export interface ScheduleUpdateRequest extends ScheduleWriteRequest {
  expectedUpdatedAt: string;
}

export interface DeactivateConfigurationRequest {
  expectedUpdatedAt: string;
  reason: string;
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
  updatedAt: string;
}

export interface CalendarExceptionWriteRequest {
  holidayDate: string;
  name: string;
  zoneId: string | null;
  holidayType: 'NATIONAL_HOLIDAY' | 'NON_TARIFFED' | 'SPECIAL_SCHEDULE' | 'MUNICIPAL_EXCEPTION';
  tariffed: boolean;
  startTime: string | null;
  endTime: string | null;
  normativeReference: string | null;
  validFrom: string;
  validTo: string | null;
  reason: string;
}

export interface CalendarExceptionUpdateRequest extends CalendarExceptionWriteRequest {
  expectedUpdatedAt: string;
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

  effectiveOperationalCalendar(zoneId: string, date: string, time: string) {
    return this.api.get<EffectiveOperationalCalendar>('admin/parking/schedules/effective', {
      params: new HttpParams().set('zoneId', zoneId).set('date', date).set('time', time),
    });
  }

  listCalendarExceptions() {
    return this.api.get<CalendarException[]>('admin/parking/calendar-exceptions');
  }

  createSchedule(body: ScheduleWriteRequest) {
    return this.api.post<ConfiguredSchedule>('admin/parking/schedules', body);
  }

  updateSchedule(id: string, body: ScheduleUpdateRequest) {
    return this.api.put<ConfiguredSchedule>(
      `admin/parking/schedules/${encodeURIComponent(id)}`,
      body,
    );
  }

  reviseSchedule(id: string, body: ScheduleUpdateRequest) {
    return this.api.post<ConfiguredSchedule>(
      `admin/parking/schedules/${encodeURIComponent(id)}/revise`,
      body,
    );
  }

  deactivateSchedule(id: string, body: DeactivateConfigurationRequest) {
    return this.api.post<ConfiguredSchedule>(
      `admin/parking/schedules/${encodeURIComponent(id)}/deactivate`,
      body,
    );
  }

  endSchedule(id: string, effectiveFrom: string, expectedUpdatedAt: string, reason: string) {
    return this.api.post<ConfiguredSchedule>(
      `admin/parking/schedules/${encodeURIComponent(id)}/end`,
      {
        effectiveFrom,
        expectedUpdatedAt,
        reason,
      },
    );
  }

  createCalendarException(body: CalendarExceptionWriteRequest) {
    return this.api.post<CalendarException>('admin/parking/calendar-exceptions', body);
  }

  updateCalendarException(id: string, body: CalendarExceptionUpdateRequest) {
    return this.api.put<CalendarException>(
      `admin/parking/calendar-exceptions/${encodeURIComponent(id)}`,
      body,
    );
  }

  deactivateCalendarException(id: string, body: DeactivateConfigurationRequest) {
    return this.api.post<CalendarException>(
      `admin/parking/calendar-exceptions/${encodeURIComponent(id)}/deactivate`,
      body,
    );
  }

  listBillingCalendars() {
    return this.api.get<BillingCalendar[]>('admin/parking/billing-day-calendars');
  }

  effective(at: string) {
    return this.api.get<EffectiveConfiguration>('admin/parking/configuration/effective', {
      params: new HttpParams().set('at', at),
    });
  }

  createTariff(body: CreateTariff) {
    return this.api.post<ConfiguredTariff>('admin/parking/tariffs', body);
  }

  updateScheduledTariff(id: string, body: CreateTariff) {
    return this.api.put<ConfiguredTariff>(`admin/parking/tariffs/${encodeURIComponent(id)}`, body);
  }

  cancelScheduledTariff(id: string, reason: string) {
    return this.api.post<ConfiguredTariff>(
      `admin/parking/tariffs/${encodeURIComponent(id)}/cancel`,
      { reason },
    );
  }

  reviseTariff(id: string, body: CreateTariff) {
    return this.api.post<ConfiguredTariff>(
      `admin/parking/tariffs/${encodeURIComponent(id)}/revise`,
      body,
    );
  }

  reviseTariffNow(id: string, body: ImmediateTariffRevision) {
    return this.api.post<ConfiguredTariff>(
      `admin/parking/tariffs/${encodeURIComponent(id)}/revise-now`,
      body,
    );
  }

  endTariff(id: string, effectiveAt: string, reason: string, expectedVersion: number) {
    return this.api.post<ConfiguredTariff>(`admin/parking/tariffs/${encodeURIComponent(id)}/end`, {
      effectiveAt,
      reason,
      expectedVersion,
    });
  }

  endTariffNow(id: string, reason: string, expectedVersion: number) {
    return this.api.post<ConfiguredTariff>(
      `admin/parking/tariffs/${encodeURIComponent(id)}/end-now`,
      { reason, expectedVersion },
    );
  }

  tariffHistory(page = 0, size = 25, tariffId?: string) {
    let params = new HttpParams().set('page', page).set('size', size);
    if (tariffId) params = params.set('tariffId', tariffId);
    return this.api.get<TariffHistoryPage>('admin/parking/tariffs/history', { params });
  }

  createBillingCalendar(body: CreateBillingCalendar) {
    return this.api.post<string>('admin/parking/billing-day-calendars', body);
  }

  endBillingCalendar(id: string, effectiveFrom: string, reason: string, expectedVersion: number) {
    return this.api.post<BillingCalendar>(
      `admin/parking/billing-day-calendars/${encodeURIComponent(id)}/end`,
      { effectiveFrom, reason, expectedVersion },
    );
  }

  updateScheduledBillingCalendar(id: string, body: UpdateBillingCalendar) {
    return this.api.put<BillingCalendar>(
      `admin/parking/billing-day-calendars/${encodeURIComponent(id)}`,
      body,
    );
  }

  reviseBillingCalendar(id: string, body: UpdateBillingCalendar) {
    return this.api.post<BillingCalendar>(
      `admin/parking/billing-day-calendars/${encodeURIComponent(id)}/revise`,
      body,
    );
  }

  cancelScheduledBillingCalendar(id: string, reason: string, expectedVersion: number) {
    return this.api.post<BillingCalendar>(
      `admin/parking/billing-day-calendars/${encodeURIComponent(id)}/cancel`,
      { reason, expectedVersion },
    );
  }

  billingCalendarHistory(id: string) {
    return this.api.get<BillingCalendarHistoryItem[]>(
      `admin/parking/billing-day-calendars/${encodeURIComponent(id)}/history`,
    );
  }
}
