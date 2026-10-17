import { DOCUMENT, inject, InjectionToken, isDevMode } from '@angular/core';
import { environment } from '../../../environments/environment';
export const PORTAL_CONFIG = new InjectionToken('PORTAL_CONFIG', { factory: () => environment });
export function permitsLocalPreview(
  enabled: boolean,
  development: boolean,
  hostname: string,
): boolean {
  return enabled && development && ['localhost', '127.0.0.1', '[::1]', '::1'].includes(hostname);
}
export function localPreviewAllowed(): boolean {
  const config = inject(PORTAL_CONFIG);
  const document = inject(DOCUMENT);
  return permitsLocalPreview(config.localPreview, isDevMode(), document.location?.hostname ?? '');
}
