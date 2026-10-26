import { Routes } from '@angular/router';
import { adminGuard, previewGuard } from './core/auth/access.guards';
const pages: Routes = [
  {
    path: 'dashboard',
    title: 'Inicio | SIMERTPI',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'map',
    title: 'Mapa de estacionamientos | SIMERTPI',
    loadComponent: () => import('./features/map/map-coverage').then((m) => m.MapCoverage),
  },
  {
    path: 'personal',
    title: 'Personal municipal | SIMERTPI',
    loadComponent: () => import('./features/municipal-users/personnel').then((m) => m.Personnel),
  },
  {
    path: 'access',
    title: 'Usuarios, roles y permisos | SIMERTPI',
    loadComponent: () => import('./features/users-roles/users-roles').then((m) => m.UsersRoles),
  },
  {
    path: 'audit',
    title: 'Auditoría administrativa | SIMERTPI',
    loadComponent: () => import('./features/audit/audit').then((m) => m.Audit),
  },
  {
    path: 'zones',
    title: 'Zonas | SIMERTPI',
    data: { catalogView: 'zones' },
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'streets', title: 'Calles y tramos | SIMERTPI', data: { catalogView: 'streets' },
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'spaces', title: 'Espacios | SIMERTPI', data: { catalogView: 'spaces' },
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'tariffs',
    loadChildren: () => import('./features/tariffs/tariffs.routes').then((m) => m.TARIFF_ROUTES),
  },
  {
    path: 'parking-sessions',
    title: 'Estacionamientos y sesiones | SIMERTPI',
    loadComponent: () =>
      import('./features/parking-sessions/parking-sessions').then((m) => m.ParkingSessions),
  },
  {
    path: 'violations',
    title: 'Infracciones | SIMERTPI',
    loadComponent: () => import('./features/violations/violations').then((m) => m.Violations),
  },
  {
    path: 'payments',
    title: 'Pagos y recaudación | SIMERTPI',
    loadComponent: () => import('./features/payments/payments').then((m) => m.Payments),
  },
  {
    path: 'reports',
    title: 'Reportes administrativos y financieros | SIMERTPI',
    loadComponent: () => import('./features/reports/reports').then((m) => m.Reports),
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
