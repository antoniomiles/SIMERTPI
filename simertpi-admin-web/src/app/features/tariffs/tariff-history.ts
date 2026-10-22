import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { TariffHistoryItem, TariffHistoryPage, TariffsApi } from './tariffs-api';
import { formatConfigurationAmount, formatConfigurationDateTime } from './tariffs-format';

@Component({
  selector: 'app-tariff-history',
  imports: [RouterLink, DataState, PageHeading],
  template: `
    <section lang="es-EC">
      <app-page-heading eyebrow="ADMINISTRACIÓN / CONFIGURACIÓN MUNICIPAL" title="Historial de cambios de tarifas"
        description="Consulte versiones, vigencias, responsables y motivos. Las operaciones económicas anteriores se conservan como registros históricos.">
        <a class="button secondary" routerLink="/admin/tariffs">Volver a tarifas</a>
      </app-page-heading>
      @if (preview) { <section class="notice preview-note" role="status"><strong>Previsualización local</strong><p>Esta vista no consulta ni modifica datos.</p></section> }
      @if (preview) {
        <section class="card config-section" role="status"><h2>Historial disponible en el portal administrativo</h2><p>La previsualización no consulta registros históricos ni presenta responsables o cambios de ejemplo.</p></section>
      } @else if (state() === 'loading') {
        <app-data-state kind="loading" title="Consultando historial" description="Cargando cambios registrados para las tarifas." />
      } @else if (state() === 'error') {
        <app-data-state kind="error" title="No se pudo cargar el historial" [description]="errorMessage()">
          <button class="button secondary" type="button" (click)="load(page())">Reintentar</button>
        </app-data-state>
      } @else if (!result()?.items?.length) {
        <section class="card config-section"><app-data-state kind="empty" title="No hay cambios registrados" description="Todavía no existen eventos de historial para esta consulta." /></section>
      } @else {
        <section class="card config-section" aria-labelledby="history-title">
          <header class="section-heading"><div><p class="eyebrow">{{ tariffId ? 'SERIE TARIFARIA' : 'TODAS LAS TARIFAS' }}</p><h2 id="history-title">{{ result()!.total }} cambios registrados</h2></div></header>
          <div class="table-scroll"><table><thead><tr><th>Fecha y hora (Ecuador)</th><th>Acción</th><th>Tarifa / versión</th><th>Responsable</th><th>Vigencia efectiva</th><th>Motivo y cambios</th></tr></thead>
            <tbody>@for (item of result()!.items; track item.id) {
              <tr><td>{{ formatDateTime(item.occurredAt) }}</td><td>{{ actionLabel(item.action) }}</td>
                <td><strong>{{ snapshot(item.afterState, 'code') || 'Versión registrada' }}</strong><small>{{ snapshot(item.afterState, 'name') }}</small></td>
                <td>{{ item.actorName || 'Responsable no disponible' }}</td><td>{{ formatDateTime(item.effectiveAt) }}</td>
                <td><p>{{ item.reason }}</p><small>{{ changeSummary(item) }}</small></td></tr>
            }</tbody></table></div>
          <nav class="history-pagination" aria-label="Paginación del historial">
            <button class="button secondary" type="button" [disabled]="page() === 0 || loading()" (click)="load(page() - 1)">Anterior</button>
            <span>Página {{ page() + 1 }} de {{ pageCount() }}</span>
            <button class="button secondary" type="button" [disabled]="page() + 1 >= pageCount() || loading()" (click)="load(page() + 1)">Siguiente</button>
          </nav>
        </section>
      }
    </section>
  `,
  styles: [`
    .config-section { margin-top: 22px; }
    .history-pagination { display:flex; align-items:center; justify-content:center; gap:16px; padding-top:18px; }
    td p { margin:0 0 5px; white-space:normal; min-width:220px; }
    td small { display:block; }
    @media(max-width:600px) { .history-pagination { flex-wrap:wrap; } }
  `],
})
export class TariffHistory {
  private readonly api = inject(TariffsApi);
  private readonly route = inject(ActivatedRoute);
  readonly tariffId = this.route.snapshot.queryParamMap.get('tariffId');
  readonly result = signal<TariffHistoryPage | null>(null);
  readonly preview = this.route.snapshot.pathFromRoot.some((item) => item.data['preview'] === true);
  readonly state = signal<'loading' | 'ready' | 'error'>(this.preview ? 'ready' : 'loading');
  readonly loading = signal(false);
  readonly page = signal(0);
  readonly errorMessage = signal('');
  readonly pageCount = () => Math.max(1, Math.ceil((this.result()?.total ?? 0) / 25));
  readonly formatDateTime = formatConfigurationDateTime;

  constructor() { if (!this.preview) this.load(0); }

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

  actionLabel(action: string) {
    const labels: Record<string, string> = { BASELINE: 'Estado inicial', CREATED: 'Creación', SCHEDULED_UPDATED: 'Programación modificada', SCHEDULED_CANCELLED: 'Programación cancelada', REVISED: 'Nueva versión', ENDED: 'Finalización' };
    return labels[action] ?? 'Cambio registrado';
  }

  snapshot(state: Record<string, unknown> | null, key: string): string {
    const value = state?.[key];
    return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  }

  changeSummary(item: TariffHistoryItem) {
    const before = item.beforeState;
    const after = item.afterState;
    if (!before) return `Importe registrado: ${this.amount(after)}`;
    const changes: string[] = [];
    for (const key of ['amount', 'durationMinutes', 'minMinutes', 'maxContinuousMinutes', 'gracePeriodMinutes', 'validFrom', 'validTo', 'normativeReference']) {
      if (before[key] !== after[key]) changes.push(`${this.fieldLabel(key)}: ${String(before[key] ?? '—')} → ${String(after[key] ?? '—')}`);
    }
    return changes.length ? changes.join(' · ') : 'Se conservó la configuración sin diferencias en los campos principales.';
  }

  private amount(state: Record<string, unknown>) {
    const amount = state['amount'];
    return typeof amount === 'string' || typeof amount === 'number'
      ? formatConfigurationAmount(amount, typeof state['currency'] === 'string' ? state['currency'] : null)
      : 'No disponible';
  }

  private fieldLabel(key: string) {
    const labels: Record<string, string> = { amount: 'Importe', durationMinutes: 'Duración', minMinutes: 'Mínimo', maxContinuousMinutes: 'Máximo continuo', gracePeriodMinutes: 'Tolerancia', validFrom: 'Inicio', validTo: 'Fin', normativeReference: 'Referencia normativa' };
    return labels[key] ?? key;
  }
}
