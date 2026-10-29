import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, finalize, of } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { AdminConfigStatus } from '../../shared/ui/admin-config-status';
import { DataState } from '../../shared/ui/data-state';
import { OperationToast } from '../../shared/ui/operation-toast';
import { PageHeading } from '../../shared/ui/page-heading';
import { ParkingZone, ZonesApi } from '../zones/zones-api';
import {
  ConfiguredSchedule,
  EffectiveOperationalCalendar,
  ScheduleUpdateRequest,
  ScheduleWriteRequest,
  TariffsApi,
} from './tariffs-api';
import {
  currentConfigurationDate,
  currentConfigurationTime,
  formatConfigurationDate,
  isValidConfigurationDate,
  nextConfigurationDate,
} from './tariffs-format';

type LoadState = 'loading' | 'error' | 'empty' | 'ready';
type Status = 'active' | 'scheduled' | 'ended' | 'inactive';
type DialogMode = 'create' | 'edit' | 'revise' | 'end' | 'deactivate' | null;

const weekdays: Record<number, string> = {
  1: 'Lunes',
  2: 'Martes',
  3: 'Miércoles',
  4: 'Jueves',
  5: 'Viernes',
  6: 'Sábado',
  7: 'Domingo',
};

@Component({
  selector: 'app-operating-hours',
  imports: [PageHeading, DataState, AdminConfigStatus, ReactiveFormsModule],
  templateUrl: './operating-hours.html',
  styleUrl: './operating-hours.scss',
})
export class OperatingHours {
  private readonly api = inject(TariffsApi);
  private readonly zonesApi = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  private readonly toast = inject(OperationToast);
  private readonly router = inject(Router);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly rows = signal<ConfiguredSchedule[]>([]);
  readonly zones = signal<ParkingZone[]>([]);
  readonly state = signal<LoadState>(this.preview ? 'empty' : 'loading');
  readonly errorMessage = signal('');
  readonly zoneError = signal('');
  readonly query = signal('');
  readonly dayFilter = signal('all');
  readonly statusFilter = signal<'all' | Status>('all');
  readonly dialog = signal<DialogMode>(null);
  readonly selected = signal<ConfiguredSchedule | null>(null);
  readonly saving = signal(false);
  readonly actionError = signal('');
  readonly effectiveDate = signal(currentConfigurationDate());
  readonly effectiveTime = signal(currentConfigurationTime());
  readonly effectiveZoneId = signal('');
  readonly effectiveResult = signal<EffectiveOperationalCalendar | null>(null);
  readonly effectiveLoading = signal(false);
  readonly effectiveError = signal('');
  readonly today = currentConfigurationDate;
  readonly tomorrow = nextConfigurationDate;
  readonly formatDate = formatConfigurationDate;
  readonly weekdayName = (day: number) => weekdays[day] ?? `Día ${day}`;
  readonly form = this.builder.nonNullable.group({
    scope: ['', [Validators.required]],
    dayOfWeek: ['', [Validators.required]],
    startTime: ['', [Validators.required]],
    endTime: ['', [Validators.required]],
    validFrom: [''],
    validTo: [''],
    effectiveFrom: [''],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  readonly visibleRows = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('es-EC');
    return this.rows().filter((row) => {
      const matchesQuery =
        !query ||
        [row.zoneCode, row.zoneName, this.weekdayName(row.dayOfWeek), row.startTime, row.endTime]
          .filter(Boolean)
          .join(' ')
          .toLocaleLowerCase('es-EC')
          .includes(query);
      return (
        matchesQuery &&
        (this.dayFilter() === 'all' || String(row.dayOfWeek) === this.dayFilter()) &&
        (this.statusFilter() === 'all' || this.status(row) === this.statusFilter())
      );
    });
  });
  readonly activeCount = computed(
    () => this.rows().filter((row) => this.status(row) === 'active').length,
  );
  readonly scheduledCount = computed(
    () => this.rows().filter((row) => this.status(row) === 'scheduled').length,
  );
  readonly inactiveCount = computed(
    () =>
      this.rows().filter((row) => this.status(row) === 'inactive' || this.status(row) === 'ended')
        .length,
  );

  constructor() {
    if (!this.preview) {
      this.load();
      this.loadZones();
    }
  }

  load(): void {
    if (this.preview) return;
    this.state.set('loading');
    this.errorMessage.set('');
    this.api
      .listSchedules()
      .pipe(
        catchError((error: unknown) => {
          this.errorMessage.set(
            normalizeError(error).message || 'No se pudieron consultar los horarios de cobro.',
          );
          this.state.set('error');
          return of(null);
        }),
        finalize(() => {
          if (this.state() === 'loading') this.state.set(this.rows().length ? 'ready' : 'empty');
        }),
      )
      .subscribe((rows) => {
        if (rows === null) return;
        this.rows.set(rows);
        this.state.set(rows.length ? 'ready' : 'empty');
      });
  }

  private loadZones(): void {
    this.zonesApi.listZones().subscribe({
      next: (zones) => {
        const activeZones = zones.filter((zone) => zone.active);
        this.zones.set(activeZones);
        if (!this.effectiveZoneId()) this.effectiveZoneId.set(activeZones[0]?.id ?? '');
      },
      error: () =>
        this.zoneError.set(
          'No se pudieron cargar las zonas. Reintente la consulta antes de guardar.',
        ),
    });
  }

  consultEffective(): void {
    const date = this.effectiveDate();
    const time = this.effectiveTime();
    const zoneId = this.effectiveZoneId();
    this.effectiveError.set('');
    this.effectiveResult.set(null);
    if (!isValidConfigurationDate(date) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(time) || !zoneId) {
      this.effectiveError.set('Selecciona una zona, fecha y hora válidas del calendario de Ecuador.');
      return;
    }
    this.effectiveLoading.set(true);
    this.api.effectiveOperationalCalendar(zoneId, date, time).pipe(finalize(() => this.effectiveLoading.set(false))).subscribe({
      next: (result) => this.effectiveResult.set(result),
      error: (error: unknown) => this.effectiveError.set(normalizeError(error).message || 'No se pudo consultar la regla efectiva.'),
    });
  }

  effectiveExplanation(result: EffectiveOperationalCalendar): string {
    if (result.reasonCode === 'NO_ACTIVE_SCHEDULE') return 'No existe un horario semanal efectivo para esta zona y fecha.';
    if (result.reasonCode === 'OUTSIDE_OPERATION_HOURS') return 'La hora consultada está fuera del intervalo operativo aplicable.';
    if (result.reasonCode === 'FREE_PARKING') return 'La excepción aplicable indica que el período no es cobrable.';
    return result.operational ? 'El intervalo semanal efectivo está activo para esta zona y hora.' : 'La regla consultada no habilita operación en este momento.';
  }

  status(row: ConfiguredSchedule): Status {
    if (!row.active) return 'inactive';
    const today = this.today();
    if (row.validFrom && row.validFrom > today) return 'scheduled';
    if (row.validTo && row.validTo < today) return 'ended';
    return 'active';
  }

  statusLabel(row: ConfiguredSchedule): string {
    return (
      {
        active: 'Vigente',
        scheduled: 'Programado',
        ended: 'Finalizado',
        inactive: 'Inactivo',
      } as const
    )[this.status(row)];
  }
  statusTone(row: ConfiguredSchedule): 'active' | 'scheduled' | 'ended' | 'inactive' {
    return this.status(row);
  }
  scope(row: ConfiguredSchedule): string {
    return row.zoneName ? `${row.zoneCode ? `${row.zoneCode} · ` : ''}${row.zoneName}` : 'General';
  }
  scopeChangeWarning(): string {
    const row = this.selected();
    if (this.dialog() !== 'edit' || !row) return '';
    const nextScope = this.form.controls.scope.value;
    if (!nextScope) return '';
    const nextZoneId = nextScope === '__general__' ? null : nextScope;
    if (nextZoneId === row.zoneId) return '';
    if (row.zoneId === null && nextZoneId !== null) {
      return 'Al cambiar de general a zonal, este horario dejará de aplicar a las demás zonas desde la fecha de vigencia. Verifica que conserven otro horario.';
    }
    if (row.zoneId !== null && nextZoneId === null) {
      return 'Al cambiar de zonal a general, este horario podrá aplicar a todas las zonas desde la fecha de vigencia. Revisa posibles conflictos de alcance.';
    }
    return 'El cambio trasladará este horario a otra zona desde la fecha de vigencia. Verifica la cobertura y los conflictos del alcance.';
  }
  validity(row: ConfiguredSchedule): string {
    return `${row.validFrom ? this.formatDate(row.validFrom) : 'Sin fecha inicial'} – ${row.validTo ? this.formatDate(row.validTo) : 'Sin fecha final'}`;
  }
  canManage(row: ConfiguredSchedule): boolean {
    return !this.preview && row.active && this.status(row) !== 'ended';
  }
  canEnd(row: ConfiguredSchedule): boolean {
    return this.canManage(row) && this.status(row) === 'active';
  }
  trackZone(_index: number, zone: ParkingZone): string {
    return zone.id;
  }

  openCreate(): void {
    if (this.preview) return;
    this.selected.set(null);
    this.actionError.set('');
    this.form.reset({
      scope: '',
      dayOfWeek: '',
      startTime: '',
      endTime: '',
      validFrom: this.tomorrow(),
      validTo: '',
      effectiveFrom: '',
      reason: '',
    });
    this.dialog.set('create');
  }

  validFromRequired(): boolean {
    const mode = this.dialog();
    return mode === 'create' || mode === 'revise'
      || (mode === 'edit' && this.selected() !== null && this.status(this.selected()!) === 'scheduled');
  }

  openEdit(row: ConfiguredSchedule): void {
    if (!this.canManage(row)) return;
    const mode: DialogMode = this.status(row) === 'active' ? 'revise' : 'edit';
    this.selected.set(row);
    this.actionError.set('');
    this.form.reset({
      scope: row.zoneId ?? '__general__',
      dayOfWeek: String(row.dayOfWeek),
      startTime: row.startTime.slice(0, 5),
      endTime: row.endTime.slice(0, 5),
      validFrom: mode === 'revise' ? this.tomorrow() : (row.validFrom ?? ''),
      validTo: row.validTo ?? '',
      effectiveFrom: '',
      reason: '',
    });
    this.dialog.set(mode);
  }

  openEnd(row: ConfiguredSchedule): void {
    if (!this.canEnd(row)) return;
    this.selected.set(row);
    this.actionError.set('');
    this.form.reset({
      scope: '',
      dayOfWeek: '',
      startTime: '',
      endTime: '',
      validFrom: '',
      validTo: '',
      effectiveFrom: this.tomorrow(),
      reason: '',
    });
    this.dialog.set('end');
  }

  openDeactivate(row: ConfiguredSchedule): void {
    if (!this.canManage(row) || this.status(row) !== 'scheduled') return;
    this.selected.set(row);
    this.actionError.set('');
    this.form.controls.reason.reset('');
    this.dialog.set('deactivate');
  }

  closeDialog(): void {
    if (this.saving()) return;
    this.dialog.set(null);
    this.selected.set(null);
    this.actionError.set('');
  }

  submit(): void {
    const mode = this.dialog();
    if (this.preview || this.saving() || mode === 'deactivate' || mode === 'end' || mode === null)
      return;
    this.actionError.set('');
    const value = this.form.getRawValue();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.actionError.set('Complete los campos obligatorios antes de guardar.');
      return;
    }
    if (!this.validTimeRange(value.startTime, value.endTime)) {
      this.actionError.set(
        'La hora final debe ser posterior a la hora inicial. Los rangos que cruzan medianoche no están admitidos.',
      );
      return;
    }
    if (
      (value.validFrom && !isValidConfigurationDate(value.validFrom)) ||
      (value.validTo && !isValidConfigurationDate(value.validTo))
    ) {
      this.actionError.set(
        'Revise las fechas de vigencia; deben ser fechas calendario válidas de Ecuador.',
      );
      return;
    }
    if (this.validFromRequired() && (!isValidConfigurationDate(value.validFrom) || value.validFrom <= this.today())) {
      this.form.controls.validFrom.setErrors({ required: !value.validFrom, future: Boolean(value.validFrom) });
      this.form.controls.validFrom.markAsTouched();
      this.actionError.set('La programación debe tener una fecha de inicio desde mañana o posterior, según Ecuador.');
      return;
    }
    if (value.validFrom && value.validTo && value.validTo < value.validFrom) {
      this.form.controls.validTo.setErrors({ range: true });
      this.actionError.set('La fecha final no puede ser anterior a la fecha inicial.');
      return;
    }
    if (
      mode === 'revise' &&
      (!isValidConfigurationDate(value.validFrom) || value.validFrom <= this.today())
    ) {
      this.form.controls.validFrom.setErrors({ future: true });
      this.actionError.set('La nueva versión debe comenzar desde mañana o en una fecha futura.');
      return;
    }
    const reason = value.reason.trim();
    if (!reason) {
      this.form.controls.reason.setErrors({ required: true });
      this.actionError.set('El motivo es obligatorio.');
      return;
    }
    const body: ScheduleWriteRequest = {
      zoneId: value.scope === '__general__' ? null : value.scope,
      dayOfWeek: Number(value.dayOfWeek),
      startTime: `${value.startTime}:00`,
      endTime: `${value.endTime}:00`,
      validFrom: value.validFrom || null,
      validTo: value.validTo || null,
      reason,
    };
    const row = this.selected();
    const operation =
      mode === 'create'
        ? this.api.createSchedule(body)
        : mode === 'revise' && row
          ? this.api.reviseSchedule(row.id, {
              ...body,
              expectedUpdatedAt: row.updatedAt,
            } satisfies ScheduleUpdateRequest)
          : row
            ? this.api.updateSchedule(row.id, {
                ...body,
                expectedUpdatedAt: row.updatedAt,
              } satisfies ScheduleUpdateRequest)
            : null;
    if (!operation) return;
    this.saving.set(true);
    operation.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        const message =
          mode === 'create'
            ? 'El horario se creó correctamente.'
            : mode === 'revise'
              ? 'El cambio se programó correctamente y conservará la versión anterior.'
              : 'El horario programado se actualizó correctamente.';
        this.closeAfterSuccess(message);
        this.load();
      },
      error: (error: unknown) => {
        this.actionError.set(this.errorMessageFor(error));
        this.toast.error(this.actionError());
      },
    });
  }

  end(): void {
    const row = this.selected();
    if (!row || this.preview || this.saving()) return;
    const value = this.form.getRawValue();
    if (!isValidConfigurationDate(value.effectiveFrom) || value.effectiveFrom <= this.today()) {
      this.form.controls.effectiveFrom.setErrors({ future: true });
      this.form.controls.effectiveFrom.markAsTouched();
      this.actionError.set(
        'La finalización debe programarse desde mañana o para una fecha posterior.',
      );
      return;
    }
    const reason = value.reason.trim();
    if (!reason) {
      this.form.controls.reason.setErrors({ required: true });
      this.form.controls.reason.markAsTouched();
      this.actionError.set('El motivo es obligatorio para programar la finalización.');
      return;
    }
    this.actionError.set('');
    this.saving.set(true);
    this.api
      .endSchedule(row.id, value.effectiveFrom, row.updatedAt, reason)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.closeAfterSuccess('La finalización del horario se programó correctamente.');
          this.load();
        },
        error: (error: unknown) => {
          this.actionError.set(this.errorMessageFor(error));
          this.toast.error(this.actionError());
        },
      });
  }

  deactivate(): void {
    const row = this.selected();
    if (!row || this.preview || this.saving() || this.status(row) !== 'scheduled') return;
    const reason = this.form.controls.reason.value.trim();
    if (!reason) {
      this.form.controls.reason.setErrors({ required: true });
      this.form.controls.reason.markAsTouched();
      this.actionError.set('El motivo es obligatorio para cancelar la programación.');
      return;
    }
    this.saving.set(true);
    this.actionError.set('');
    this.api
      .deactivateSchedule(row.id, { reason, expectedUpdatedAt: row.updatedAt })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.closeAfterSuccess('La programación del horario se canceló correctamente.');
          this.load();
        },
        error: (error: unknown) => {
          this.actionError.set(this.errorMessageFor(error));
          this.toast.error(this.actionError());
        },
      });
  }

  openHistory(row: ConfiguredSchedule): void {
    void this.router.navigate(['/admin/audit'], {
      queryParams: { resourceType: 'PARKING_SCHEDULE', resourceId: row.id },
    });
  }

  private validTimeRange(start: string, end: string): boolean {
    return /^\d{2}:\d{2}$/.test(start) && /^\d{2}:\d{2}$/.test(end) && start < end;
  }
  private closeAfterSuccess(message: string): void {
    this.dialog.set(null);
    this.selected.set(null);
    this.actionError.set('');
    this.toast.success(message);
  }
  private errorMessageFor(error: unknown): string {
    const normalized = normalizeError(error);
    return normalized.kind === 'conflict'
      ? 'Otro administrador modificó este horario o existe un intervalo incompatible. Actualice los datos y revise los rangos.'
      : normalized.message;
  }
}
