import { Component, input } from '@angular/core';
@Component({
  selector: 'app-brand',
  template: ` <div
    class="brand"
    [class.brand-icon-only]="iconOnly()"
    [attr.role]="iconOnly() ? 'img' : null"
    [attr.aria-label]="iconOnly() ? 'SIMERTPI · Administración municipal' : null"
  >
    <span class="brand-mark" aria-hidden="true">P<span>·</span></span>
    <span
      ><strong>SIMERTPI</strong
      ><small>{{ compact() ? 'Administración municipal' : 'GAD Municipal de Piñas' }}</small></span
    >
  </div>`,
})
export class Brand {
  readonly compact = input(false);
  readonly iconOnly = input(false);
}
