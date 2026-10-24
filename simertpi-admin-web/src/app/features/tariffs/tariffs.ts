import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { catchError, forkJoin, finalize, map, of, switchMap } from 'rxjs';
import { normalizeError, PortalError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { OperationToast } from '../../shared/ui/operation-toast';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import { ConfiguredTariff, EffectiveConfiguration, TariffsApi } from './tariffs-api';
import { formatConfigurationDate, formatConfigurationDateTime, formatConfigurationAmount, isoToGuayaquilDateTimeLocal, localDateTimeToIso } from './tariffs-format';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';

const TARIFF_CONFLICT_MESSAGES: Record<string, string> = {
  TARIFF_VALIDITY_OVERLAP: 'Ya existe una tarifa con una vigencia que se cruza para esta misma aplicación.',
  TARIFF_CODE_ALREADY_EXISTS: 'El código de tarifa ya está registrado. Use un código diferente.',
  TARIFF_START_MUST_BE_FUTURE: 'La vigencia de la nueva tarifa debe comenzar en una fecha futura.',
  INVALID_CONFIGURATION_VALIDITY: 'La fecha final debe ser posterior a la fecha de inicio.',
  TARIFF_SCHEDULE_NO_LONGER_EDITABLE: 'La tarifa ya inició su vigencia y no se puede editar como programación.',
  TARIFF_SCHEDULE_NO_LONGER_CANCELLABLE: 'La tarifa ya no está programada y no se puede cancelar.',
  TARIFF_SCOPE_CANNOT_CHANGE: 'No se puede cambiar el alcance de una tarifa programada; cancele la programación y registre otra.',
  TARIFF_CANCELLATION_REASON_REQUIRED: 'Ingrese el motivo de cancelación.',
  INVALID_TARIFF_AMOUNT: 'El importe debe ser mayor que cero y tener como máximo dos decimales.',
  INVALID_TARIFF_DURATION_RANGE: 'Revise la duración, el mínimo facturable y el máximo continuo.',
  INVALID_TARIFF_CURRENCY: 'La moneda indicada no es válida.',
};

function normalizeTariffWriteError(error: unknown) {
  if (error instanceof PortalError && error.kind === 'conflict') {
    return { message: TARIFF_CONFLICT_MESSAGES[error.code ?? ''] ?? error.message };
  }
  if (error instanceof HttpErrorResponse && error.status === 409) {
    const code = typeof error.error?.message === 'string' ? error.error.message : '';
    return {
      message:
        TARIFF_CONFLICT_MESSAGES[code] ??
        'La tarifa entra en conflicto con una configuración existente. Revise el código y las vigencias.',
    };
  }
  return normalizeError(error);
}

@Component({
  selector: 'app-tariffs',
  imports: [ReactiveFormsModule, RouterLink, NgTemplateOutlet, DataState, PageHeading],
  templateUrl: './tariffs.html',
  styleUrl: './tariffs.scss',
})
export class Tariffs {
  private readonly api = inject(TariffsApi);
  private readonly zonesApi = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  private readonly toast = inject(OperationToast);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some((route) => route.data['preview'] === true);
  readonly tariffs = signal<ConfiguredTariff[]>([]);
  readonly zones = signal<ParkingZone[]>([]);
  readonly effective = signal<EffectiveConfiguration | null>(null);
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly errorMessage = signal('');
  readonly actionError = signal('');
  readonly actionMessage = signal('');
  readonly savedVersionId = signal('');
  readonly saving = signal(false);
  readonly showForm = signal(false);
  readonly viewingTariff = signal<ConfiguredTariff | null>(null);
  readonly detailDialog = viewChild<ElementRef<HTMLDialogElement>>('detailDialog');
  readonly cancelDialog = viewChild<ElementRef<HTMLDialogElement>>('cancelDialog');
  readonly endDialog = viewChild<ElementRef<HTMLDialogElement>>('endDialog');
  readonly confirmEditDiscard = signal(false);
  readonly revisionOf = signal<ConfiguredTariff | null>(null);
  readonly revisionTiming = signal<'now' | 'scheduled'>('scheduled');
  readonly endingTariff = signal<ConfiguredTariff | null>(null);
  readonly endTiming = signal<'now' | 'scheduled'>('scheduled');
  readonly query = signal('');
  readonly statusFilter = signal<'all' | 'vigentes' | 'programadas' | 'finalizadas' | 'canceladas'>('all');
  readonly formatDate = formatConfigurationDate;
  readonly formatDateTime = formatConfigurationDateTime;
  readonly formatAmount = formatConfigurationAmount;

  readonly form = this.builder.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(50)]],
    name: ['', [Validators.required, Validators.maxLength(150)]],
    scope: ['GENERAL', Validators.required],
    zoneId: [''],
    currency: ['', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]],
    amount: ['', [Validators.required, Validators.pattern(/^[0-9]{1,10}(?:[.,][0-9]{1,2})?$/)]],
    durationMinutes: [null as number | null, [Validators.required, Validators.min(1)]],
    minMinutes: [null as number | null, [Validators.required, Validators.min(1)]],
    maxContinuousMinutes: [null as number | null, [Validators.required, Validators.min(1)]],
    gracePeriodMinutes: [0, [Validators.required, Validators.min(0)]],
    roundingMode: ['HALF_UP', Validators.required],
    validFrom: ['', Validators.required],
    validTo: [''],
    normativeReference: ['', [Validators.required, Validators.maxLength(255)]],
    reason: [''],
    expectedVersion: [null as number | null],
  });
  readonly endForm = this.builder.nonNullable.group({ validTo: ['', Validators.required], reason: ['', [Validators.required, Validators.maxLength(500)]] });
  readonly cancellationForm = this.builder.nonNullable.group({ reason: ['', [Validators.required, Validators.maxLength(500)]] });
  readonly editingScheduled = signal<ConfiguredTariff | null>(null);
  readonly cancellingTariff = signal<ConfiguredTariff | null>(null);
  private listRequestSequence = 0;

  readonly visibleTariffs = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('es-EC');
    const now = Date.now();
    return this.tariffs().filter((row) => {
      const text = [row.code, row.name, row.zoneCode, row.zoneName, row.currency, row.normativeReference]
        .filter(Boolean).join(' ').toLocaleLowerCase('es-EC');
      const starts = Date.parse(row.validFrom);
      const ends = row.validTo ? Date.parse(row.validTo) : Number.POSITIVE_INFINITY;
      const status = row.cancelledAt ? 'canceladas' : !row.active || ends < now ? 'finalizadas' : starts > now ? 'programadas' : 'vigentes';
      return (!query || text.includes(query)) && (this.statusFilter() === 'all' || status === this.statusFilter());
    });
  });
  readonly currentGeneral = computed(() => this.effective()?.generalTariff ?? null);
  readonly currentExceptions = computed(() => (this.effective()?.zones ?? []).filter((zone) => zone.tariffSource === 'ZONE'));
  readonly scheduledTariffs = computed(() => this.tariffs().filter((row) => row.active && !row.cancelledAt && Date.parse(row.validFrom) > Date.now()));

  constructor() {
    effect(() => {
      const viewing = this.viewingTariff();
      const detail = this.detailDialog()?.nativeElement;
      if (detail && viewing && !detail.open) detail.showModal();
      else if (detail && !viewing && detail.open) detail.close();

      const ending = this.endingTariff();
      const endDialog = this.endDialog()?.nativeElement;
      if (endDialog && ending && !endDialog.open) endDialog.showModal();
      else if (endDialog && !ending && endDialog.open) endDialog.close();

      const cancelling = this.cancellingTariff();
      const cancelDialog = this.cancelDialog()?.nativeElement;
      if (cancelDialog && cancelling && !cancelDialog.open) cancelDialog.showModal();
      else if (cancelDialog && !cancelling && cancelDialog.open) cancelDialog.close();
    });
    if (!this.preview) this.load();
  }

  load() {
    if (this.preview) return;
    const requestSequence = ++this.listRequestSequence;
    this.state.set('loading');
    this.errorMessage.set('');
    this.loadConfiguration()
      .subscribe({
        next: (result) => {
          if (requestSequence === this.listRequestSequence) this.applyConfiguration(result);
        },
        error: (error: unknown) => {
          if (requestSequence === this.listRequestSequence) {
            this.errorMessage.set(normalizeError(error).message || 'No se pudo consultar la configuración de tarifas.');
            this.state.set('error');
          }
        },
      });
  }

  private loadConfiguration() {
    return forkJoin({ tariffs: this.api.listTariffs(), zones: this.zonesApi.listZones(), effective: this.api.effective(new Date().toISOString()) });
  }

  private applyConfiguration(result: { tariffs: ConfiguredTariff[]; zones: ParkingZone[]; effective: EffectiveConfiguration }) {
    this.tariffs.set(result.tariffs);
    this.zones.set(result.zones);
    this.effective.set(result.effective);
    this.state.set('ready');
  }

  private refreshAfterWrite(saved: ConfiguredTariff, successMessage: string) {
    const requestSequence = ++this.listRequestSequence;
    return this.loadConfiguration().pipe(
      map((configuration) => ({ saved, configuration, refreshed: true as const })),
      catchError(() => of({ saved, configuration: null, refreshed: false as const })),
      map((result) => {
        if (requestSequence === this.listRequestSequence && result.configuration) this.applyConfiguration(result.configuration);
        return { ...result, successMessage };
      }),
    );
  }

  openCreate() {
    this.revisionOf.set(null);
    this.editingScheduled.set(null);
    this.cancellingTariff.set(null);
    this.actionError.set('');
    this.actionMessage.set('');
    this.savedVersionId.set('');
    this.form.controls.reason.clearValidators();
    this.form.reset({ code: '', name: '', scope: 'GENERAL', zoneId: '', currency: '', amount: '', durationMinutes: null, minMinutes: null, maxContinuousMinutes: null, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '', validTo: '', normativeReference: '', reason: '' });
    this.showForm.set(true);
  }

  openRevision(row: ConfiguredTariff) {
    if (!this.canModify(row)) return;
    this.actionError.set('');
    this.actionMessage.set('');
    this.savedVersionId.set('');
    this.endingTariff.set(null);
    this.editingScheduled.set(null);
    this.cancellingTariff.set(null);
    this.revisionOf.set(row);
    this.revisionTiming.set('scheduled');
    this.showForm.set(false);
    this.confirmEditDiscard.set(false);
    this.form.controls.reason.setValidators([Validators.required, Validators.maxLength(500)]);
    this.form.reset({ code: row.code, name: row.name, scope: row.zoneId ? 'ZONE' : 'GENERAL', zoneId: row.zoneId ?? '', currency: row.currency ?? '', amount: String(row.amount).replace('.', ','), durationMinutes: row.durationMinutes, minMinutes: row.minMinutes, maxContinuousMinutes: row.maxContinuousMinutes, gracePeriodMinutes: row.gracePeriodMinutes ?? 0, roundingMode: row.roundingMode ?? 'HALF_UP', validFrom: '', validTo: isoToGuayaquilDateTimeLocal(row.validTo), normativeReference: row.normativeReference ?? '', reason: '', expectedVersion: row.rowVersion });
    this.form.updateValueAndValidity();
    this.setRevisionTiming('scheduled');
  }

  setRevisionTiming(timing: 'now' | 'scheduled') {
    this.revisionTiming.set(timing);
    const control = this.form.controls.validFrom;
    control.setValidators(timing === 'scheduled' ? [Validators.required] : []);
    if (timing === 'now') control.setValue('');
    control.updateValueAndValidity();
  }

  openEditScheduled(row: ConfiguredTariff) {
    if (!this.isScheduled(row)) return;
    this.actionError.set(''); this.actionMessage.set(''); this.showForm.set(false);
    this.revisionOf.set(null); this.endingTariff.set(null); this.cancellingTariff.set(null);
    this.viewingTariff.set(null);
    this.confirmEditDiscard.set(false);
    this.editingScheduled.set(row);
    this.form.controls.reason.setValidators([Validators.required, Validators.maxLength(500)]);
    this.form.reset({ code: row.code, name: row.name, scope: row.zoneId ? 'ZONE' : 'GENERAL', zoneId: row.zoneId ?? '',
      currency: row.currency ?? '', amount: String(row.amount).replace('.', ','), durationMinutes: row.durationMinutes,
      minMinutes: row.minMinutes, maxContinuousMinutes: row.maxContinuousMinutes, gracePeriodMinutes: row.gracePeriodMinutes ?? 0,
      roundingMode: row.roundingMode ?? 'HALF_UP', validFrom: isoToGuayaquilDateTimeLocal(row.validFrom),
      validTo: isoToGuayaquilDateTimeLocal(row.validTo), normativeReference: row.normativeReference ?? '',
      reason: '', expectedVersion: row.rowVersion });
    this.form.updateValueAndValidity();
  }

  openView(row: ConfiguredTariff) {
    this.viewingTariff.set(row);
  }

  closeView(event?: Event) {
    event?.preventDefault();
    this.viewingTariff.set(null);
  }

  requestEditClose(event?: Event) {
    event?.preventDefault();
    if (this.saving()) return;
    if (this.form.dirty) {
      this.confirmEditDiscard.set(true);
      return;
    }
    this.closeScheduledEditor();
  }

  closeScheduledEditor() {
    this.confirmEditDiscard.set(false);
    this.editingScheduled.set(null);
    this.revisionOf.set(null);
    this.actionError.set('');
    this.form.controls.reason.clearValidators();
    this.form.controls.reason.updateValueAndValidity();
  }

  submit(event: Event) {
    event.preventDefault();
    if (this.preview || this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.formValidationFailed('Revise los campos marcados antes de guardar.');
      return;
    }
    const value = this.form.getRawValue();
    if (value.scope === 'ZONE' && !value.zoneId) {
      this.form.controls.zoneId.setErrors({ required: true });
      this.form.controls.zoneId.markAsTouched();
      this.formValidationFailed('Seleccione una zona para esta excepción tarifaria.');
      return;
    }
    const revision = this.revisionOf();
    const immediateRevision = !!revision && this.revisionTiming() === 'now';
    const start = immediateRevision ? null : localDateTimeToIso(value.validFrom);
    const end = value.validTo ? localDateTimeToIso(value.validTo) : null;
    if (value.validTo && !end) {
      this.form.controls.validTo.setErrors({ ...this.form.controls.validTo.errors, invalidDateTime: true });
      this.form.controls.validTo.markAsTouched();
      this.formValidationFailed('La fecha final no es válida. Use una fecha y hora de Ecuador.');
      return;
    }
    if (!immediateRevision && !start) {
      this.form.controls.validFrom.setErrors({ ...this.form.controls.validFrom.errors, invalidDateTime: true });
      this.form.controls.validFrom.markAsTouched();
      this.actionError.set('Seleccione fechas y horas válidas.');
      this.toast.error(this.actionError());
      return;
    }
    const scheduled = this.editingScheduled();
    const body = {
      code: value.code.trim(), name: value.name.trim(), zoneId: revision ? revision.zoneId : value.scope === 'ZONE' ? value.zoneId : null,
      currency: value.currency, amount: value.amount.trim().replace(',', '.'),
      durationMinutes: Number(value.durationMinutes), minMinutes: Number(value.minMinutes),
      maxContinuousMinutes: Number(value.maxContinuousMinutes), gracePeriodMinutes: Number(value.gracePeriodMinutes),
      roundingMode: value.roundingMode, validFrom: start ?? '', validTo: end, normativeReference: value.normativeReference.trim(),
      ...(scheduled || revision ? { reason: value.reason.trim(), expectedVersion: (scheduled ?? revision)!.rowVersion } : {}),
    };
    this.saving.set(true);
    this.actionError.set('');
    this.actionMessage.set('');
    this.savedVersionId.set('');
    if (!immediateRevision && Date.parse(start!) <= Date.now()) {
      this.saving.set(false);
      this.form.controls.validFrom.setErrors({ ...this.form.controls.validFrom.errors, dateMustBeFuture: true });
      this.form.controls.validFrom.markAsTouched();
      this.formValidationFailed('La fecha y hora de inicio debe ser futura en la hora de Ecuador (America/Guayaquil).');
      return;
    }
    const effectiveStart = immediateRevision ? Date.now() : Date.parse(start!);
    if (end && Date.parse(end) <= effectiveStart) {
      this.saving.set(false);
      this.form.controls.validTo.setErrors({ ...this.form.controls.validTo.errors, endNotAfterStart: true });
      this.form.controls.validTo.markAsTouched();
      this.formValidationFailed('La fecha y hora final debe ser posterior al inicio de la nueva versión.');
      return;
    }
    const save = scheduled
      ? this.api.updateScheduledTariff(scheduled.id, body)
      : revision
      ? immediateRevision
        ? this.api.reviseTariffNow(revision.id, {
          code: body.code, name: body.name, currency: body.currency, amount: body.amount,
          durationMinutes: body.durationMinutes, minMinutes: body.minMinutes,
          maxContinuousMinutes: body.maxContinuousMinutes, gracePeriodMinutes: body.gracePeriodMinutes,
          roundingMode: body.roundingMode, normativeReference: body.normativeReference,
          reason: value.reason.trim(), expectedVersion: revision.rowVersion, validTo: end,
        })
        : this.api.reviseTariff(revision.id, body)
      : this.api.createTariff(body);
    save.pipe(
      switchMap((saved) => this.refreshAfterWrite(saved, revision
        ? 'Tarifa actualizada correctamente. Se creó una nueva versión y se conservaron los datos históricos.'
        : scheduled ? 'La tarifa programada se actualizó correctamente.' : 'Los cambios se guardaron correctamente.')),
      finalize(() => this.saving.set(false)),
    ).subscribe({
        next: ({ saved, refreshed, successMessage }) => {
        this.showForm.set(false);
        this.revisionOf.set(null);
        this.editingScheduled.set(null);
        this.savedVersionId.set(saved.id);
        this.actionMessage.set(refreshed ? successMessage : `${successMessage} El servidor confirmó el guardado, pero el listado no pudo actualizarse. Use “Actualizar” para consultar la versión persistida.`);
        this.toast.success(this.savedVersionId() ? `${this.actionMessage()} Identificador: ${this.savedVersionId()}` : this.actionMessage());
      },
      error: (error: unknown) => {
        this.actionError.set(normalizeTariffWriteError(error).message);
        this.toast.error(this.actionError());
      },
    });
  }

  controlError(control: AbstractControl, label: string): string {
    if (!control.touched || !control.invalid) return '';
    if (control.hasError('required')) return `Ingrese ${label}.`;
    if (control.hasError('invalidDateTime')) return `Ingrese una fecha y hora válida para ${label}.`;
    if (control.hasError('dateMustBeFuture')) return 'La fecha y hora debe ser futura en Ecuador.';
    if (control.hasError('endNotAfterStart')) return 'Debe ser posterior al inicio de la nueva versión.';
    if (control.hasError('pattern')) return `Revise el formato de ${label}.`;
    if (control.hasError('min')) return `El valor de ${label} debe ser mayor o igual al mínimo permitido.`;
    if (control.hasError('maxlength')) return `El valor de ${label} excede la longitud permitida.`;
    return `Revise el valor de ${label}.`;
  }

  private formValidationFailed(message: string) {
    this.actionError.set(message);
    this.toast.error(message);
  }

  openEnd(row: ConfiguredTariff) {
    if (!this.canModify(row)) return;
    this.showForm.set(false);
    this.actionError.set('');
    this.endingTariff.set(row);
    this.setEndTiming('scheduled');
    this.endForm.reset({ validTo: '', reason: '' });
  }

  setEndTiming(timing: 'now' | 'scheduled') {
    this.endTiming.set(timing);
    const control = this.endForm.controls.validTo;
    control.setValidators(timing === 'scheduled' ? [Validators.required] : []);
    if (timing === 'now') control.setValue('');
    control.updateValueAndValidity();
  }

  zonesWithoutTariffAfterEnd(row: ConfiguredTariff): string[] {
    const at = this.endTiming() === 'now' ? Date.now() : Date.parse(localDateTimeToIso(this.endForm.controls.validTo.value) ?? '');
    if (!Number.isFinite(at)) return [];
    return (this.effective()?.zones ?? []).filter((zone) => {
      const candidates = this.tariffs().filter((candidate) => candidate.id !== row.id && candidate.active
        && !candidate.cancelledAt && Date.parse(candidate.validFrom) <= at
        && (!candidate.validTo || Date.parse(candidate.validTo) >= at)
        && (candidate.zoneId === zone.zoneId || candidate.zoneId === null));
      candidates.sort((left, right) => Number(right.zoneId === zone.zoneId) - Number(left.zoneId === zone.zoneId)
        || Date.parse(right.validFrom) - Date.parse(left.validFrom));
      return candidates.length === 0;
    }).map((zone) => `${zone.zoneCode} · ${zone.zoneName}`);
  }

  closeEnd(event?: Event) {
    event?.preventDefault();
    this.endingTariff.set(null);
    this.actionError.set('');
  }

  cancelScheduledDialog(event?: Event) {
    event?.preventDefault();
    this.cancelDialog()?.nativeElement.close();
    this.cancellingTariff.set(null);
    this.actionError.set('');
  }

  openCancel(row: ConfiguredTariff) {
    if (!this.isScheduled(row)) return;
    this.showForm.set(false); this.endingTariff.set(null); this.revisionOf.set(null); this.editingScheduled.set(null);
    this.actionError.set(''); this.cancellingTariff.set(row); this.cancellationForm.reset({ reason: '' });
  }

  submitCancel(event: Event) {
    event.preventDefault();
    const row = this.cancellingTariff();
    if (!row || this.saving()) return;
    this.cancellationForm.markAllAsTouched();
    if (this.cancellationForm.invalid) { this.formValidationFailed('Escriba el motivo obligatorio para cancelar la programación.'); return; }
    this.saving.set(true);
    this.api.cancelScheduledTariff(row.id, this.cancellationForm.controls.reason.value.trim())
      .pipe(switchMap((saved) => this.refreshAfterWrite(saved, 'La programación fue cancelada. Esta tarifa no entrará en vigencia.')),
        finalize(() => this.saving.set(false))).subscribe({
        next: ({ refreshed, successMessage }) => { this.cancelDialog()?.nativeElement.close(); this.cancellingTariff.set(null); this.actionMessage.set(refreshed ? successMessage : `${successMessage} El servidor confirmó la cancelación, pero el listado no pudo actualizarse. Use “Actualizar” para consultar el estado persistido.`); this.toast.success(this.actionMessage()); },
        error: (error: unknown) => { this.actionError.set(normalizeTariffWriteError(error).message); this.toast.error(this.actionError()); },
      });
  }

  submitEnd(event: Event) {
    event.preventDefault();
    const row = this.endingTariff();
    if (!row || this.saving()) return;
    this.endForm.markAllAsTouched();
    if (this.endForm.invalid) { this.formValidationFailed('Seleccione una fecha válida y escriba el motivo obligatorio para finalizar.'); return; }
    const immediate = this.endTiming() === 'now';
    const value = immediate ? null : localDateTimeToIso(this.endForm.getRawValue().validTo);
    if (!immediate && !value) { this.formValidationFailed('Seleccione una fecha y hora válidas.'); return; }
    if (!immediate && Date.parse(value!) <= Date.now()) { this.formValidationFailed('La fecha y hora debe ser futura en la hora de Ecuador (America/Guayaquil).'); return; }
    this.saving.set(true);
    const request = immediate
      ? this.api.endTariffNow(row.id, this.endForm.controls.reason.value.trim(), row.rowVersion)
      : this.api.endTariff(row.id, value!, this.endForm.controls.reason.value.trim(), row.rowVersion);
    request.pipe(switchMap((saved) => this.refreshAfterWrite(saved, immediate ? 'La vigencia se finalizó correctamente.' : 'La finalización se programó correctamente.')),
      finalize(() => this.saving.set(false))).subscribe({
      next: ({ refreshed, successMessage }) => { this.endDialog()?.nativeElement.close(); this.endingTariff.set(null); this.actionMessage.set(refreshed ? successMessage : `${successMessage} El servidor confirmó la operación, pero el listado no pudo actualizarse. Use “Actualizar” para consultar el estado persistido.`); this.toast.success(this.actionMessage()); },
      error: (error: unknown) => { this.actionError.set(normalizeTariffWriteError(error).message); this.toast.error(this.actionError()); },
    });
  }

  cancelForms() { this.showForm.set(false); this.revisionOf.set(null); this.closeScheduledEditor(); this.endingTariff.set(null); this.cancellingTariff.set(null); this.actionError.set(''); }
  displayZone(code: string | null, name: string | null) { return code && name ? `${code} · ${name}` : code ?? name ?? 'Todas las zonas'; }
  tariffSource(source: string) { return source === 'ZONE' ? 'Excepción de zona' : source === 'GENERAL' ? 'Regla general heredada' : 'Sin tarifa vigente'; }
  canModify(row: ConfiguredTariff) { return this.isCurrent(row); }
  isCurrent(row: ConfiguredTariff) { const now = Date.now(); return row.active && !row.cancelledAt && Date.parse(row.validFrom) <= now && (!row.validTo || Date.parse(row.validTo) >= now); }
  isScheduled(row: ConfiguredTariff) { return row.active && !row.cancelledAt && Date.parse(row.validFrom) > Date.now(); }
  statusLabel(row: ConfiguredTariff) { return row.cancelledAt ? 'Cancelada' : this.isScheduled(row) ? 'Programada' : row.active && (!row.validTo || Date.parse(row.validTo) >= Date.now()) ? 'Vigente' : 'Finalizada'; }
  statusClass(row: ConfiguredTariff): 'current' | 'scheduled' | 'cancelled' | 'ended' {
    if (row.cancelledAt) return 'cancelled';
    if (this.isScheduled(row)) return 'scheduled';
    return this.isCurrent(row) ? 'current' : 'ended';
  }
}
