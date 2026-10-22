import { Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { TariffHistoryItem, TariffHistoryPage, TariffsApi } from './tariffs-api';
import { formatConfigurationAmount, formatConfigurationDateTime } from './tariffs-format';

interface HistoryChange {
  key: string;
  label: string;
  before: string;
  after: string;
}

interface SnapshotEntry {
  key: string;
  label: string;
  value: string;
}

const SNAPSHOT_LABELS: Record<string, string> = {
  id: 'Identificador de tarifa', versionGroupId: 'Serie tarifaria', previousVersionId: 'Versión anterior',
  rowVersion: 'Número de edición', code: 'Código', name: 'Nombre', zoneId: 'Identificador de zona',
  currency: 'Moneda', amount: 'Importe', durationMinutes: 'Duración por importe', minMinutes: 'Mínimo facturable',
  maxContinuousMinutes: 'Máximo continuo', gracePeriodMinutes: 'Tolerancia', roundingMode: 'Regla de redondeo',
  validFrom: 'Vigente desde', validTo: 'Vigente hasta', normativeReference: 'Referencia normativa',
  active: 'Activa', cancelledAt: 'Cancelada el', cancelledByUserId: 'Identificador de quien canceló',
  cancellationReason: 'Motivo de cancelación',
};

const CHANGE_KEYS = [
  'amount', 'durationMinutes', 'minMinutes', 'maxContinuousMinutes', 'gracePeriodMinutes',
  'roundingMode', 'validFrom', 'validTo', 'normativeReference', 'code', 'name', 'zoneId',
  'currency', 'active', 'cancelledAt', 'cancellationReason',
];

@Component({
  selector: 'app-tariff-history',
  imports: [RouterLink, DataState, PageHeading],
  template: `
    <section lang="es-EC">
      <app-page-heading eyebrow="ADMINISTRACIÓN / CONFIGURACIÓN MUNICIPAL" title="Historial de cambios de tarifas"
        description="Consulte las versiones, fechas, responsables, motivos y valores registrados. El historial conserva las condiciones anteriores.">
        <a class="button secondary" routerLink="/admin/tariffs">Volver a tarifas</a>
      </app-page-heading>
      @if (preview) { <section class="notice preview-note" role="status"><strong>Previsualización local</strong><p>Esta vista no consulta ni modifica datos.</p></section> }
      @if (preview) {
        <section class="card history-empty" role="status"><h2>Historial disponible en el portal administrativo</h2><p>La previsualización no consulta registros históricos ni presenta responsables o cambios de ejemplo.</p></section>
      } @else if (state() === 'loading') {
        <app-data-state kind="loading" title="Consultando historial" description="Cargando cambios registrados para las tarifas." />
      } @else if (state() === 'error') {
        <app-data-state kind="error" title="No se pudo cargar el historial" [description]="errorMessage()">
          <button class="button secondary" type="button" (click)="load(page())">Reintentar</button>
        </app-data-state>
      } @else if (!result()?.items?.length) {
        <section class="card history-empty"><app-data-state kind="empty" title="No hay cambios registrados" description="Todavía no existen eventos de historial para esta consulta." /></section>
      } @else {
        @if (tariffId) {
          <section class="history-series card" aria-label="Serie tarifaria consultada">
            <div><span class="history-kicker">TARIFA CONSULTADA</span><strong>{{ seriesLabel() }}</strong><small>El historial se filtra por esta tarifa y sus versiones relacionadas.</small></div>
            <span class="history-count">{{ result()!.total }} {{ result()!.total === 1 ? 'evento' : 'eventos' }}</span>
          </section>
        } @else {
          <section class="history-series card" aria-label="Resumen del historial">
            <div><span class="history-kicker">HISTORIAL GENERAL</span><strong>Todos los cambios de tarifas</strong><small>Los registros se presentan en páginas de 25 eventos.</small></div>
            <span class="history-count">{{ result()!.total }} {{ result()!.total === 1 ? 'evento' : 'eventos' }}</span>
          </section>
        }
        <section class="card history-panel" aria-labelledby="history-title">
          <header class="history-heading"><div><p class="eyebrow">TRAZABILIDAD ADMINISTRATIVA</p><h2 id="history-title">{{ result()!.total }} cambios registrados</h2><p>Fechas y horas expresadas en la zona de Ecuador (America/Guayaquil).</p></div></header>
          <div class="history-table-scroll" role="region" aria-label="Registros del historial; deslice horizontalmente para ver todas las columnas" tabindex="0">
            <table class="history-table"><thead><tr><th>Fecha y acción</th><th>Tarifa / versión</th><th>Responsable</th><th>Motivo</th><th>Valores modificados</th></tr></thead>
              <tbody>@for (item of result()!.items; track item.id) {
                <tr>
                  <td data-label="Fecha y acción"><time>{{ formatDateTime(item.occurredAt) }}</time><small class="effective-date">Efectiva: {{ item.effectiveAt ? formatDateTime(item.effectiveAt) : 'No especificada' }}</small><span class="history-action" [class]="actionClass(item.action)">{{ actionLabel(item.action) }}</span></td>
                  <td data-label="Tarifa / versión"><strong class="version-code">{{ versionCode(item) }}</strong><small>{{ versionName(item) || 'Nombre no disponible en este registro' }}</small><small class="version-caption">Versión afectada</small></td>
                  <td data-label="Responsable">{{ item.actorName || 'Responsable no registrado en este evento' }}</td>
                  <td data-label="Motivo"><span class="reason-text">{{ item.reason?.trim() || 'No se registró un motivo para este evento histórico.' }}</span></td>
                  <td data-label="Valores modificados">
                    @if (item.beforeState) {
                      <ul class="change-list">@for (change of changePreview(item).items; track change.key) {<li><strong>{{ change.label }}:</strong> {{ change.before }} <span aria-hidden="true">→</span> {{ change.after }}</li>}</ul>
                      @if (changePreview(item).remaining > 0) { <small class="more-changes">{{ changePreview(item).remaining }} cambios adicionales</small> }
                    } @else { <span class="legacy-note">Estado inicial registrado; no hay una versión anterior para comparar.</span> }
                    <button class="button secondary detail-button" type="button" (click)="openDetails(item)" aria-haspopup="dialog">Ver detalle</button>
                  </td>
                </tr>
              }</tbody>
            </table>
          </div>
          <nav class="history-pagination" aria-label="Paginación del historial">
            <button class="button secondary" type="button" [disabled]="page() === 0 || loading()" (click)="load(page() - 1)">Anterior</button>
            <span>Página {{ page() + 1 }} de {{ pageCount() }}</span>
            <button class="button secondary" type="button" [disabled]="page() + 1 >= pageCount() || loading()" (click)="load(page() + 1)">Siguiente</button>
          </nav>
        </section>
      }

      @if (selectedItem(); as selected) {
        <dialog #detailDialog class="tariff-dialog history-detail-dialog" aria-labelledby="history-detail-title" (cancel)="closeDetails($event)" (close)="onDetailClosed()">
          <div class="dialog-content">
            <header class="dialog-heading"><div><p class="eyebrow">{{ actionLabel(selected.action) }}</p><h2 id="history-detail-title">Detalle del cambio</h2></div><button class="icon-button" type="button" autofocus aria-label="Cerrar detalle del cambio" (click)="closeDetails()">×</button></header>
            <div class="dialog-scroll" role="region" aria-label="Detalle histórico desplazable" tabindex="0">
              <section class="detail-section"><h3>Evento y responsable</h3><dl class="history-detail-meta">
                <div><dt>Tarifa / versión</dt><dd>{{ versionCode(selected) }}{{ versionName(selected) ? ' · ' + versionName(selected) : '' }}</dd></div>
                <div><dt>Acción</dt><dd><span class="history-action" [class]="actionClass(selected.action)">{{ actionLabel(selected.action) }}</span></dd></div>
                <div><dt>Fecha del cambio (Ecuador)</dt><dd>{{ formatDateTime(selected.occurredAt) }}</dd></div>
                <div><dt>Vigencia efectiva (Ecuador)</dt><dd>{{ selected.effectiveAt ? formatDateTime(selected.effectiveAt) : 'No especificada' }}</dd></div>
                <div><dt>Responsable</dt><dd>{{ selected.actorName || 'Responsable no registrado en este evento histórico.' }}</dd></div>
                <div class="detail-wide"><dt>Motivo</dt><dd>{{ selected.reason?.trim() || 'No se registró un motivo para este evento histórico.' }}</dd></div>
              </dl></section>
              <section class="detail-section"><h3>Valores anteriores</h3>
                @if (selected.beforeState) { <dl class="snapshot-grid">@for (entry of snapshotEntries(selected.beforeState); track entry.key) {<div><dt>{{ entry.label }}</dt><dd>{{ entry.value }}</dd></div>}</dl> }
                @else { <p class="legacy-note">Este evento no cuenta con una versión anterior registrada.</p> }
              </section>
              <section class="detail-section"><h3>Valores registrados después del cambio</h3>
                <dl class="snapshot-grid">@for (entry of snapshotEntries(selected.afterState); track entry.key) {<div><dt>{{ entry.label }}</dt><dd>{{ entry.value }}</dd></div>}</dl>
              </section>
            </div>
            <footer class="dialog-actions"><button class="button secondary" type="button" (click)="closeDetails()">Cerrar</button></footer>
          </div>
        </dialog>
      }
    </section>
  `,
  styleUrl: './tariff-history.scss',
})
export class TariffHistory {
  private readonly api = inject(TariffsApi);
  private readonly route = inject(ActivatedRoute);
  readonly tariffId = this.route.snapshot.queryParamMap.get('tariffId');
  readonly result = signal<TariffHistoryPage | null>(null);
  readonly selectedItem = signal<TariffHistoryItem | null>(null);
  readonly detailDialog = viewChild<ElementRef<HTMLDialogElement>>('detailDialog');
  readonly preview = this.route.snapshot.pathFromRoot.some((item) => item.data['preview'] === true);
  readonly state = signal<'loading' | 'ready' | 'error'>(this.preview ? 'ready' : 'loading');
  readonly loading = signal(false);
  readonly page = signal(0);
  readonly errorMessage = signal('');
  readonly pageCount = () => Math.max(1, Math.ceil((this.result()?.total ?? 0) / 25));
  readonly formatDateTime = formatConfigurationDateTime;

  constructor() {
    effect(() => {
      const dialog = this.detailDialog()?.nativeElement;
      if (dialog && this.selectedItem() && !dialog.open) dialog.showModal();
      else if (dialog && !this.selectedItem() && dialog.open) dialog.close();
    });
    if (!this.preview) this.load(0);
  }

  load(page: number) {
    if (this.preview) return;
    this.loading.set(true);
    this.state.set('loading');
    this.errorMessage.set('');
    this.api.tariffHistory(page, 25, this.tariffId ?? undefined).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (result) => { this.result.set(result); this.page.set(result.page); this.state.set('ready'); },
      error: (error: unknown) => { this.errorMessage.set(normalizeError(error).message); this.state.set('error'); },
    });
  }

  openDetails(item: TariffHistoryItem) { this.selectedItem.set(item); }

  closeDetails(event?: Event) {
    event?.preventDefault();
    this.selectedItem.set(null);
  }

  onDetailClosed() { this.selectedItem.set(null); }

  actionLabel(action: string) {
    const labels: Record<string, string> = { BASELINE: 'Estado inicial', CREATED: 'Creación', SCHEDULED_UPDATED: 'Programación modificada', SCHEDULED_CANCELLED: 'Programación cancelada', REVISED: 'Nueva versión', ENDED: 'Finalización' };
    return labels[action] ?? 'Cambio registrado';
  }

  actionClass(action: string) {
    const classes: Record<string, string> = { BASELINE: 'history-action-baseline', CREATED: 'history-action-created', SCHEDULED_UPDATED: 'history-action-updated', SCHEDULED_CANCELLED: 'history-action-cancelled', REVISED: 'history-action-revised', ENDED: 'history-action-ended' };
    return `history-action ${classes[action] ?? 'history-action-default'}`;
  }

  versionCode(item: TariffHistoryItem) {
    return this.snapshot(item.afterState, 'code') || this.snapshot(item.beforeState, 'code') || `Registro ${item.tariffId.slice(0, 8)}`;
  }

  versionName(item: TariffHistoryItem) {
    return this.snapshot(item.afterState, 'name') || this.snapshot(item.beforeState, 'name');
  }

  seriesLabel() {
    const item = this.result()?.items.find((entry) => entry.tariffId === this.tariffId || entry.previousTariffId === this.tariffId);
    return item ? `${this.versionCode(item)}${this.versionName(item) ? ' · ' + this.versionName(item) : ''}` : `Tarifa ${this.tariffId}`;
  }

  snapshot(state: Record<string, unknown> | null, key: string): string {
    const value = state?.[key];
    return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  }

  changePreview(item: TariffHistoryItem): { items: HistoryChange[]; remaining: number } {
    const before = item.beforeState;
    if (!before) return { items: [], remaining: 0 };
    const changes = CHANGE_KEYS.filter((key) => before[key] !== item.afterState[key]).map((key) => ({
      key,
      label: SNAPSHOT_LABELS[key] ?? this.humanize(key),
      before: this.formatSnapshotValue(key, before[key], before),
      after: this.formatSnapshotValue(key, item.afterState[key], item.afterState),
    }));
    return { items: changes.slice(0, 2), remaining: Math.max(0, changes.length - 2) };
  }

  snapshotEntries(state: Record<string, unknown>): SnapshotEntry[] {
    return Object.entries(state).map(([key, value]) => ({
      key,
      label: SNAPSHOT_LABELS[key] ?? this.humanize(key),
      value: this.formatSnapshotValue(key, value, state),
    }));
  }

  private formatSnapshotValue(key: string, value: unknown, state: Record<string, unknown>) {
    if (value === null || value === undefined || value === '') return key === 'validTo' ? 'Sin fecha final' : 'Sin dato';
    if (key === 'amount' && (typeof value === 'number' || typeof value === 'string')) {
      return formatConfigurationAmount(value, typeof state['currency'] === 'string' ? state['currency'] : null);
    }
    if (['validFrom', 'validTo', 'cancelledAt'].includes(key) && typeof value === 'string') {
      return formatConfigurationDateTime(value);
    }
    if (key.endsWith('Minutes')) return `${String(value)} minutos`;
    if (typeof value === 'boolean') return value ? 'Sí' : 'No';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }

  private humanize(key: string) {
    return key.replace(/([A-Z])/g, ' $1').replace(/^./, (letter) => letter.toLocaleUpperCase('es-EC'));
  }
}
