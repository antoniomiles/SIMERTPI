import { Component, input } from '@angular/core';
@Component({
  selector: 'app-page-heading',
  template: ` <header class="page-heading">
    <div>
      <p class="eyebrow">{{ eyebrow() }}</p>
      <h1 tabindex="-1">{{ title() }}</h1>
      <p class="page-description">{{ description() }}</p>
    </div>
    <ng-content />
  </header>`,
})
export class PageHeading {
  readonly eyebrow = input('ADMINISTRACIÓN MUNICIPAL');
  readonly title = input.required<string>();
  readonly description = input('');
}
