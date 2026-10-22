import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, finalize, switchMap, tap } from 'rxjs';
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
  imports: [ReactiveFormsModule, RouterLink, DataState, PageHeading],
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
  readonly saving = signal(false);
  readonly showForm = signal(false);
  readonly revisionOf = signal<ConfiguredTariff | null>(null);
  readonly endingTariff = signal<ConfiguredTariff | null>(null);
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
  });
  readonly endForm = this.builder.nonNullable.group({ validTo: ['', Validators.required] });
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

  constructor() { if (!this.preview) this.load(); }

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
    this.form.reset({ code: '', name: '', scope: 'GENERAL', zoneId: '', currency: '', amount: '', durationMinutes: null, minMinutes: null, maxContinuousMinutes: null, gracePeriodMinutes: 0, roundingMode: 'HALF_UP', validFrom: '', validTo: '', normativeReference: '' });
    this.showForm.set(true);
  }

  openRevision(row: ConfiguredTariff) {
    this.actionError.set('');
    this.actionMessage.set('');
    this.endingTariff.set(null);
    this.editingScheduled.set(null);
    this.cancellingTariff.set(null);
    this.revisionOf.set(row);
    this.form.reset({ code: '', name: row.name, scope: row.zoneId ? 'ZONE' : 'GENERAL', zoneId: row.zoneId ?? '', currency: row.currency ?? '', amount: String(row.amount).replace('.', ','), durationMinutes: row.durationMinutes, minMinutes: row.minMinutes, maxContinuousMinutes: row.maxContinuousMinutes, gracePeriodMinutes: row.gracePeriodMinutes ?? 0, roundingMode: row.roundingMode ?? 'HALF_UP', validFrom: '', validTo: '', normativeReference: row.normativeReference ?? '' });
    this.showForm.set(true);
  }

  openEditScheduled(row: ConfiguredTariff) {
    if (!this.isScheduled(row)) return;
    this.actionError.set(''); this.actionMessage.set(''); this.showForm.set(true);
    this.revisionOf.set(null); this.endingTariff.set(null); this.cancellingTariff.set(null);
    this.editingScheduled.set(row);
    this.form.reset({ code: row.code, name: row.name, scope: row.zoneId ? 'ZONE' : 'GENERAL', zoneId: row.zoneId ?? '',
      currency: row.currency ?? '', amount: String(row.amount).replace('.', ','), durationMinutes: row.durationMinutes,
      minMinutes: row.minMinutes, maxContinuousMinutes: row.maxContinuousMinutes, gracePeriodMinutes: row.gracePeriodMinutes ?? 0,
      roundingMode: row.roundingMode ?? 'HALF_UP', validFrom: isoToGuayaquilDateTimeLocal(row.validFrom),
      validTo: isoToGuayaquilDateTimeLocal(row.validTo), normativeReference: row.normativeReference ?? '' });
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
    const start = localDateTimeToIso(value.validFrom);
    const end = value.validTo ? localDateTimeToIso(value.validTo) : null;
    if (!start || value.validTo && !end) {
      this.actionError.set('Seleccione fechas y horas válidas.');
      return;
    }
    const revision = this.revisionOf();
    const scheduled = this.editingScheduled();
    const body = {
      code: value.code.trim(), name: value.name.trim(), zoneId: scheduled?.zoneId ?? revision?.zoneId ?? (value.scope === 'ZONE' ? value.zoneId : null),
      currency: value.currency, amount: value.amount.trim().replace(',', '.'),
      durationMinutes: Number(value.durationMinutes), minMinutes: Number(value.minMinutes),
      maxContinuousMinutes: Number(value.maxContinuousMinutes), gracePeriodMinutes: Number(value.gracePeriodMinutes),
      roundingMode: value.roundingMode, validFrom: start, validTo: end, normativeReference: value.normativeReference.trim(),
    };
    this.saving.set(true);
    this.actionError.set('');
    this.actionMessage.set('');
    if (Date.parse(start) <= Date.now()) {
      this.saving.set(false);
      this.actionError.set('La fecha y hora de inicio debe ser futura en la hora de Ecuador (America/Guayaquil).');
      return;
    }
    if (end && Date.parse(end) <= Date.parse(start)) {
      this.saving.set(false);
      this.actionError.set('La fecha y hora final debe ser posterior a la fecha y hora de inicio.');
      return;
    }
    let previousVersionEnded = false;
    const save = scheduled
      ? this.api.updateScheduledTariff(scheduled.id, body)
      : revision
      ? this.api.endTariff(revision.id, start).pipe(
          tap(() => previousVersionEnded = true),
          switchMap(() => this.api.createTariff(body)),
        )
      : this.api.createTariff(body);
    save.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.showForm.set(false);
        this.revisionOf.set(null);
        this.editingScheduled.set(null);
        this.actionMessage.set('La nueva versión tarifaria quedó registrada con su vigencia y referencia normativa.');
        if (scheduled) this.actionMessage.set('La tarifa programada se actualizó correctamente.');
        this.load();
      },
      error: (error: unknown) => {
        this.actionError.set(previousVersionEnded
          ? 'La versión anterior quedó programada para finalizar, pero la nueva versión no se confirmó. Revise la configuración antes de continuar.'
          : normalizeTariffWriteError(error).message);
        if (previousVersionEnded) this.load();
      },
    });
  }

  openEnd(row: ConfiguredTariff) {
    this.showForm.set(false);
    this.actionError.set('');
    this.endingTariff.set(row);
    this.endForm.reset({ validTo: '' });
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
    const value = localDateTimeToIso(this.endForm.getRawValue().validTo);
    if (!value) { this.actionError.set('Seleccione una fecha y hora válidas.'); return; }
    this.saving.set(true);
    this.api.endTariff(row.id, value).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => { this.endingTariff.set(null); this.actionMessage.set('La finalización futura quedó registrada.'); this.load(); },
      error: (error: unknown) => this.actionError.set(normalizeTariffWriteError(error).message),
    });
  }

  cancelForms() { this.showForm.set(false); this.revisionOf.set(null); this.editingScheduled.set(null); this.endingTariff.set(null); this.cancellingTariff.set(null); this.actionError.set(''); }
  displayZone(code: string | null, name: string | null) { return code && name ? `${code} · ${name}` : code ?? name ?? 'Todas las zonas'; }
  tariffSource(source: string) { return source === 'ZONE' ? 'Excepción de zona' : source === 'GENERAL' ? 'Regla general heredada' : 'Sin tarifa vigente'; }
  canModify(row: ConfiguredTariff) { return row.active && !row.validTo && Date.parse(row.validFrom) < Date.now(); }
  isScheduled(row: ConfiguredTariff) { return row.active && !row.cancelledAt && Date.parse(row.validFrom) > Date.now(); }
  statusLabel(row: ConfiguredTariff) { return row.cancelledAt ? 'Cancelada' : this.isScheduled(row) ? 'Programada' : row.active && (!row.validTo || Date.parse(row.validTo) >= Date.now()) ? 'Vigente' : 'Finalizada'; }
}
