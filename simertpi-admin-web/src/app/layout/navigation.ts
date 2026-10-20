import type { IconName } from '../shared/ui/icon';
export interface NavigationItem {
  readonly label: string;
  readonly icon: IconName;
  readonly path?: string;
  readonly description?: string;
  readonly children?: readonly NavigationChild[];
}
export interface NavigationChild {
  readonly label: string;
  readonly description: string;
  readonly path: string;
  readonly pending?: boolean;
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
      { label: 'Mapa de estacionamientos', icon: 'map', path: 'map' },
      { label: 'Estacionamientos y sesiones', icon: 'sessions', path: 'parking-sessions' },
      { label: 'Zonas y espacios', icon: 'spaces', path: 'zones' },
      { label: 'Infracciones', icon: 'violation', path: 'violations' },
      { label: 'Inspectores', icon: 'inspectors' },
    ],
  },
  {
    label: 'Finanzas',
    items: [
      { label: 'Pagos y recaudación', icon: 'collection', path: 'payments' },
      { label: 'Conciliación', icon: 'reconciliation' },
      { label: 'Reportes', icon: 'report', path: 'reports' },
    ],
  },
  {
    label: 'Administración',
    items: [
      { label: 'Personal municipal', icon: 'personnel', path: 'personal' },
      { label: 'Usuarios, roles y permisos', icon: 'roles', path: 'access' },
      {
        label: 'Tarifas y horarios',
        icon: 'tariffs',
        children: [
          { label: 'Configuración de tarifas', description: 'Tarifa general y excepciones por zona.', path: 'tariffs' },
          { label: 'Días de cobro', description: 'Calendario semanal general y por zona.', path: 'tariffs/billing-days' },
          { label: 'Días especiales y feriados', description: 'Fechas especiales y excepciones.', path: 'tariffs/holidays', pending: true },
          { label: 'Horarios de cobro', description: 'Horarios generales y excepciones.', path: 'tariffs/hours', pending: true },
          { label: 'Historial de cambios', description: 'Versiones, vigencias y responsables.', path: 'tariffs/history', pending: true },
        ],
      },
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
