import { Routes } from '@angular/router';
import { adminGuard, previewGuard } from './core/auth/access.guards';
const pages: Routes = [
  {
    path: 'dashboard',
    title: 'Inicio | SIMERTPI',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'personal',
    title: 'Personal municipal | SIMERTPI',
    loadComponent: () => import('./features/municipal-users/personnel').then((m) => m.Personnel),
  },
  {
    path: 'audit',
    title: 'Auditoría administrativa | SIMERTPI',
    loadComponent: () => import('./features/audit/audit').then((m) => m.Audit),
  },
  {
    path: 'zones',
    title: 'Zonas, calles y espacios | SIMERTPI',
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'tariffs',
    title: 'Tarifas y horarios | SIMERTPI',
    loadComponent: () => import('./features/tariffs/tariffs').then((m) => m.Tariffs),
  },
  {
    path: 'parking-sessions',
    title: 'Estacionamientos y sesiones | SIMERTPI',
    loadComponent: () =>
      import('./features/parking-sessions/parking-sessions').then((m) => m.ParkingSessions),
  },
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
];
export const routes: Routes = [
  {
    path: 'login',
    title: 'Acceso administrativo | SIMERTPI',
    loadComponent: () => import('./features/auth/login').then((m) => m.Login),
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    canActivateChild: [adminGuard],
    loadComponent: () => import('./layout/admin-shell').then((m) => m.AdminShell),
    children: pages,
  },
  {
    path: 'preview',
    canMatch: [previewGuard],
    data: { preview: true },
    loadComponent: () => import('./layout/admin-shell').then((m) => m.AdminShell),
    children: pages,
  },
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  { path: '**', redirectTo: 'login' },
];
