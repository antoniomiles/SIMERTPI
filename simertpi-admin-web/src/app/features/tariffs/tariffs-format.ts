const guayaquil = 'America/Guayaquil';

export function currentConfigurationDate(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: '2-digit', day: '2-digit', timeZone: guayaquil,
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function nextConfigurationDate(): string {
  const [year, month, day] = currentConfigurationDate().split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10);
}

export function isValidConfigurationDate(value: string | null | undefined): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
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
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match) return null;
  const y = Number(match[1]), mo = Number(match[2]), d = Number(match[3]);
  const h = Number(match[4]), mi = Number(match[5]);
  const wallTime = Date.UTC(y, mo - 1, d, h, mi);
  const check = new Date(wallTime);
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d
      || check.getUTCHours() !== h || check.getUTCMinutes() !== mi) return null;
  const offsetAt = (instant: number) => {
    const parts = new Intl.DateTimeFormat('en-CA', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      second: '2-digit', hourCycle: 'h23', timeZone: guayaquil,
    }).formatToParts(new Date(instant));
    const part = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((item) => item.type === type)?.value);
    return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second')) - instant;
  };
  let instant = wallTime - offsetAt(wallTime);
  instant = wallTime - offsetAt(instant);
  const date = new Date(instant);
  const localParts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23', timeZone: guayaquil,
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => localParts.find((item) => item.type === type)?.value;
  if (Number(part('year')) !== y || Number(part('month')) !== mo || Number(part('day')) !== d
      || Number(part('hour')) !== h || Number(part('minute')) !== mi) return null;
  return date.toISOString();
}

export function isoToGuayaquilDateTimeLocal(value: string | null | undefined): string {
  if (!value || Number.isNaN(Date.parse(value))) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23', timeZone: guayaquil,
  }).formatToParts(new Date(value));
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
}
