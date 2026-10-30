import { systemEmailUnsavedGuard } from './features/system-email/system-email-unsaved.guard';
import { Routes } from '@angular/router';
import {
  adminGuard,
  permissionGuard,
  previewGuard,
  superadminGuard,
} from './core/auth/access.guards';
const pages: Routes = [
  {
    path: 'dashboard',
    canActivate: [permissionGuard],
    data: { permission: 'dashboard.view' },
    title: 'Inicio | SIMERTPI',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'map',
    canActivate: [permissionGuard],
    data: { permission: 'spaces.view' },
    title: 'Mapa de estacionamientos | SIMERTPI',
    loadComponent: () => import('./features/map/map-coverage').then((m) => m.MapCoverage),
  },
  {
    path: 'personal',
    canActivate: [permissionGuard],
    data: { permission: 'users.view' },
    title: 'Personal municipal | SIMERTPI',
    loadComponent: () => import('./features/municipal-users/personnel').then((m) => m.Personnel),
  },
  {
    path: 'access',
    title: 'Usuarios, roles y permisos | SIMERTPI',
    canActivate: [permissionGuard],
    // Administrators may manage inspectors here; the component hides the
    // superadministrator-only role catalogue and backend policies enforce scope.
    data: { permission: 'users.view' },
    loadComponent: () => import('./features/users-roles/users-roles').then((m) => m.UsersRoles),
  },
  {
    path: 'system/email',
    canActivate: [superadminGuard],
    canDeactivate: [systemEmailUnsavedGuard],
    title: 'Configuración de correo | SIMERTPI',
    loadComponent: () => import('./features/system-email/system-email').then((m) => m.SystemEmail),
  },
  {
    path: 'audit',
    canActivate: [permissionGuard],
    data: { permission: 'audit.view' },
    title: 'Auditoría administrativa | SIMERTPI',
    loadComponent: () => import('./features/audit/audit').then((m) => m.Audit),
  },
  {
    path: 'zones',
    canActivate: [permissionGuard],
    data: { permission: 'zones.view', catalogView: 'zones' },
    title: 'Zonas | SIMERTPI',
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'streets',
    canActivate: [permissionGuard],
    data: { permission: 'streets.view', catalogView: 'streets' },
    title: 'Calles y tramos | SIMERTPI',
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'spaces',
    canActivate: [permissionGuard],
    data: { permission: 'spaces.view', catalogView: 'spaces' },
    title: 'Espacios | SIMERTPI',
    loadComponent: () => import('./features/zones/zones').then((m) => m.Zones),
  },
  {
    path: 'tariffs',
    canActivate: [permissionGuard],
    data: { permission: 'tariffs.view' },
    loadChildren: () => import('./features/tariffs/tariffs.routes').then((m) => m.TARIFF_ROUTES),
  },
  {
    path: 'parking-sessions',
    canActivate: [permissionGuard],
    data: { permission: 'parking_sessions.view' },
    title: 'Estacionamientos y sesiones | SIMERTPI',
    loadComponent: () =>
      import('./features/parking-sessions/parking-sessions').then((m) => m.ParkingSessions),
  },
  {
    path: 'violations',
    canActivate: [permissionGuard],
    data: { permission: 'violations.view' },
    title: 'Infracciones | SIMERTPI',
    loadComponent: () => import('./features/violations/violations').then((m) => m.Violations),
  },
  {
    path: 'inspections',
    canActivate: [permissionGuard],
    data: { permission: 'inspections.view' },
    title: 'Inspecciones | SIMERTPI',
    loadComponent: () => import('./features/inspections/inspections').then((m) => m.Inspections),
  },
  {
    path: 'payments',
    canActivate: [permissionGuard],
    data: { permission: 'payments.view' },
    title: 'Pagos y recaudación | SIMERTPI',
    loadComponent: () => import('./features/payments/payments').then((m) => m.Payments),
  },
  {
    path: 'reports',
    canActivate: [permissionGuard],
    data: { permission: 'reports.view' },
    title: 'Reportes administrativos y financieros | SIMERTPI',
    loadComponent: () => import('./features/reports/reports').then((m) => m.Reports),
  },
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
];
export const routes: Routes = [
  {
    path: 'activate-account',
    title: 'Activar cuenta | SIMERTPI',
    loadComponent: () => import('./features/auth/activate-account').then((m) => m.ActivateAccount),
  },
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
