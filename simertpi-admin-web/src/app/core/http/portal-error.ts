import { HttpErrorResponse } from '@angular/common/http';
export type PortalErrorKind =
  'unavailable' | 'unauthorized' | 'forbidden' | 'validation' | 'network' | 'timeout' | 'server' | 'conflict';
export class PortalError extends Error {
  constructor(
    readonly kind: PortalErrorKind,
    message: string,
    readonly code: string | null = null,
    readonly fieldNames: readonly string[] = [],
  ) {
    super(message);
    this.name = 'PortalError';
  }
}
export function normalizeError(error: unknown): PortalError {
  if (error instanceof PortalError) return error;
  if (error instanceof Error && error.name === 'TimeoutError')
    return new PortalError('timeout', 'El servicio tardó demasiado en responder. Puede intentarlo nuevamente.');
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0)
      return new PortalError('network', 'No se pudo conectar con el servicio.');
    if (error.status === 401)
      return new PortalError('unauthorized', 'Debe iniciar una sesión administrativa.');
    if (error.status === 403)
      return new PortalError('forbidden', 'La solicitud fue rechazada por seguridad o permisos. Actualice la pantalla e inténtelo nuevamente.');
    if (error.status === 502 && error.headers.get('X-Admin-Proxy-Error') === 'UPSTREAM_UNAVAILABLE')
      return new PortalError('network', 'No se pudo conectar con el servicio administrativo. Espere un momento y vuelva a intentarlo.');
    if (error.status === 502)
      return new PortalError('unavailable', 'El backend o su gateway respondió HTTP 502. Espere unos segundos y vuelva a intentarlo.');
    if (error.status === 503)
      return new PortalError('unavailable', 'El servicio administrativo respondió HTTP 503 y no está disponible temporalmente. Vuelva a intentarlo en un momento.');
    if (error.status === 504)
      return new PortalError('timeout', 'El servicio administrativo tardó demasiado en responder. No se repitió el inicio de sesión; puede intentarlo nuevamente.');
    if (error.status === 400 || error.status === 422) {
      const fields = error.error?.fields;
      const fieldNames = fields && typeof fields === 'object' && !Array.isArray(fields)
        ? Object.keys(fields).filter((field) => /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(field))
        : [];
      return new PortalError('validation', 'Revise los datos del formulario.', null, fieldNames);
    }
    if (error.status === 409) {
      const message = error.error?.message;
      if (message === 'Ya existe un usuario o correo con esos datos.')
        return new PortalError('conflict', 'Ya existe un usuario o correo con esos datos.', 'INSPECTOR_ALREADY_EXISTS');
      return new PortalError('conflict', 'La operación entra en conflicto con la configuración o el estado actual. Revise los datos e inténtelo nuevamente.',
        typeof message === 'string' && /^[A-Z][A-Z0-9_]{0,95}$/.test(message) ? message : null);
    }
  }
  return new PortalError('server', 'No se pudo completar la solicitud. Inténtelo más tarde.');
}
