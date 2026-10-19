import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { CalendarException, ConfiguredSchedule, ConfiguredTariff, TariffsApi } from './tariffs-api';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';

@Component({
  selector: 'app-tariffs',
  imports: [DatePipe, DecimalPipe, DataState, PageHeading],
  templateUrl: './tariffs.html',
  styleUrl: './tariffs.scss',
})
export class Tariffs {
  private readonly api = inject(TariffsApi);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly tariffs = signal<ConfiguredTariff[]>([]);
  readonly schedules = signal<ConfiguredSchedule[]>([]);
  readonly exceptions = signal<CalendarException[]>([]);
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly errorMessage = signal('');
  readonly query = signal('');
  readonly statusFilter = signal<'all' | 'enabled' | 'disabled'>('all');

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
    }).subscribe({
      next: (data) => {
        this.tariffs.set(data.tariffs);
        this.schedules.set(data.schedules);
        this.exceptions.set(data.exceptions);
        this.state.set(data.tariffs.length + data.schedules.length + data.exceptions.length ? 'ready' : 'empty');
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

}
