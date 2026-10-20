import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { ZonesApi } from '../zones/zones-api';
import { BillingCalendar, CalendarException, ConfiguredSchedule, ConfiguredTariff, EffectiveConfiguration, TariffsApi } from './tariffs-api';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';

@Component({
  selector: 'app-tariffs',
  imports: [DatePipe, DecimalPipe, FormsModule, DataState, PageHeading],
  templateUrl: './tariffs.html',
  styleUrl: './tariffs.scss',
})
export class Tariffs {
  private readonly api = inject(TariffsApi);
  private readonly zonesApi = inject(ZonesApi);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly tariffs = signal<ConfiguredTariff[]>([]);
  readonly schedules = signal<ConfiguredSchedule[]>([]);
  readonly exceptions = signal<CalendarException[]>([]);
  readonly billingCalendars = signal<BillingCalendar[]>([]);
  readonly zones = signal<{ id: string; code: string; name: string; active: boolean }[]>([]);
  readonly effective = signal<EffectiveConfiguration | null>(null);
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly errorMessage = signal('');
  readonly query = signal('');
  readonly statusFilter = signal<'all' | 'enabled' | 'disabled'>('all');
  readonly saving = signal(false);
  readonly actionError = signal('');
  readonly actionMessage = signal('');
  readonly tariffForm = {
    code: '', name: '', scope: 'GENERAL', zoneId: '', currency: '', amount: '',
    durationMinutes: null as number | null, minMinutes: null as number | null,
    maxContinuousMinutes: null as number | null, gracePeriodMinutes: null as number | null,
    roundingMode: 'HALF_UP', validFrom: '',
    validTo: '', normativeReference: '',
  };
  readonly billingForm = { scope: 'GENERAL', zoneId: '', validFrom: '',
    validTo: '', normativeReference: '', chargeableDays: [] as number[] };
  readonly weekDays = [
    { value: 1, label: 'Lunes' }, { value: 2, label: 'Martes' }, { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' }, { value: 5, label: 'Viernes' }, { value: 6, label: 'Sábado' },
    { value: 7, label: 'Domingo' },
  ];

  readonly filteredTariffs = computed(() =>
    this.tariffs().filter((row) => this.matches(this.tariffText(row), row.active)),
  );
  readonly filteredSchedules = computed(() =>
    this.schedules().filter((row) => this.matches(this.scheduleText(row), row.active)),
  );
  readonly filteredExceptions = computed(() =>
    this.exceptions().filter((row) => this.matches(this.exceptionText(row), row.active)),
  );

  constructor() {
    if (!this.preview) this.load();
  }

  load() {
    this.state.set('loading');
    this.errorMessage.set('');
    forkJoin({
      tariffs: this.api.listTariffs(),
      schedules: this.api.listSchedules(),
      exceptions: this.api.listCalendarExceptions(),
      billingCalendars: this.api.listBillingCalendars(),
      zones: this.zonesApi.listZones(),
      effective: this.api.effective(new Date().toISOString()),
    }).subscribe({
      next: (data) => {
        this.tariffs.set(data.tariffs);
        this.schedules.set(data.schedules);
        this.exceptions.set(data.exceptions);
        this.billingCalendars.set(data.billingCalendars);
        this.zones.set(data.zones);
        this.effective.set(data.effective);
        this.state.set('ready');
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(normalizeError(error).message || 'No se pudo consultar la configuración.');
        this.state.set('error');
      },
    });
  }

  private matches(text: string, active: boolean) {
    const query = this.query().trim().toLocaleLowerCase();
    const status = this.statusFilter();
    return (!query || text.toLocaleLowerCase().includes(query)) &&
      (status === 'all' || (status === 'enabled' ? active : !active));
  }

  private tariffText(row: ConfiguredTariff) {
    return [row.code, row.name, row.zoneCode, row.zoneName, row.currency, row.roundingMode].filter(Boolean).join(' ');
  }

  private scheduleText(row: ConfiguredSchedule) {
    return [row.zoneCode, row.zoneName, this.dayName(row.dayOfWeek), row.startTime, row.endTime].filter(Boolean).join(' ');
  }

  private exceptionText(row: CalendarException) {
    return [row.name, row.holidayType, row.zoneCode, row.zoneName, row.normativeReference].filter(Boolean).join(' ');
  }

  dayName(day: number) {
    return ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'][day - 1] ?? `Día ${day}`;
  }

  displayZone(code: string | null, name: string | null) {
    return code && name ? `${code} · ${name}` : code ?? name ?? 'Todas las zonas';
  }

  toggleChargeableDay(day: number, checked: boolean) {
    const selected = new Set(this.billingForm.chargeableDays);
    checked ? selected.add(day) : selected.delete(day);
    this.billingForm.chargeableDays = [...selected].sort((a, b) => a - b);
  }

  saveTariff(event: Event) {
    event.preventDefault();
    if (!this.tariffForm.validFrom || !this.tariffForm.currency || !this.tariffForm.amount
        || this.tariffForm.durationMinutes === null || this.tariffForm.minMinutes === null
        || this.tariffForm.maxContinuousMinutes === null || this.tariffForm.gracePeriodMinutes === null
        || (this.tariffForm.scope === 'ZONE' && !this.tariffForm.zoneId)) {
      this.actionError.set('Complete todos los valores tarifarios y la vigencia respaldados por la norma aplicable.');
      return;
    }
    this.saving.set(true); this.actionError.set(''); this.actionMessage.set('');
    const start = new Date(this.tariffForm.validFrom);
    const end = this.tariffForm.validTo ? new Date(this.tariffForm.validTo) : null;
    this.api.createTariff({
      code: this.tariffForm.code.trim(), name: this.tariffForm.name.trim(),
      zoneId: this.tariffForm.scope === 'ZONE' ? this.tariffForm.zoneId : null,
      currency: this.tariffForm.currency, amount: this.tariffForm.amount,
      durationMinutes: this.tariffForm.durationMinutes, minMinutes: this.tariffForm.minMinutes,
      maxContinuousMinutes: this.tariffForm.maxContinuousMinutes, gracePeriodMinutes: this.tariffForm.gracePeriodMinutes,
      roundingMode: this.tariffForm.roundingMode, validFrom: start.toISOString(),
      validTo: end?.toISOString() ?? null, normativeReference: this.tariffForm.normativeReference.trim(),
    }).subscribe({
      next: () => { this.actionMessage.set('Versión tarifaria registrada.'); this.reloadConfiguration(); },
      error: (error: unknown) => { this.actionError.set(normalizeError(error).message); this.saving.set(false); },
    });
  }

  saveBillingDays(event: Event) {
    event.preventDefault();
    this.saving.set(true); this.actionError.set(''); this.actionMessage.set('');
    this.api.createBillingCalendar({
      zoneId: this.billingForm.scope === 'ZONE' ? this.billingForm.zoneId : null,
      validFrom: this.billingForm.validFrom, validTo: this.billingForm.validTo || null,
      chargeableDays: this.billingForm.chargeableDays,
      normativeReference: this.billingForm.normativeReference.trim(),
    }).subscribe({
      next: () => { this.actionMessage.set('Calendario semanal registrado. Los días no seleccionados serán gratuitos durante el horario operativo.'); this.reloadConfiguration(); },
      error: (error: unknown) => { this.actionError.set(normalizeError(error).message); this.saving.set(false); },
    });
  }

  endTariff(row: ConfiguredTariff) {
    const effectiveAt = prompt('Fecha y hora futura desde la que esta versión deja de regir (ISO 8601 con zona horaria):');
    if (!effectiveAt) return;
    this.saving.set(true); this.actionError.set('');
    this.api.endTariff(row.id, effectiveAt).subscribe({
      next: () => { this.actionMessage.set('La versión finalizará en la fecha indicada; las sesiones existentes conservan su referencia tarifaria.'); this.reloadConfiguration(); },
      error: (error: unknown) => { this.actionError.set(normalizeError(error).message); this.saving.set(false); },
    });
  }

  endBillingCalendar(row: BillingCalendar) {
    const effectiveFrom = prompt('Fecha futura desde la que esta zona volverá a heredar el calendario general (AAAA-MM-DD):');
    if (!effectiveFrom) return;
    this.saving.set(true); this.actionError.set('');
    this.api.endBillingCalendar(row.id, effectiveFrom).subscribe({
      next: () => { this.actionMessage.set('La excepción finalizará; desde esa fecha se aplicará la herencia general.'); this.reloadConfiguration(); },
      error: (error: unknown) => { this.actionError.set(normalizeError(error).message); this.saving.set(false); },
    });
  }

  effectiveForZone(zoneId: string) { return this.effective()?.zones.find((zone) => zone.zoneId === zoneId) ?? null; }

  chargeableDayLabel(days: Record<string, boolean>) {
    return this.weekDays.filter((day) => days[String(day.value)]).map((day) => day.label).join(', ') || 'Ningún día: gratuito toda la semana';
  }

  private reloadConfiguration() {
    this.saving.set(false);
    this.load();
  }

}
