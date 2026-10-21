import { HttpInterceptorFn, HttpErrorResponse, HttpRequest, HttpResponse } from '@angular/common/http';
import { DOCUMENT, inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, filter, Observable, retry, switchMap, throwError, timeout, timer } from 'rxjs';
import { PORTAL_CONFIG } from '../config/portal-config';
import { AdminSessionState } from '../auth/admin-session-state';
import { normalizeError, PortalError } from './portal-error';
export const ADMIN_AUTH_TIMEOUT_MS = 130000;
export const ADMIN_API_TIMEOUT_MS = 15000;
const ADMIN_CSRF_PREFLIGHT_TIMEOUT_MS = ADMIN_AUTH_TIMEOUT_MS + ADMIN_API_TIMEOUT_MS + 5000;
const ADMIN_AUTH_PATHS = new Set([
  '/api/v1/admin/auth/csrf',
  '/api/v1/admin/auth/login',
  '/api/v1/admin/auth/me',
  '/api/v1/admin/auth/logout',
]);
export function requestTimeoutMs(pathname: string): number {
  return ADMIN_AUTH_PATHS.has(pathname) ? ADMIN_AUTH_TIMEOUT_MS : ADMIN_API_TIMEOUT_MS;
}
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const EXPLICIT_CSRF_FLOWS = new Set([
  '/api/v1/admin/auth/csrf',
  '/api/v1/admin/auth/login',
  '/api/v1/admin/auth/logout',
]);
const CSRF_RETRY_DELAYS_MS = [250, 750] as const;

function isTransientReadFailure(error: unknown): boolean {
  return error instanceof HttpErrorResponse && [0, 502, 503, 504].includes(error.status);
}

function retryCsrfGet<T>(response: Observable<T>): Observable<T> {
  return response.pipe(
    retry({
      count: CSRF_RETRY_DELAYS_MS.length,
      delay: (error: unknown, retryCount: number) =>
        isTransientReadFailure(error)
          ? timer(CSRF_RETRY_DELAYS_MS[retryCount - 1])
          : throwError(() => error),
    }),
  );
}

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
  const credentialed = request.clone({ withCredentials: true });
  const csrfPreflight =
    target.pathname.startsWith('/api/v1/admin/') &&
    !SAFE_METHODS.has(request.method.toUpperCase()) &&
    !EXPLICIT_CSRF_FLOWS.has(target.pathname);
  const csrfRead = request.method.toUpperCase() === 'GET' && target.pathname === '/api/v1/admin/auth/csrf';
  const response = csrfPreflight
    ? retryCsrfGet(
        next(
          new HttpRequest('GET', `${config.apiBaseUrl.replace(/\/$/, '')}/admin/auth/csrf`, {
            withCredentials: true,
          }),
        ),
      ).pipe(
        filter((event): event is HttpResponse<unknown> => event instanceof HttpResponse),
        switchMap(() => next(credentialed)),
      )
    : csrfRead
      ? retryCsrfGet(next(credentialed))
      : next(credentialed);
  return response.pipe(
    timeout(csrfPreflight ? ADMIN_CSRF_PREFLIGHT_TIMEOUT_MS : requestTimeoutMs(target.pathname)),
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
