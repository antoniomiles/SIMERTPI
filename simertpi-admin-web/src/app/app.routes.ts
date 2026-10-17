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
