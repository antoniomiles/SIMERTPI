import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';

@Component({
  selector: 'app-tariffs-pending',
  imports: [DataState, PageHeading],
  template: `
    <section lang="es-EC">
      <app-page-heading
        eyebrow="ADMINISTRACIÓN / CONFIGURACIÓN MUNICIPAL"
        [title]="title"
        description="Esta sección está identificada en la navegación, pero todavía no tiene operaciones administrativas implementadas."
      />
      @if (preview) {
        <section class="notice preview-note" role="status">
          <strong>Previsualización local</strong>
          <p>Esta vista no consulta ni modifica datos.</p>
        </section>
      }
      <section class="card config-section">
        <span class="tag">Pendiente</span>
        <app-data-state
          kind="empty"
          title="Funcionalidad pendiente"
          description="No hay formularios, reglas ni cambios simulados en esta sección."
        />
      </section>
    </section>
  `,
  styleUrl: './tariffs.scss',
})
export class TariffsPending {
  private readonly route = inject(ActivatedRoute);
  readonly title = String(this.route.snapshot.data['pendingTitle'] ?? 'Configuración pendiente');
  readonly preview = this.route.snapshot.pathFromRoot.some((item) => item.data['preview'] === true);
}
