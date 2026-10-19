import { HttpErrorResponse } from '@angular/common/http';
export type PortalErrorKind =
  'unavailable' | 'unauthorized' | 'forbidden' | 'validation' | 'network' | 'timeout' | 'server' | 'conflict';
export class PortalError extends Error {
  constructor(
    readonly kind: PortalErrorKind,
    message: string,
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
      return new PortalError('forbidden', 'No tiene permiso para esta operación.');
    if (error.status === 502 && error.headers.get('X-Admin-Proxy-Error') === 'UPSTREAM_UNAVAILABLE')
      return new PortalError('network', 'No se pudo conectar con el servicio administrativo. Espere un momento y vuelva a intentarlo.');
    if (error.status === 502 || error.status === 503)
      return new PortalError('unavailable', 'El servicio administrativo no está disponible o se está iniciando. Espere un momento y vuelva a intentarlo.');
    if (error.status === 504)
      return new PortalError('timeout', 'El servicio administrativo tardó demasiado en responder. No se repitió el inicio de sesión; puede intentarlo nuevamente.');
    if (error.status === 400 || error.status === 422)
      return new PortalError('validation', 'Revise los datos del formulario.');
    if (error.status === 409)
      return new PortalError('conflict', 'Ya existe un usuario con ese nombre o correo, o la cuenta cambió. Actualice el directorio.');
  }
  return new PortalError('server', 'No se pudo completar la solicitud. Inténtelo más tarde.');
}
