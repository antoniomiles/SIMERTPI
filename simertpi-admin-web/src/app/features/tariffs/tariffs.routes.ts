import { Routes } from '@angular/router';

export const TARIFF_ROUTES: Routes = [
  {
    path: '',
    title: 'Configuración de tarifas | SIMERTPI',
    loadComponent: () => import('./tariffs').then((module) => module.Tariffs),
  },
  {
    path: 'billing-days',
    title: 'Días de cobro | SIMERTPI',
    loadComponent: () => import('./billing-days').then((module) => module.BillingDays),
  },
  ...[
    { path: 'holidays', title: 'Días especiales y feriados' },
    { path: 'hours', title: 'Horarios de cobro' },
    { path: 'history', title: 'Historial de cambios' },
  ].map(({ path, title }) => ({
    path,
    title: `${title} | SIMERTPI`,
    data: { pendingTitle: title },
    loadComponent: path === 'history'
      ? () => import('./tariff-history').then((module) => module.TariffHistory)
      : () => import('./tariffs-pending').then((module) => module.TariffsPending),
  })),
];
