import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { forkJoin, finalize, Observable } from 'rxjs';
import { normalizeError, PortalError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import { BillingCalendar, BillingCalendarHistoryItem, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { currentConfigurationDate, formatConfigurationDate, formatConfigurationDateTime, isValidConfigurationDate, nextConfigurationDate } from './tariffs-format';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';
const dateOnlyValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value as string | null;
  return !value || isValidConfigurationDate(value) ? null : { invalidConfigurationDate: true };
};
const futureGuayaquilDateValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value as string | null;
  return !value || !isValidConfigurationDate(value) || value > currentConfigurationDate() ? null : { dateMustBeFuture: true };
};
const validityRangeValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const from = control.get('validFrom')?.value as string | null;
  const to = control.get('validTo')?.value as string | null;
  return from && to && isValidConfigurationDate(from) && isValidConfigurationDate(to) && to < from ? { validityEndBeforeStart: true } : null;
};
const calendarErrors: Record<string, string> = {
  BILLING_CALENDAR_START_MUST_BE_FUTURE: 'La fecha de inicio debe ser posterior al día de hoy en Ecuador.',
  BILLING_CALENDAR_VALIDITY_OVERLAP: 'Ya existe un calendario para el mismo alcance en el período seleccionado.',
  INVALID_CONFIGURATION_VALIDITY: 'La fecha final debe ser igual o posterior a la fecha inicial.',
  BILLING_CALENDAR_END_MUST_BE_FUTURE: 'La fecha de finalización debe ser posterior al día de hoy en Ecuador.',
  INVALID_BILLING_CALENDAR_END: 'No se puede finalizar el calendario en esa fecha. Revise la vigencia actual.',
  BILLING_CALENDAR_NOT_SCHEDULED: 'Esta programación ya no se puede modificar o cancelar porque dejó de estar programada.',
  BILLING_CALENDAR_NOT_CURRENT: 'Este calendario ya no está vigente. Actualice el listado antes de continuar.',
  BILLING_CALENDAR_VERSION_CHANGED_REFRESH_REQUIRED: 'Otra persona modificó este calendario. Actualice el listado e inténtelo nuevamente.',
  BILLING_CALENDAR_CHANGE_REASON_REQUIRED: 'Escriba el motivo del cambio.',
};
function normalizeBillingCalendarError(error: unknown): PortalError {
  if (error instanceof PortalError && error.kind === 'conflict') return new PortalError('conflict', calendarErrors[error.code ?? ''] ?? error.message, error.code);
  if (error instanceof HttpErrorResponse && error.status === 409) {
    const message = error.error?.message;
    const code = typeof message === 'string' && /^[A-Z][A-Z0-9_]{0,95}$/.test(message) ? message : '';
    return new PortalError('conflict', calendarErrors[code] ?? 'El calendario entra en conflicto con la configuración vigente o con su estado actual. Revise el alcance y las fechas.', code || null);
  }
  if (error instanceof HttpErrorResponse && error.status === 400) {
    const fields = error.error?.fields;
    const names = fields && typeof fields === 'object' && !Array.isArray(fields) ? Object.keys(fields).filter((f) => /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(f)) : [];
    return new PortalError('validation', 'El servidor rechazó los datos del calendario. Revise las fechas y los campos obligatorios.', null, names);
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
  readonly savedVersionId = signal('');
  readonly saving = signal(false);
  readonly showForm = signal(false);
  readonly revisionOf = signal<BillingCalendar | null>(null);
  readonly editingCalendar = signal<BillingCalendar | null>(null);
  readonly endingCalendar = signal<BillingCalendar | null>(null);
  readonly cancellingCalendar = signal<BillingCalendar | null>(null);
  readonly viewingCalendar = signal<BillingCalendar | null>(null);
  readonly historyCalendar = signal<BillingCalendar | null>(null);
  readonly historyItems = signal<BillingCalendarHistoryItem[]>([]);
  readonly historyLoading = signal(false);
  readonly historyError = signal('');
  readonly query = signal('');
  readonly chargeableDays = signal<number[]>([]);
  readonly detailDialog = viewChild<ElementRef<HTMLDialogElement>>('detailDialog');
  readonly editDialog = viewChild<ElementRef<HTMLDialogElement>>('editDialog');
  readonly endDialog = viewChild<ElementRef<HTMLDialogElement>>('endDialog');
  readonly cancelDialog = viewChild<ElementRef<HTMLDialogElement>>('cancelDialog');
  readonly historyDialog = viewChild<ElementRef<HTMLDialogElement>>('historyDialog');
  readonly formatDate = formatConfigurationDate;
  readonly formatDateTime = formatConfigurationDateTime;
  readonly weekDays = [
    { value: 1, label: 'Lunes' }, { value: 2, label: 'Martes' }, { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' }, { value: 5, label: 'Viernes' }, { value: 6, label: 'Sábado' }, { value: 7, label: 'Domingo' },
  ];
  readonly form = this.builder.nonNullable.group({
    scope: ['GENERAL', Validators.required], zoneId: [''],
    validFrom: ['', [Validators.required, dateOnlyValidator, futureGuayaquilDateValidator]],
    validTo: ['', dateOnlyValidator], normativeReference: ['', [Validators.required, Validators.maxLength(255)]],
    reason: [''],
  }, { validators: validityRangeValidator });
  readonly endForm = this.builder.nonNullable.group({
    effectiveFrom: ['', [Validators.required, dateOnlyValidator, futureGuayaquilDateValidator]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });
  readonly cancellationForm = this.builder.nonNullable.group({ reason: ['', [Validators.required, Validators.maxLength(500)]] });
  readonly generalCalendar = computed(() => this.calendars().find((c) => c.id === this.effective()?.generalBillingCalendarId) ?? null);
  readonly zoneExceptions = computed(() => (this.effective()?.zones ?? []).filter((z) => z.billingDaysSource === 'ZONE'));
  readonly scheduled = computed(() => this.calendars().filter((c) => this.isScheduled(c)));
  readonly visibleCalendars = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('es-EC');
    return this.calendars().filter((c) => [c.zoneCode, c.zoneName, c.normativeReference].filter(Boolean).join(' ').toLocaleLowerCase('es-EC').includes(query));
  });

  constructor() {
    if (!this.preview) this.load();
    effect(() => { const d=this.detailDialog()?.nativeElement; if (d && this.viewingCalendar() && !d.open) d.showModal(); else if (d?.open && !this.viewingCalendar()) d.close(); });
    effect(() => { const d=this.editDialog()?.nativeElement; if (d && this.showForm() && !d.open) d.showModal(); else if (d?.open && !this.showForm()) d.close(); });
    effect(() => { const d=this.endDialog()?.nativeElement; if (d && this.endingCalendar() && !d.open) d.showModal(); else if (d?.open && !this.endingCalendar()) d.close(); });
    effect(() => { const d=this.cancelDialog()?.nativeElement; if (d && this.cancellingCalendar() && !d.open) d.showModal(); else if (d?.open && !this.cancellingCalendar()) d.close(); });
    effect(() => { const d=this.historyDialog()?.nativeElement; if (d && this.historyCalendar() && !d.open) d.showModal(); else if (d?.open && !this.historyCalendar()) d.close(); });
  }

  load() {
    if (this.preview) return;
    this.state.set('loading'); this.errorMessage.set('');
    forkJoin({ calendars: this.api.listBillingCalendars(), zones: this.zonesApi.listZones(), effective: this.api.effective(new Date().toISOString()) }).subscribe({
      next: (r) => { this.calendars.set(r.calendars); this.zones.set(r.zones); this.effective.set(r.effective); this.state.set('ready'); },
      error: (e: unknown) => { this.errorMessage.set(normalizeError(e).message || 'No se pudo consultar el calendario semanal.'); this.state.set('error'); },
    });
  }

  openCreate() {
    this.editingCalendar.set(null); this.revisionOf.set(null); this.actionError.set(''); this.chargeableDays.set([]);
    this.form.reset({ scope: 'GENERAL', zoneId: '', validFrom: '', validTo: '', normativeReference: '', reason: '' });
    this.form.controls.reason.clearValidators(); this.form.controls.reason.updateValueAndValidity(); this.showForm.set(true);
  }
  openEdit(calendar: BillingCalendar) {
    this.actionError.set(''); this.editingCalendar.set(calendar); this.revisionOf.set(this.isCurrent(calendar) ? calendar : null);
    this.chargeableDays.set(this.weekDays.filter((d) => calendar.days[String(d.value)]).map((d) => d.value));
    this.form.controls.reason.setValidators([Validators.required, Validators.maxLength(500)]);
    this.form.reset({ scope: calendar.zoneId ? 'ZONE' : 'GENERAL', zoneId: calendar.zoneId ?? '', validFrom: this.isCurrent(calendar) ? nextConfigurationDate() : calendar.validFrom,
      validTo: calendar.validTo ?? '', normativeReference: calendar.normativeReference ?? '', reason: '' });
    this.form.controls.scope.disable(); this.form.controls.zoneId.disable(); this.showForm.set(true);
  }
  openView(calendar: BillingCalendar) { this.viewingCalendar.set(calendar); }
  closeView(event?: Event) { event?.preventDefault(); this.viewingCalendar.set(null); }
  openHistory(calendar: BillingCalendar) {
    this.historyCalendar.set(calendar); this.historyItems.set([]); this.historyError.set(''); this.historyLoading.set(true);
    this.api.billingCalendarHistory(calendar.id).pipe(finalize(() => this.historyLoading.set(false))).subscribe({
      next: (items) => this.historyItems.set(items), error: (error: unknown) => this.historyError.set(normalizeBillingCalendarError(error).message),
    });
  }
  toggleDay(day: number, checked: boolean) { const set=new Set(this.chargeableDays()); checked ? set.add(day) : set.delete(day); this.chargeableDays.set([...set].sort((a,b)=>a-b)); }

  submit(event: Event) {
    event.preventDefault(); if (this.preview || this.saving()) return;
    this.form.markAllAsTouched(); this.form.updateValueAndValidity();
    const value=this.form.getRawValue();
    if (value.scope==='ZONE' && !value.zoneId) this.form.controls.zoneId.setErrors({ required: true });
    if (this.form.invalid) return;
    this.saving.set(true); this.actionError.set(''); this.actionMessage.set(''); this.savedVersionId.set('');
    const calendar=this.editingCalendar();
    const body={ validFrom:value.validFrom, validTo:value.validTo || null, chargeableDays:this.chargeableDays(), normativeReference:value.normativeReference.trim(),
      reason:value.reason.trim(), expectedVersion:calendar?.rowVersion ?? 0 };
    const request: Observable<string | BillingCalendar> = calendar
      ? (this.isCurrent(calendar) ? this.api.reviseBillingCalendar(calendar.id, body) : this.api.updateScheduledBillingCalendar(calendar.id, body))
      : this.api.createBillingCalendar({ zoneId:value.scope==='ZONE' ? value.zoneId : null, validFrom:value.validFrom, validTo:value.validTo || null,
          chargeableDays:this.chargeableDays(), normativeReference:value.normativeReference.trim() });
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (result) => {
        const id=typeof result==='string' ? result : result.id;
        this.savedVersionId.set(id);
        this.actionMessage.set(calendar ? this.isCurrent(calendar) ? 'Calendario actualizado correctamente. Se creó una nueva versión y se conservó el historial.' : 'Calendario actualizado correctamente.' : 'El calendario quedó registrado. Los días no marcados serán gratuitos, no prohibidos.');
        this.showForm.set(false); this.editingCalendar.set(null); this.revisionOf.set(null); this.form.controls.scope.enable(); this.form.controls.zoneId.enable(); this.load();
      },
      error: (error: unknown) => this.recordWriteError(error),
    });
  }

  openEnd(calendar: BillingCalendar) { this.actionError.set(''); this.endForm.reset({ effectiveFrom: '', reason: '' }); this.endingCalendar.set(calendar); }
  submitEnd(event: Event) {
    event.preventDefault(); const calendar=this.endingCalendar(); if (!calendar || this.saving()) return;
    this.endForm.markAllAsTouched(); if (this.endForm.invalid) return;
    const value=this.endForm.getRawValue(); this.saving.set(true); this.actionError.set('');
    this.api.endBillingCalendar(calendar.id,value.effectiveFrom,value.reason.trim(),calendar.rowVersion).pipe(finalize(()=>this.saving.set(false))).subscribe({
      next:()=>{ this.endingCalendar.set(null); this.actionMessage.set('Vigencia finalizada correctamente. Desde esa fecha la excepción zonal heredará el calendario general, si existe.'); this.load(); },
      error:(error:unknown)=>this.recordWriteError(error),
    });
  }
  openCancel(calendar: BillingCalendar) { this.cancellationForm.reset({ reason:'' }); this.actionError.set(''); this.cancellingCalendar.set(calendar); }
  submitCancel(event: Event) {
    event.preventDefault(); const calendar=this.cancellingCalendar(); if (!calendar || this.saving()) return;
    this.cancellationForm.markAllAsTouched(); if (this.cancellationForm.invalid) return;
    this.saving.set(true); this.actionError.set('');
    this.api.cancelScheduledBillingCalendar(calendar.id,this.cancellationForm.controls.reason.value.trim(),calendar.rowVersion).pipe(finalize(()=>this.saving.set(false))).subscribe({
      next:()=>{ this.cancellingCalendar.set(null); this.actionMessage.set('Programación cancelada correctamente. Este calendario no entrará en vigencia.'); this.load(); },
      error:(error:unknown)=>this.recordWriteError(error),
    });
  }
  cancelForms() { this.showForm.set(false); this.editingCalendar.set(null); this.revisionOf.set(null); this.endingCalendar.set(null); this.actionError.set(''); this.form.controls.scope.enable(); this.form.controls.zoneId.enable(); }
  private recordWriteError(error: unknown) {
    const normalized=normalizeBillingCalendarError(error);
    if (normalized.kind==='validation') {
      if (normalized.fieldNames.includes('validFrom')) this.form.controls.validFrom.setErrors({ ...this.form.controls.validFrom.errors, backendRejected:true });
      if (normalized.fieldNames.includes('validTo')) this.form.controls.validTo.setErrors({ ...this.form.controls.validTo.errors, backendRejected:true });
      if (normalized.fieldNames.includes('effectiveFrom')) this.endForm.controls.effectiveFrom.setErrors({ ...this.endForm.controls.effectiveFrom.errors, backendRejected:true });
    }
    this.actionError.set(normalized.message);
  }
  today() { return currentConfigurationDate(); }
  minStartDate() { return nextConfigurationDate(); }
  validFromError() {
    const c=this.form.controls.validFrom; if (!c.touched) return '';
    if (c.hasError('required')) return 'Seleccione una fecha de inicio.';
    if (c.hasError('invalidConfigurationDate')) return 'Ingrese una fecha válida.';
    if (c.hasError('dateMustBeFuture')) return 'La fecha de inicio debe ser posterior al día de hoy en Ecuador.';
    if (c.hasError('backendRejected')) return 'El servidor rechazó la fecha de inicio. Verifique que sea válida y futura en Ecuador.';
    return '';
  }
  validToError() {
    const c=this.form.controls.validTo; if (!c.touched) return '';
    if (c.hasError('invalidConfigurationDate')) return 'Ingrese una fecha final válida.';
    if (this.form.hasError('validityEndBeforeStart')) return 'La fecha final debe ser igual o posterior a la fecha inicial.';
    return '';
  }
  effectiveFromError() {
    const c=this.endForm.controls.effectiveFrom; if (!c.touched) return '';
    if (c.hasError('required')) return 'Seleccione la fecha desde la que se aplicará el cambio.';
    if (c.hasError('invalidConfigurationDate')) return 'Ingrese una fecha válida.';
    if (c.hasError('dateMustBeFuture')) return 'La fecha debe ser posterior al día de hoy en Ecuador.';
    return '';
  }
  isCurrent(c: BillingCalendar) { return c.active && !c.cancelledAt && c.validFrom<=this.today() && (!c.validTo || c.validTo>=this.today()); }
  isScheduled(c: BillingCalendar) { return c.active && !c.cancelledAt && c.validFrom>this.today(); }
  isCancelled(c: BillingCalendar) { return !!c.cancelledAt; }
  isEnded(c: BillingCalendar) { return c.active && !this.isCancelled(c) && c.validTo !== null && c.validTo<this.today(); }
  statusLabel(c: BillingCalendar) { return this.isCancelled(c)?'Cancelado':!c.active?'Deshabilitado':this.isScheduled(c)?'Programado':this.isCurrent(c)?'Vigente':this.isEnded(c)?'Finalizado':'Fuera de vigencia'; }
  zoneName(code: string|null,name: string|null) { return code&&name?`${code} · ${name}`:code??name??'General para todas las zonas'; }
  daysLabel(days: Record<string,boolean>|null|undefined) { return days?this.weekDays.filter(d=>days[String(d.value)]).map(d=>d.label).join(', ')||'Ningún día: gratuito toda la semana':'Sin calendario configurado'; }
  isDayChargeable(calendar: BillingCalendar, day: number) { return !!calendar.days[String(day)]; }
  formatJson(value: Record<string, unknown>) { return JSON.stringify(value, null, 2); }
  inheritedCalendar(zoneId: string|null) { const z=this.effective()?.zones.find(x=>x.zoneId===zoneId); const id=z?.billingCalendarId??this.effective()?.generalBillingCalendarId; return this.calendars().find(c=>c.id===id)??null; }
}
