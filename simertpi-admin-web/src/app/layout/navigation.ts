import type { IconName } from '../shared/ui/icon';
export interface NavigationItem {
  readonly label: string;
  readonly icon: IconName;
  readonly path?: string;
}
export interface NavigationGroup {
  readonly label: string;
  readonly items: readonly NavigationItem[];
}
export const NAVIGATION: readonly NavigationGroup[] = [
  { label: 'Inicio', items: [{ label: 'Dashboard', icon: 'dashboard', path: 'dashboard' }] },
  {
    label: 'Operación',
    items: [
      { label: 'Mapa de estacionamientos', icon: 'map' },
      { label: 'Zonas y espacios', icon: 'spaces' },
      { label: 'Infracciones', icon: 'violation' },
      { label: 'Inspectores', icon: 'inspectors' },
    ],
  },
  {
    label: 'Finanzas',
    items: [
      { label: 'Recaudación', icon: 'collection' },
      { label: 'Conciliación', icon: 'reconciliation' },
      { label: 'Reportes', icon: 'report' },
    ],
  },
  {
    label: 'Administración',
    items: [
      { label: 'Personal municipal', icon: 'personnel', path: 'personal' },
      { label: 'Usuarios, roles y permisos', icon: 'roles' },
      { label: 'Configuración municipal', icon: 'settings' },
    ],
  },
  {
    label: 'Control',
    items: [
      { label: 'Auditoría', icon: 'audit', path: 'audit' },
      { label: 'Notificaciones', icon: 'notifications' },
      { label: 'Monitoreo', icon: 'monitor' },
    ],
  },
];
