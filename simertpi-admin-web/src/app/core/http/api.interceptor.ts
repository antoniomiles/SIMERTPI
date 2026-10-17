import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT, inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError, timeout } from 'rxjs';
import { PORTAL_CONFIG } from '../config/portal-config';
import { AdminSessionState } from '../auth/admin-session-state';
import { normalizeError, PortalError } from './portal-error';
export const apiInterceptor: HttpInterceptorFn = (request, next) => {
  const config = inject(PORTAL_CONFIG),
    document = inject(DOCUMENT),
    state = inject(AdminSessionState),
    router = inject(Router);
  if (!config.administrativeApiEnabled)
    return throwError(
      () => new PortalError('unavailable', 'La API administrativa aún no está habilitada.'),
    );
  const target = new URL(request.url, document.location.origin);
  if (target.origin !== document.location.origin || !target.pathname.startsWith('/api/v1/admin/'))
    return throwError(() => new PortalError('forbidden', 'Destino administrativo no autorizado.'));
  return next(request.clone({ withCredentials: true })).pipe(
    timeout(15000),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        state.clear();
        if (router.url.startsWith('/admin'))
          void router.navigate(['/login'], { queryParams: { reason: 'expired' } });
      }
      return throwError(() => normalizeError(error));
    }),
  );
};
