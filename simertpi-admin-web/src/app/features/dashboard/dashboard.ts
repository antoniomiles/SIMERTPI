import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageHeading } from '../../shared/ui/page-heading';
@Component({
  selector: 'app-dashboard',
  imports: [PageHeading, RouterLink],
  template: `
    <app-page-heading
      eyebrow="INICIO / DASHBOARD"
      title="Administración municipal"
      description="Un espacio para organizar, supervisar y acompañar la operación de SIMERTPI."
      ><span class="tag status-tag">Base del portal</span></app-page-heading
    >
    <section class="welcome-panel" aria-labelledby="welcome-title">
      <div>
        <span class="eyebrow">GAD MUNICIPAL DE PIÑAS</span>
        <h2 id="welcome-title">La gestión comienza con<br />las personas.</h2>
        <p>
          Estamos preparando las herramientas para administrar el estacionamiento municipal con
          seguridad y trazabilidad.
        </p>
        <a class="button primary" routerLink="../personal"
          >Revisar personal municipal <span aria-hidden="true">→</span></a
        >
      </div>
      <div class="welcome-graphic" aria-hidden="true"><span>P</span><i></i><b>SIMERTPI</b></div>
    </section>
    <div class="section-title">
      <h2>Estado del portal</h2>
      <span>Implementación progresiva</span>
    </div>
    <div class="readiness-grid">
      <article class="card readiness-card">
        <span class="feature-icon" aria-hidden="true">▦</span
        ><span class="tag ready-tag">Disponible para revisar</span>
        <h3>Base administrativa</h3>
        <p>Navegación, formularios y componentes de interfaz en una vista local separada.</p>
      </article>
      <article class="card readiness-card">
        <span class="feature-icon blue" aria-hidden="true">⚿</span
        ><span class="tag">Próxima etapa</span>
        <h3>Acceso seguro</h3>
        <p>Sesiones administrativas, permisos y creación segura del primer administrador.</p>
      </article>
      <article class="card readiness-card">
        <span class="feature-icon yellow" aria-hidden="true">⌁</span
        ><span class="tag">Pendiente de integración</span>
        <h3>Indicadores reales</h3>
        <p>La ocupación y recaudación se mostrarán cuando existan contratos y datos verificados.</p>
      </article>
    </div>
    <section class="card next-stage" aria-labelledby="next-title">
      <div>
        <p class="eyebrow">SIGUIENTE HITO</p>
        <h2 id="next-title">Habilitar la administración de personal</h2>
        <p>
          Primero autenticación y permisos. Después, altas de inspectores con auditoría y validación
          en la aplicación municipal.
        </p>
      </div>
      <span class="tag">Sin operaciones habilitadas</span>
    </section>
  `,
})
export class Dashboard {}
