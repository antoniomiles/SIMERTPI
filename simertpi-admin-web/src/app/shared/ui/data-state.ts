import { Component, input } from '@angular/core';
export type DataStateKind = 'empty' | 'loading' | 'error';
@Component({
  selector: 'app-data-state',
  template: ` <div
    class="data-state"
    [attr.role]="kind() === 'error' ? 'alert' : 'status'"
    [attr.aria-busy]="kind() === 'loading'"
  >
    <span class="state-symbol" [class.spinning]="kind() === 'loading'" aria-hidden="true">{{
      kind() === 'loading' ? '◌' : kind() === 'error' ? '!' : '≡'
    }}</span>
    <h3>{{ title() }}</h3>
    <p>{{ description() }}</p>
    <ng-content />
  </div>`,
})
export class DataState {
  readonly kind = input<DataStateKind>('empty');
  readonly title = input.required<string>();
  readonly description = input.required<string>();
}
