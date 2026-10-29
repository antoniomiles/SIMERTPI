import { Component, input } from '@angular/core';

export type AdminConfigStatusTone =
  'active' | 'scheduled' | 'cancelled' | 'ended' | 'inactive' | 'neutral';

@Component({
  selector: 'app-admin-config-status',
  standalone: true,
  template: `
    <span
      class="admin-config-status"
      role="status"
      aria-live="polite"
      [class.admin-config-status--active]="tone() === 'active'"
      [class.admin-config-status--scheduled]="tone() === 'scheduled'"
      [class.admin-config-status--cancelled]="tone() === 'cancelled'"
      [class.admin-config-status--ended]="tone() === 'ended'"
      [class.admin-config-status--inactive]="tone() === 'inactive'"
      [class.admin-config-status--neutral]="tone() === 'neutral'"
      [attr.data-tone]="tone()"
      [attr.aria-label]="'Estado: ' + label()"
    >
      <span class="admin-config-status__dot" aria-hidden="true"></span>
      {{ label() }}
    </span>
  `,
  styleUrl: './admin-config-status.scss',
})
export class AdminConfigStatus {
  readonly label = input.required<string>();
  readonly tone = input<AdminConfigStatusTone>('neutral');
}
