const guayaquil = 'America/Guayaquil';

export function currentConfigurationDate(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: '2-digit', day: '2-digit', timeZone: guayaquil,
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function formatConfigurationDate(value: string | null | undefined): string {
  if (!value) return 'No definida';
  const [year, month, day] = value.slice(0, 10).split('-').map(Number);
  if (!year || !month || !day) return 'Fecha no válida';
  return new Intl.DateTimeFormat('es-EC', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(year, month - 1, day)));
}

export function formatConfigurationDateTime(value: string | null | undefined): string {
  if (!value) return 'No definida';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Fecha no válida';
  return new Intl.DateTimeFormat('es-EC', {
    dateStyle: 'long', timeStyle: 'short', timeZone: guayaquil,
  }).format(date);
}

export function formatConfigurationAmount(value: string | number, currency: string | null): string {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return 'Importe no disponible';
  const formatted = new Intl.NumberFormat('es-EC', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
  return `${formatted} ${currency ?? 'Moneda no configurada'}`;
}

export function localDateTimeToIso(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
