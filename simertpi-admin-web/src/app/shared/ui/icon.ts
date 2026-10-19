import { Component, input } from '@angular/core';
const PATHS = {
  dashboard: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  map: 'm9 18-6 3V6l6-3 6 3 6-3v15l-6 3-6-3z M9 3v15 M15 6v15',
  spaces: 'M3 3h18v18H3z M3 9h18 M3 15h18 M9 3v18 M15 3v18',
  violation: 'm12 3 10 18H2L12 3z M12 9v5 M12 17h.01',
  inspectors:
    'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M3 21v-2a6 6 0 0 1 12 0v2 M17 5a3 3 0 0 1 0 6 M21 21v-2a6 6 0 0 0-3-5.2',
  collection: 'M3 5h18v14H3z M3 9h18 M16 14h2 M7 14h3',
  reconciliation: 'M4 7h15 M15 3l4 4-4 4 M20 17H5 M9 13l-4 4 4 4',
  report: 'M6 3h9l4 4v14H6z M14 3v5h5 M9 12h7 M9 16h7',
  personnel:
    'M5 3h14v18H5z M8 3v3h8V3 M12 14a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z M8 18a4 4 0 0 1 8 0',
  roles: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3z m-4 9 3 3 5-6',
  settings:
    'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2',
  tariffs: 'M4 5h16 M4 12h16 M4 19h16 M8 3v4 M16 10v4 M10 17v4',
  audit: 'M8 3h8 M9 3v3H5v15h14V6h-4V3 M8 11h8 M8 15h5',
  notifications: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
  monitor: 'M2 12h5l3-8 4 16 3-8h5',
  panelClose: 'M3 3h18v18H3z M9 3v18 m8-13-4 4 4 4',
  panelOpen: 'M3 3h18v18H3z M9 3v18 m4-13 4 4-4 4',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  close: 'm6 6 12 12 M6 18 18 6',
} as const;
export type IconName = keyof typeof PATHS;
@Component({
  selector: 'app-icon',
  host: { 'aria-hidden': 'true' },
  template: `
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.75"
      stroke-linecap="round"
      stroke-linejoin="round"
      focusable="false"
      aria-hidden="true"
    >
      <path [attr.d]="paths[name()]" />
    </svg>
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly paths = PATHS;
}
