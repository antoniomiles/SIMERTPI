import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, finalize, switchMap, tap } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import { BillingCalendar, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { currentConfigurationDate, formatConfigurationDate } from './tariffs-format';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';

@Component({
  selector: 'app-billing-days',
  imports: [ReactiveFormsModule, DataState, PageHeading],
  templateUrl: './billing-days.html',
  styleUrl: './tariffs.scss',
})
export class BillingDays {
  private readonly api = inject(TariffsApi);
  private readonly zonesApi = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some((route) => route.data['preview'] === true);
  readonly calendars = signal<BillingCalendar[]>([]);
  readonly zones = signal<ParkingZone[]>([]);
  readonly effective = signal<EffectiveConfiguration | null>(null);
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly errorMessage = signal('');
  readonly actionError = signal('');
  readonly actionMessage = signal('');
  readonly saving = signal(false);
  readonly showForm = signal(false);
  readonly revisionOf = signal<BillingCalendar | null>(null);
  readonly endingCalendar = signal<BillingCalendar | null>(null);
  readonly query = signal('');
  readonly chargeableDays = signal<number[]>([]);
  readonly formatDate = formatConfigurationDate;
  readonly weekDays = [
    { value: 1, label: 'Lunes' }, { value: 2, label: 'Martes' }, { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' }, { value: 5, label: 'Viernes' }, { value: 6, label: 'Sábado' },
    { value: 7, label: 'Domingo' },
  ];
  readonly form = this.builder.nonNullable.group({
    scope: ['GENERAL', Validators.required], zoneId: [''], validFrom: ['', Validators.required],
    validTo: [''], normativeReference: ['', [Validators.required, Validators.maxLength(255)]],
  });
  readonly endForm = this.builder.nonNullable.group({ effectiveFrom: ['', Validators.required] });
  readonly generalCalendar = computed(() => {
    const id = this.effective()?.generalBillingCalendarId;
    return this.calendars().find((calendar) => calendar.id === id) ?? null;
  });
  readonly zoneExceptions = computed(() => (this.effective()?.zones ?? []).filter((zone) => zone.billingDaysSource === 'ZONE'));
  readonly scheduled = computed(() => this.calendars().filter((calendar) => calendar.active && calendar.validFrom > this.today()));
  readonly visibleCalendars = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('es-EC');
    return this.calendars().filter((calendar) => [calendar.zoneCode, calendar.zoneName, calendar.normativeReference]
      .filter(Boolean).join(' ').toLocaleLowerCase('es-EC').includes(query));
  });

  constructor() { if (!this.preview) this.load(); }

  load() {
    if (this.preview) return;
    this.state.set('loading');
    this.errorMessage.set('');
    forkJoin({ calendars: this.api.listBillingCalendars(), zones: this.zonesApi.listZones(), effective: this.api.effective(new Date().toISOString()) })
      .subscribe({
        next: (result) => {
          this.calendars.set(result.calendars);
          this.zones.set(result.zones);
          this.effective.set(result.effective);
          this.state.set('ready');
        },
        error: (error: unknown) => {
          this.errorMessage.set(normalizeError(error).message || 'No se pudo consultar el calendario semanal.');
          this.state.set('error');
        },
      });
  }

  openCreate() {
    this.revisionOf.set(null);
    this.actionError.set('');
    this.actionMessage.set('');
    this.chargeableDays.set([]);
    this.form.reset({ scope: 'GENERAL', zoneId: '', validFrom: '', validTo: '', normativeReference: '' });
    this.showForm.set(true);
  }

  openRevision(calendar: BillingCalendar) {
    this.endingCalendar.set(null);
    this.revisionOf.set(calendar);
    this.actionError.set('');
    this.actionMessage.set('');
    this.chargeableDays.set(this.weekDays.filter((day) => calendar.days[String(day.value)]).map((day) => day.value));
    this.form.reset({ scope: calendar.zoneId ? 'ZONE' : 'GENERAL', zoneId: calendar.zoneId ?? '', validFrom: '', validTo: '', normativeReference: calendar.normativeReference });
    this.showForm.set(true);
  }

  toggleDay(day: number, checked: boolean) {
    const selected = new Set(this.chargeableDays());
    checked ? selected.add(day) : selected.delete(day);
    this.chargeableDays.set([...selected].sort((a, b) => a - b));
  }

  submit(event: Event) {
    event.preventDefault();
    if (this.preview || this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const value = this.form.getRawValue();
    if (value.scope === 'ZONE' && !value.zoneId) {
      this.form.controls.zoneId.setErrors({ required: true });
      return;
    }
    if (value.validTo && value.validTo < value.validFrom) {
      this.actionError.set('La fecha final debe ser igual o posterior a la fecha inicial.');
      return;
    }
    this.saving.set(true);
    this.actionError.set('');
    this.actionMessage.set('');
    let previousVersionEnded = false;
    const revision = this.revisionOf();
    const body = {
      zoneId: revision?.zoneId ?? (value.scope === 'ZONE' ? value.zoneId : null),
      validFrom: value.validFrom,
      validTo: value.validTo || null,
      chargeableDays: this.chargeableDays(),
      normativeReference: value.normativeReference.trim(),
    };
    const save = revision
      ? this.api.endBillingCalendar(revision.id, value.validFrom).pipe(
          tap(() => previousVersionEnded = true),
          switchMap(() => this.api.createBillingCalendar(body)),
        )
      : this.api.createBillingCalendar(body);
    save.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.showForm.set(false);
        this.revisionOf.set(null);
        this.actionMessage.set('El calendario quedó registrado con su vigencia y referencia normativa. Los días no marcados serán gratuitos, no prohibidos.');
        this.load();
      },
      error: (error: unknown) => {
        this.actionError.set(previousVersionEnded
          ? 'La versión anterior quedó finalizada antes del inicio indicado, pero el nuevo calendario no se confirmó. Revise la configuración antes de continuar.'
          : normalizeError(error).message);
        if (previousVersionEnded) this.load();
      },
    });
  }

  openEnd(calendar: BillingCalendar) {
    this.showForm.set(false);
    this.endingCalendar.set(calendar);
    this.actionError.set('');
    this.endForm.reset({ effectiveFrom: '' });
  }

  submitEnd(event: Event) {
    event.preventDefault();
    const calendar = this.endingCalendar();
    const effectiveFrom = this.endForm.getRawValue().effectiveFrom;
    if (!calendar || this.endForm.invalid || this.saving()) { this.endForm.markAllAsTouched(); return; }
    this.saving.set(true);
    this.api.endBillingCalendar(calendar.id, effectiveFrom).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => { this.endingCalendar.set(null); this.actionMessage.set('La finalización quedó registrada; desde esa fecha la zona volverá a heredar el calendario general.'); this.load(); },
      error: (error: unknown) => this.actionError.set(normalizeError(error).message),
    });
  }

  cancelForms() { this.showForm.set(false); this.revisionOf.set(null); this.endingCalendar.set(null); this.actionError.set(''); }
  today() { return currentConfigurationDate(); }
  isCurrent(calendar: BillingCalendar) { return calendar.active && calendar.validFrom <= this.today() && (!calendar.validTo || calendar.validTo >= this.today()); }
  isScheduled(calendar: BillingCalendar) { return calendar.active && calendar.validFrom > this.today(); }
  canModify(calendar: BillingCalendar) { return this.isCurrent(calendar) && !calendar.validTo; }
  zoneName(code: string | null, name: string | null) { return code && name ? `${code} · ${name}` : code ?? name ?? 'General para todas las zonas'; }
  daysLabel(days: Record<string, boolean> | null | undefined) {
    return days ? this.weekDays.filter((day) => days[String(day.value)]).map((day) => day.label).join(', ') || 'Ningún día: gratuito toda la semana' : 'Sin calendario configurado';
  }
  inheritedCalendar(zoneId: string | null) {
    const effectiveZone = this.effective()?.zones.find((zone) => zone.zoneId === zoneId);
    const id = effectiveZone?.billingCalendarId ?? this.effective()?.generalBillingCalendarId;
    return this.calendars().find((calendar) => calendar.id === id) ?? null;
  }
}
