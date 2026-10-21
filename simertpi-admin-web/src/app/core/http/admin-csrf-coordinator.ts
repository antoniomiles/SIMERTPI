import { HttpBackend, HttpClient, HttpErrorResponse, HttpXsrfTokenExtractor } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { finalize, map, Observable, of, retry, shareReplay, switchMap, throwError, timer } from 'rxjs';
import { PORTAL_CONFIG } from '../config/portal-config';
import { PortalError } from './portal-error';

const CSRF_RETRY_DELAYS_MS = [250, 750] as const;

function isTransientReadFailure(error: unknown): boolean {
  return error instanceof HttpErrorResponse && [0, 502, 503, 504].includes(error.status);
}

@Injectable({ providedIn: 'root' })
export class AdminCsrfCoordinator {
  private readonly config = inject(PORTAL_CONFIG);
  private readonly extractor = inject(HttpXsrfTokenExtractor);
  private readonly http = new HttpClient(inject(HttpBackend));
  private pending: Observable<string> | undefined;

  getToken(): Observable<string> {
    if (!this.pending) {
      // Keep the safe preflight outside the interceptor chain; Angular's built-in XSRF
      // interceptor runs before apiInterceptor and would otherwise retain its old header.
      // Share only an in-flight GET so concurrent writes do not race to rotate the cookie.
      const request = this.http
        .get<void>(`${this.config.apiBaseUrl.replace(/\/$/, '')}/admin/auth/csrf`, { withCredentials: true })
        .pipe(
          retry({
            count: CSRF_RETRY_DELAYS_MS.length,
            delay: (error: unknown, retryCount: number) =>
              isTransientReadFailure(error)
                ? timer(CSRF_RETRY_DELAYS_MS[retryCount - 1])
                : throwError(() => error),
          }),
          map(() => this.extractor.getToken()),
          switchMap((token) => token
            ? of(token)
            : throwError(() => new PortalError('forbidden', 'No se pudo obtener un token de seguridad vigente.'))),
          finalize(() => { this.pending = undefined; }),
          shareReplay({ bufferSize: 1, refCount: true }),
        );
      this.pending = request;
    }
    return this.pending;
  }
}
