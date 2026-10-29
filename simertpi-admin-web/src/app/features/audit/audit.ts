import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { AuditApi, AuditRecord, ConfigurationChangeRecord } from './audit-api';
import { PortalError, normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';

const PAGE_SIZE = 50;

@Component({
  selector: 'app-audit',
  imports: [ReactiveFormsModule, DataState, PageHeading],
  templateUrl: './audit.html',
  styleUrl: './audit.scss',
})
export class Audit {
  private readonly api = inject(AuditApi);
  private readonly builder = inject(FormBuilder);
  readonly state = signal<'loading' | 'error' | 'empty' | 'ready'>('loading');
  readonly rows = signal<AuditRecord[]>([]);
  readonly errorMessage = signal('');
  readonly offset = signal(0);
  readonly hasNext = signal(false);
  readonly changes = signal<ConfigurationChangeRecord[]>([]);
  readonly changesState = signal<'loading' | 'error' | 'empty' | 'ready'>('loading');
  readonly changesError = signal('');
  readonly changesOffset = signal(0);
  readonly changesHasNext = signal(false);
  readonly selectedChange = signal<ConfigurationChangeRecord | null>(null);
  readonly filters = this.builder.nonNullable.group({
    action: ['', [Validators.maxLength(120)]],
    resourceType: ['', [Validators.maxLength(80)]],
    resourceId: ['', [Validators.maxLength(36)]],
    result: [''],
    correlationId: ['', [Validators.maxLength(128)]],
    from: [''],
    to: [''],
  });

  constructor() {
    const query = inject(ActivatedRoute).snapshot.queryParamMap;
    this.filters.patchValue({
      resourceType: query.get('resourceType') ?? '',
      resourceId: query.get('resourceId') ?? '',
    });
    this.load();
    this.loadConfigurationHistory();
  }

  load(offset = 0) {
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const values = this.filters.getRawValue();
    if (values.from && values.to && new Date(values.from) > new Date(values.to)) {
      this.errorMessage.set('El inicio del período debe ser anterior al final.');
      this.state.set('error');
      return;
    }
    this.offset.set(Math.max(0, offset));
    this.errorMessage.set('');
    this.state.set('loading');
    this.api
      .search({
        action: values.action,
        resourceType: values.resourceType,
        resourceId: values.resourceId || undefined,
        result: values.result || undefined,
        correlationId: values.correlationId,
        from: this.toIso(values.from),
        to: this.toIso(values.to),
        offset: this.offset(),
        limit: PAGE_SIZE + 1,
      })
      .subscribe({
        next: (rows) => {
          this.rows.set(rows.slice(0, PAGE_SIZE));
          this.hasNext.set(rows.length > PAGE_SIZE);
          this.state.set(rows.length ? 'ready' : 'empty');
        },
        error: (error: unknown) => {
          const normalized = normalizeError(error);
          this.errorMessage.set(
            normalized instanceof PortalError
              ? normalized.message
              : 'No se pudo consultar la auditoría.',
          );
          this.state.set('error');
        },
      });
  }

  applyFilters() {
    this.load(0);
  }

  clearFilters() {
    this.filters.reset({
      action: '',
      resourceType: '',
      resourceId: '',
      result: '',
      correlationId: '',
      from: '',
      to: '',
    });
    this.load(0);
  }

  previous() {
    if (this.offset() > 0) this.load(this.offset() - PAGE_SIZE);
  }

  next() {
    if (this.hasNext()) this.load(this.offset() + PAGE_SIZE);
  }

  loadConfigurationHistory(offset = 0) {
    this.changesOffset.set(Math.max(0, offset));
    this.changesError.set('');
    this.changesState.set('loading');
    this.api.configurationHistory(PAGE_SIZE + 1, this.changesOffset()).subscribe({
      next: (rows) => {
        this.changes.set(rows.slice(0, PAGE_SIZE));
        this.changesHasNext.set(rows.length > PAGE_SIZE);
        this.changesState.set(rows.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        const normalized = normalizeError(error);
        this.changesError.set(
          normalized instanceof PortalError
            ? normalized.message
            : 'No se pudo consultar el historial de configuraciones.',
        );
        this.changesState.set('error');
      },
    });
  }

  previousChanges() {
    if (this.changesOffset() > 0) this.loadConfigurationHistory(this.changesOffset() - PAGE_SIZE);
  }

  nextChanges() {
    if (this.changesHasNext()) this.loadConfigurationHistory(this.changesOffset() + PAGE_SIZE);
  }

  formatDateTime(value: string) {
    return new Intl.DateTimeFormat('es-EC', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'America/Guayaquil',
    }).format(new Date(value));
  }

  actionLabel(action: string) {
    const labels: Record<string, string> = {
      OPERATIONAL_SCHEDULE_CREATED: 'Horario creado',
      OPERATIONAL_SCHEDULE_UPDATED: 'Horario actualizado',
      OPERATIONAL_SCHEDULE_REVISED: 'Nueva versión de horario',
      OPERATIONAL_SCHEDULE_END_SCHEDULED: 'Finalización programada',
      OPERATIONAL_SCHEDULE_DEACTIVATED: 'Programación cancelada',
      CALENDAR_EXCEPTION_CREATED: 'Día especial creado',
      CALENDAR_EXCEPTION_UPDATED: 'Día especial actualizado',
      CALENDAR_EXCEPTION_DEACTIVATED: 'Día especial desactivado',
      ADMIN_USER_UPDATED: 'Cuenta actualizada',
      ADMIN_USER_ENABLED: 'Cuenta habilitada',
      ADMIN_USER_DISABLED: 'Cuenta deshabilitada',
      INSPECTOR_CREATED: 'Cuenta de inspector creada',
      INSPECTOR_ACTIVATION_REISSUED: 'Enlace de activación reemitido',
      INSPECTOR_ACTIVATED: 'Cuenta de inspector activada',
    };
    return labels[action] ?? action;
  }

  detailLabel(key: string) {
    const fields: Record<string, string> = {
      zoneId: 'Zona',
      dayOfWeek: 'Día',
      startTime: 'Hora inicial',
      endTime: 'Hora final',
      validFrom: 'Vigente desde',
      validTo: 'Vigente hasta',
      active: 'Activo',
      holidayDate: 'Fecha especial',
      name: 'Nombre',
      holidayType: 'Tipo',
      tariffed: 'Cobro',
      normativeReference: 'Referencia normativa',
      email: 'Correo',
      firstName: 'Nombres',
      lastName: 'Apellidos',
      phone: 'Teléfono',
      enabled: 'Habilitado',
      changedFields: 'Campos modificados',
      previousScheduleId: 'Horario anterior',
      previousVersionEnd: 'Último día de la versión anterior',
      effectiveFrom: 'Efectivo desde',
      previousEnabled: 'Estado anterior',
    };
    if (key === 'reason') return 'Motivo';
    const [version, field] = key.split('.', 2);
    if (version === 'before') return `Anterior · ${fields[field] ?? field}`;
    if (version === 'after') return `Nuevo · ${fields[field] ?? field}`;
    return fields[key] ?? key;
  }

  detailEntries(change: ConfigurationChangeRecord): [string, string][] {
    return Object.entries(change.details);
  }

  hasDetails(change: ConfigurationChangeRecord) {
    return Object.keys(change.details).length > 0;
  }

  closeChangeDetails() {
    this.selectedChange.set(null);
  }

  private toIso(value: string): string | undefined {
    if (!value) return undefined;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
  }
}
