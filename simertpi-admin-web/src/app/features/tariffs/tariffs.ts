import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { forkJoin, finalize } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
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
  readonly editDialog = viewChild<ElementRef<HTMLDialogElement>>('editDialog');
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

      const editing = this.editingScheduled();
      const editor = this.editDialog()?.nativeElement;
      const revision = this.revisionOf();
      if (editor && (editing || revision) && !editor.open) editor.showModal();
      else if (editor && !editing && !revision && editor.open) editor.close();

      const ending = this.endingTariff();
      const endDialog = this.endDialog()?.nativeElement;
      if (endDialog && ending && !endDialog.open) endDialog.showModal();
      else if (endDialog && !ending && endDialog.open) endDialog.close();
    });
    if (!this.preview) this.load();
  }

  load() {
    if (this.preview) return;
    this.state.set('loading');
    this.errorMessage.set('');
    forkJoin({ tariffs: this.api.listTariffs(), zones: this.zonesApi.listZones(), effective: this.api.effective(new Date().toISOString()) })
      .subscribe({
        next: (result) => {
          this.tariffs.set(result.tariffs);
          this.zones.set(result.zones);
          this.effective.set(result.effective);
          this.state.set('ready');
        },
        error: (error: unknown) => {
          this.errorMessage.set(normalizeError(error).message || 'No se pudo consultar la configuración de tarifas.');
          this.state.set('error');
        },
      });
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
    this.form.reset({ code: row.code, name: row.name, scope: row.zoneId ? 'ZONE' : 'GENERAL', zoneId: row.zoneId ?? '', currency: row.currency ?? '', amount: String(row.amount).replace('.', ','), durationMinutes: row.durationMinutes, minMinutes: row.minMinutes, maxContinuousMinutes: row.maxContinuousMinutes, gracePeriodMinutes: row.gracePeriodMinutes ?? 0, roundingMode: row.roundingMode ?? 'HALF_UP', validFrom: '', validTo: '', normativeReference: row.normativeReference ?? '', reason: '', expectedVersion: row.rowVersion });
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
    if (this.form.invalid) return;
    const value = this.form.getRawValue();
    if (value.scope === 'ZONE' && !value.zoneId) {
      this.form.controls.zoneId.setErrors({ required: true });
      return;
    }
    const revision = this.revisionOf();
    const immediateRevision = !!revision && this.revisionTiming() === 'now';
    const start = immediateRevision ? null : localDateTimeToIso(value.validFrom);
    const end = value.validTo ? localDateTimeToIso(value.validTo) : null;
    if (!immediateRevision && (!start || value.validTo && !end)) {
      this.actionError.set('Seleccione fechas y horas válidas.');
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
      this.actionError.set('La fecha y hora de inicio debe ser futura en la hora de Ecuador (America/Guayaquil).');
      return;
    }
    if (!immediateRevision && end && Date.parse(end) <= Date.parse(start!)) {
      this.saving.set(false);
      this.actionError.set('La fecha y hora final debe ser posterior a la fecha y hora de inicio.');
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
          reason: value.reason.trim(), expectedVersion: revision.rowVersion,
        })
        : this.api.reviseTariff(revision.id, body)
      : this.api.createTariff(body);
    save.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.showForm.set(false);
        this.revisionOf.set(null);
        this.editingScheduled.set(null);
        this.savedVersionId.set(saved.id);
        this.actionMessage.set(revision
          ? 'Tarifa actualizada correctamente. Se creó una nueva versión y se conservó el historial.'
          : 'La nueva versión tarifaria quedó registrada con su vigencia y referencia normativa.');
        if (scheduled) this.actionMessage.set('La tarifa programada se actualizó correctamente.');
        if (revision && immediateRevision) this.actionMessage.set(`Tarifa actualizada correctamente. Nueva versión ${saved.id}; las sesiones ya iniciadas conservan su tarifa anterior.`);
        this.load();
      },
      error: (error: unknown) => {
        this.actionError.set(normalizeTariffWriteError(error).message);
      },
    });
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

  openCancel(row: ConfiguredTariff) {
    if (!this.isScheduled(row)) return;
    this.showForm.set(false); this.endingTariff.set(null); this.revisionOf.set(null); this.editingScheduled.set(null);
    this.actionError.set(''); this.cancellingTariff.set(row); this.cancellationForm.reset({ reason: '' });
  }

  submitCancel(event: Event) {
    event.preventDefault();
    const row = this.cancellingTariff();
    if (!row || this.saving() || this.cancellationForm.invalid) { this.cancellationForm.markAllAsTouched(); return; }
    this.saving.set(true);
    this.api.cancelScheduledTariff(row.id, this.cancellationForm.controls.reason.value.trim())
      .pipe(finalize(() => this.saving.set(false))).subscribe({
        next: () => { this.cancellingTariff.set(null); this.actionMessage.set('La programación fue cancelada. Esta tarifa no entrará en vigencia.'); this.load(); },
        error: (error: unknown) => this.actionError.set(normalizeTariffWriteError(error).message),
      });
  }

  submitEnd(event: Event) {
    event.preventDefault();
    const row = this.endingTariff();
    if (!row || this.endForm.invalid || this.saving()) { this.endForm.markAllAsTouched(); return; }
    const immediate = this.endTiming() === 'now';
    const value = immediate ? null : localDateTimeToIso(this.endForm.getRawValue().validTo);
    if (!immediate && !value) { this.actionError.set('Seleccione una fecha y hora válidas.'); return; }
    if (!immediate && Date.parse(value!) <= Date.now()) { this.actionError.set('La fecha y hora debe ser futura en la hora de Ecuador (America/Guayaquil).'); return; }
    this.saving.set(true);
    const request = immediate
      ? this.api.endTariffNow(row.id, this.endForm.controls.reason.value.trim(), row.rowVersion)
      : this.api.endTariff(row.id, value!, this.endForm.controls.reason.value.trim(), row.rowVersion);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => { this.endingTariff.set(null); this.actionMessage.set(immediate ? 'La tarifa dejó de aplicarse desde el momento de confirmación.' : 'La finalización futura quedó registrada.'); this.load(); },
      error: (error: unknown) => this.actionError.set(normalizeTariffWriteError(error).message),
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
