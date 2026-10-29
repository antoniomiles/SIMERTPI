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
  {
    path: 'holidays',
    title: 'Días especiales y feriados | SIMERTPI',
    loadComponent: () =>
      import('./calendar-exceptions').then((module) => module.CalendarExceptions),
  },
  {
    path: 'hours',
    title: 'Horarios de cobro | SIMERTPI',
    loadComponent: () => import('./operating-hours').then((module) => module.OperatingHours),
  },
  {
    path: 'history',
    title: 'Historial de cambios | SIMERTPI',
    loadComponent: () => import('./tariff-history').then((module) => module.TariffHistory),
  },
];
