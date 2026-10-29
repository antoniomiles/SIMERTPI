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
  CalendarException,
  CalendarExceptionUpdateRequest,
  CalendarExceptionWriteRequest,
  EffectiveOperationalCalendar,
  TariffsApi,
} from './tariffs-api';
import {
  currentConfigurationDate,
  currentConfigurationTime,
  formatConfigurationDate,
  isValidConfigurationDate,
} from './tariffs-format';

type LoadState = 'loading' | 'error' | 'empty' | 'ready';
type Status = 'active' | 'scheduled' | 'ended' | 'inactive';
type DialogMode = 'create' | 'edit' | 'deactivate' | null;
type HolidayType = CalendarExceptionWriteRequest['holidayType'];

const holidayTypes: { value: HolidayType; label: string }[] = [
  { value: 'NATIONAL_HOLIDAY', label: 'Feriado nacional' },
  { value: 'NON_TARIFFED', label: 'Día sin cobro' },
  { value: 'SPECIAL_SCHEDULE', label: 'Horario especial' },
  { value: 'MUNICIPAL_EXCEPTION', label: 'Excepción municipal' },
];

@Component({
  selector: 'app-calendar-exceptions',
  imports: [PageHeading, DataState, AdminConfigStatus, ReactiveFormsModule],
  templateUrl: './calendar-exceptions.html',
  styleUrl: './calendar-exceptions.scss',
})
export class CalendarExceptions {
  private readonly api = inject(TariffsApi);
  private readonly zonesApi = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  private readonly toast = inject(OperationToast);
  private readonly router = inject(Router);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly rows = signal<CalendarException[]>([]);
  readonly zones = signal<ParkingZone[]>([]);
  readonly state = signal<LoadState>(this.preview ? 'empty' : 'loading');
  readonly errorMessage = signal('');
  readonly zoneError = signal('');
  readonly query = signal('');
  readonly statusFilter = signal<'all' | Status>('all');
  readonly dialog = signal<DialogMode>(null);
  readonly selected = signal<CalendarException | null>(null);
  readonly saving = signal(false);
  readonly actionError = signal('');
  readonly effectiveDate = signal(currentConfigurationDate());
  readonly effectiveTime = signal(currentConfigurationTime());
  readonly effectiveZoneId = signal('');
  readonly effectiveResult = signal<EffectiveOperationalCalendar | null>(null);
  readonly effectiveLoading = signal(false);
  readonly effectiveError = signal('');
  readonly today = currentConfigurationDate;
  readonly formatDate = formatConfigurationDate;
  readonly holidayTypes = holidayTypes;
  readonly form = this.builder.nonNullable.group({
    holidayDate: ['', [Validators.required]],
    name: ['', [Validators.required, Validators.maxLength(150)]],
    scope: ['', [Validators.required]],
    holidayType: ['', [Validators.required]],
    tariffed: ['', [Validators.required]],
    startTime: [''],
    endTime: [''],
    normativeReference: ['', [Validators.maxLength(255)]],
    validFrom: ['', [Validators.required]],
    validTo: [''],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  readonly visibleRows = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('es-EC');
    return this.rows().filter((row) => {
      const matchesQuery =
        !query ||
        [row.name, row.zoneCode, row.zoneName, row.holidayType, row.normativeReference]
          .filter(Boolean)
          .join(' ')
          .toLocaleLowerCase('es-EC')
          .includes(query);
      return (
        matchesQuery && (this.statusFilter() === 'all' || this.status(row) === this.statusFilter())
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
      .listCalendarExceptions()
      .pipe(
        catchError((error: unknown) => {
          this.errorMessage.set(
            normalizeError(error).message || 'No se pudieron consultar los días especiales.',
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

  status(row: CalendarException): Status {
    if (!row.active) return 'inactive';
    const today = this.today();
    if (row.validFrom > today) return 'scheduled';
    if (row.validTo && row.validTo < today) return 'ended';
    return 'active';
  }

  statusLabel(row: CalendarException): string {
    return (
      {
        active: 'Vigente',
        scheduled: 'Programado',
        ended: 'Finalizado',
        inactive: 'Inactivo',
      } as const
    )[this.status(row)];
  }

  statusTone(row: CalendarException): 'active' | 'scheduled' | 'ended' | 'inactive' {
    return this.status(row);
  }
  scope(row: CalendarException): string {
    return row.zoneName ? `${row.zoneCode ? `${row.zoneCode} · ` : ''}${row.zoneName}` : 'General';
  }
  canManage(row: CalendarException): boolean {
    return !this.preview && row.active && row.holidayDate > this.today();
  }
  trackZone(_index: number, zone: ParkingZone): string {
    return zone.id;
  }
  typeLabel(type: string): string {
    return holidayTypes.find((item) => item.value === type)?.label ?? type;
  }

  openCreate(): void {
    if (this.preview) return;
    this.selected.set(null);
    this.actionError.set('');
    this.form.reset({
      holidayDate: '',
      name: '',
      scope: '',
      holidayType: '',
      tariffed: '',
      startTime: '',
      endTime: '',
      normativeReference: '',
      validFrom: '',
      validTo: '',
      reason: '',
    });
    this.dialog.set('create');
  }

  openEdit(row: CalendarException): void {
    if (!this.canManage(row)) return;
    this.selected.set(row);
    this.actionError.set('');
    this.form.reset({
      holidayDate: row.holidayDate,
      name: row.name,
      scope: row.zoneId ?? '__general__',
      holidayType: row.holidayType,
      tariffed: String(row.tariffed),
      startTime: row.startTime ?? '',
      endTime: row.endTime ?? '',
      normativeReference: row.normativeReference ?? '',
      validFrom: row.validFrom,
      validTo: row.validTo ?? '',
      reason: '',
    });
    this.dialog.set('edit');
  }

  openDeactivate(row: CalendarException): void {
    if (!this.canManage(row)) return;
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
    if (this.preview || this.saving() || this.dialog() === 'deactivate') return;
    this.actionError.set('');
    const value = this.form.getRawValue();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.actionError.set('Complete los campos obligatorios antes de guardar.');
      return;
    }
    if (
      !isValidConfigurationDate(value.holidayDate) ||
      !isValidConfigurationDate(value.validFrom) ||
      (value.validTo !== '' && !isValidConfigurationDate(value.validTo))
    ) {
      this.actionError.set('Revise las fechas; utilice fechas calendario válidas de Ecuador.');
      return;
    }
    if (value.validTo && value.validTo < value.validFrom) {
      this.form.controls.validTo.setErrors({ range: true });
      this.actionError.set('La fecha final no puede ser anterior a la fecha inicial.');
      return;
    }
    if (
      (value.startTime === '') !== (value.endTime === '') ||
      (value.startTime && value.endTime && value.startTime >= value.endTime)
    ) {
      this.form.controls.startTime.setErrors({ range: true });
      this.form.controls.endTime.setErrors({ range: true });
      this.actionError.set(
        'Ingrese ambas horas y asegúrese de que la hora final sea posterior a la inicial.',
      );
      return;
    }
    const body: CalendarExceptionWriteRequest = {
      holidayDate: value.holidayDate,
      name: value.name.trim(),
      zoneId: value.scope === '__general__' ? null : value.scope,
      holidayType: value.holidayType as HolidayType,
      tariffed: value.tariffed === 'true',
      startTime: value.startTime || null,
      endTime: value.endTime || null,
      normativeReference: value.normativeReference.trim() || null,
      validFrom: value.validFrom,
      validTo: value.validTo || null,
      reason: value.reason.trim(),
    };
    if (!body.reason) {
      this.form.controls.reason.setErrors({ required: true });
      this.actionError.set('Indique el motivo de la configuración.');
      return;
    }
    const current = this.selected();
    const operation =
      this.dialog() === 'edit' && current
        ? this.api.updateCalendarException(current.id, {
            ...body,
            expectedUpdatedAt: current.updatedAt,
          } satisfies CalendarExceptionUpdateRequest)
        : this.api.createCalendarException(body);
    this.saving.set(true);
    operation.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.closeAfterSuccess(
          this.dialog() === 'edit'
            ? 'La excepción se actualizó correctamente.'
            : 'La excepción se creó correctamente.',
        );
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
    if (!row || this.preview || this.saving()) return;
    const control = this.form.controls.reason;
    const reason = control.value.trim();
    if (!reason) {
      control.setErrors({ required: true });
      control.markAsTouched();
      this.actionError.set('El motivo es obligatorio para desactivar la excepción.');
      return;
    }
    this.saving.set(true);
    this.actionError.set('');
    this.api
      .deactivateCalendarException(row.id, { reason, expectedUpdatedAt: row.updatedAt })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.closeAfterSuccess('La excepción se desactivó correctamente.');
          this.load();
        },
        error: (error: unknown) => {
          this.actionError.set(this.errorMessageFor(error));
          this.toast.error(this.actionError());
        },
      });
  }

  openHistory(row: CalendarException): void {
    void this.router.navigate(['/admin/audit'], {
      queryParams: { resourceType: 'PARKING_CALENDAR_EXCEPTION', resourceId: row.id },
    });
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
      ? 'Otro administrador modificó esta excepción o existe una configuración incompatible. Actualice los datos y revise los períodos.'
      : normalized.message;
  }
}
