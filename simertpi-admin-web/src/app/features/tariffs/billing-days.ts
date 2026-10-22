import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { forkJoin, finalize, switchMap, tap } from 'rxjs';
import { normalizeError, PortalError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import { BillingCalendar, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { currentConfigurationDate, formatConfigurationDate, isValidConfigurationDate, nextConfigurationDate } from './tariffs-format';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';

const dateOnlyValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value as string | null;
  return !value || isValidConfigurationDate(value) ? null : { invalidConfigurationDate: true };
};

const futureGuayaquilDateValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value as string | null;
  return !value || !isValidConfigurationDate(value) || value > currentConfigurationDate()
    ? null
    : { dateMustBeFuture: true };
};

const validityRangeValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const from = control.get('validFrom')?.value as string | null;
  const to = control.get('validTo')?.value as string | null;
  return from && to && isValidConfigurationDate(from) && isValidConfigurationDate(to) && to < from
    ? { validityEndBeforeStart: true }
    : null;
};

const billingCalendarConflictMessages: Record<string, string> = {
  BILLING_CALENDAR_START_MUST_BE_FUTURE: 'La fecha de inicio debe ser posterior al día de hoy en Ecuador.',
  BILLING_CALENDAR_VALIDITY_OVERLAP: 'Ya existe un calendario para el mismo alcance en el período seleccionado.',
  INVALID_CONFIGURATION_VALIDITY: 'La fecha final debe ser igual o posterior a la fecha inicial.',
  BILLING_CALENDAR_END_MUST_BE_FUTURE: 'La fecha de finalización debe ser posterior al día de hoy en Ecuador.',
  INVALID_BILLING_CALENDAR_END: 'No se puede finalizar el calendario en esa fecha. Revise la vigencia actual.',
};

function normalizeBillingCalendarError(error: unknown): PortalError {
  if (error instanceof PortalError && error.kind === 'conflict') {
    return new PortalError('conflict', billingCalendarConflictMessages[error.code ?? '']
      ?? 'El calendario entra en conflicto con la configuración vigente o con su estado actual. Revise el alcance y las fechas.', error.code);
  }
  if (error instanceof HttpErrorResponse && error.status === 409) {
    const message = error.error?.message;
    const code = typeof message === 'string' && /^[A-Z][A-Z0-9_]{0,95}$/.test(message) ? message : '';
    return new PortalError('conflict', billingCalendarConflictMessages[code]
      ?? 'El calendario entra en conflicto con la configuración vigente o con su estado actual. Revise el alcance y las fechas.', code || null);
  }
  if (error instanceof HttpErrorResponse && error.status === 400) {
    const fields = error.error?.fields;
    const fieldNames = fields && typeof fields === 'object' && !Array.isArray(fields)
      ? Object.keys(fields).filter((field) => /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(field))
      : [];
    return new PortalError('validation', 'El servidor rechazó los datos del calendario. Revise las fechas y los campos obligatorios.', null, fieldNames);
  }
  return normalizeError(error);
}

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
    scope: ['GENERAL', Validators.required], zoneId: [''],
    validFrom: ['', [Validators.required, dateOnlyValidator, futureGuayaquilDateValidator]],
    validTo: ['', dateOnlyValidator],
    normativeReference: ['', [Validators.required, Validators.maxLength(255)]],
  }, { validators: validityRangeValidator });
  readonly endForm = this.builder.nonNullable.group({
    effectiveFrom: ['', [Validators.required, dateOnlyValidator, futureGuayaquilDateValidator]],
  });
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
    this.form.updateValueAndValidity();
    if (this.form.invalid) return;
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
        this.recordWriteError(error, previousVersionEnded);
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
    if (!calendar || this.saving()) return;
    this.endForm.markAllAsTouched();
    this.endForm.updateValueAndValidity();
    if (this.endForm.invalid) return;
    this.saving.set(true);
    this.api.endBillingCalendar(calendar.id, effectiveFrom).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => { this.endingCalendar.set(null); this.actionMessage.set('La finalización quedó registrada; desde esa fecha la zona volverá a heredar el calendario general.'); this.load(); },
      error: (error: unknown) => this.recordWriteError(error),
    });
  }

  cancelForms() { this.showForm.set(false); this.revisionOf.set(null); this.endingCalendar.set(null); this.actionError.set(''); }
  private recordWriteError(error: unknown, previousVersionEnded = false) {
    const normalized = normalizeBillingCalendarError(error);
    if (normalized.kind === 'validation') {
      if (normalized.fieldNames.includes('validFrom'))
        this.form.controls.validFrom.setErrors({ ...this.form.controls.validFrom.errors, backendRejected: true });
      if (normalized.fieldNames.includes('validTo'))
        this.form.controls.validTo.setErrors({ ...this.form.controls.validTo.errors, backendRejected: true });
      if (normalized.fieldNames.includes('effectiveFrom'))
        this.endForm.controls.effectiveFrom.setErrors({ ...this.endForm.controls.effectiveFrom.errors, backendRejected: true });
    }
    this.actionError.set(previousVersionEnded
      ? `La versión anterior quedó finalizada antes del inicio indicado, pero el nuevo calendario no se confirmó. ${normalized.message}`
      : normalized.message);
  }
  today() { return currentConfigurationDate(); }
  minStartDate() { return nextConfigurationDate(); }
  validFromError() {
    const control = this.form.controls.validFrom;
    if (!control.touched) return '';
    if (control.hasError('required')) return 'Seleccione una fecha de inicio.';
    if (control.hasError('invalidConfigurationDate')) return 'Ingrese una fecha válida.';
    if (control.hasError('dateMustBeFuture')) return 'La fecha de inicio debe ser posterior al día de hoy en Ecuador.';
    if (control.hasError('backendRejected')) return 'El servidor rechazó la fecha de inicio. Verifique que sea válida y futura en Ecuador.';
    return '';
  }
  validToError() {
    const control = this.form.controls.validTo;
    if (!control.touched) return '';
    if (control.hasError('invalidConfigurationDate')) return 'Ingrese una fecha final válida.';
    if (this.form.hasError('validityEndBeforeStart')) return 'La fecha final debe ser igual o posterior a la fecha inicial.';
    if (control.hasError('backendRejected')) return 'El servidor rechazó la fecha final. Revise su relación con la fecha inicial.';
    return '';
  }
  effectiveFromError() {
    const control = this.endForm.controls.effectiveFrom;
    if (!control.touched) return '';
    if (control.hasError('required')) return 'Seleccione la fecha desde la que se aplicará el cambio.';
    if (control.hasError('invalidConfigurationDate')) return 'Ingrese una fecha válida.';
    if (control.hasError('dateMustBeFuture')) return 'La fecha debe ser posterior al día de hoy en Ecuador.';
    if (control.hasError('backendRejected')) return 'El servidor rechazó esta fecha. Verifique que sea futura en Ecuador.';
    return '';
  }
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
