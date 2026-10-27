import { DestroyRef, inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { catchError, map, Observable, of, tap, throwError } from 'rxjs';
import { PORTAL_CONFIG } from '../config/portal-config';
import { normalizeError, PortalError } from '../http/portal-error';
import { AdminIdentity, AdminSessionState } from './admin-session-state';
@Injectable({ providedIn: 'root' })
export class AdminSession {
  private readonly http = inject(HttpClient);
  private readonly config = inject(PORTAL_CONFIG);
  private readonly router = inject(Router);
  private readonly state = inject(AdminSessionState);
  private expiry: ReturnType<typeof setTimeout> | undefined;
  private serverClockOffsetMs = 0;
  private authFlowVersion = 0;
  private refreshInFlight = false;
  readonly identity = this.state.identity;
  readonly authenticated = this.state.authenticated;
  constructor() {
    const destroy = inject(DestroyRef);
    const unregisterRefresh = this.state.registerRefreshHandler(() => this.refreshAfterActivity());
    destroy.onDestroy(() => {
      unregisterRefresh();
      if (this.expiry) clearTimeout(this.expiry);
    });
  }
  signIn(username: string, password: string): Observable<AdminIdentity> {
    if (!this.config.administrativeApiEnabled)
      return throwError(
        () =>
          new PortalError(
            'unavailable',
            'El acceso administrativo aún no está habilitado. No se enviaron las credenciales.',
          ),
      );
    const version = ++this.authFlowVersion;
    return this.http.post<AdminIdentity>(`${this.config.apiBaseUrl}/admin/auth/login`, {
          username,
          password,
        }, { observe: 'response' }).pipe(
      map((response) => {
        this.captureServerClock(response.headers.get('Date'));
        if (!response.body) throw new PortalError('unavailable', 'El servidor no confirmó la identidad administrativa.');
        return response.body;
      }),
      tap((identity) => { if (version === this.authFlowVersion) this.accept(identity); }),
      catchError((error: unknown) => throwError(() => normalizeError(error))),
    );
  }
  restore(): Observable<boolean> {
    if (!this.config.administrativeApiEnabled) return of(false);
    const version = this.authFlowVersion;
    return this.http.get<AdminIdentity>(`${this.config.apiBaseUrl}/admin/auth/me`, { observe: 'response' }).pipe(
      map((response) => {
        this.captureServerClock(response.headers.get('Date'));
        if (!response.body) throw new PortalError('unauthorized', 'El servidor no confirmó la sesión administrativa.');
        return response.body;
      }),
      tap((identity) => { if (version === this.authFlowVersion) this.accept(identity); }),
      map(() => true),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401 && version === this.authFlowVersion)
          this.state.clear();
        return of(false);
      }),
    );
  }
  signOut(): Observable<void> {
    const version = ++this.authFlowVersion;
    return this.http.post<void>(`${this.config.apiBaseUrl}/admin/auth/logout`, {}).pipe(
      tap(() => {
        if (version === this.authFlowVersion) {
          if (this.expiry) clearTimeout(this.expiry);
          this.state.clear();
        }
      }),
    );
  }
  private refreshAfterActivity() {
    if (this.refreshInFlight || !this.state.authenticated()) return;
    this.refreshInFlight = true;
    this.restore().subscribe({ next: () => { this.refreshInFlight = false; }, error: () => { this.refreshInFlight = false; } });
  }
  private captureServerClock(dateHeader: string | null) {
    if (!dateHeader) return;
    const serverTime = Date.parse(dateHeader);
    if (Number.isFinite(serverTime)) this.serverClockOffsetMs = serverTime - Date.now();
  }
  private accept(identity: AdminIdentity) {
    const expires = Date.parse(identity.sessionExpiresAt);
    const serverNow = Date.now() + this.serverClockOffsetMs;
    if (!Number.isFinite(expires) || expires <= serverNow)
      throw new PortalError('unauthorized', 'La sesión ha expirado.');
    this.state.accept(identity);
    if (this.expiry) clearTimeout(this.expiry);
    const expectedExpiry = identity.sessionExpiresAt;
    this.expiry = setTimeout(
      () => this.expireOrWaitForRenewal(expectedExpiry),
      Math.min(Math.max(0, expires - serverNow), 2147483647),
    );
  }
  private expireOrWaitForRenewal(expectedExpiry: string) {
    const current = this.state.identity();
    if (!current || current.sessionExpiresAt !== expectedExpiry) return;
    // Don't extend an idle session with a timer-driven request. A /me request
    // is only made after real authenticated application traffic. If that
    // renewal is already in flight at the predicted deadline, let its server
    // response replace the deadline; otherwise the idle policy expires locally.
    if (this.refreshInFlight) {
      this.expiry = setTimeout(() => this.expireOrWaitForRenewal(expectedExpiry), 1000);
      return;
    }
    this.expiry = undefined;
    this.state.clear();
    if (this.router.url.startsWith('/admin'))
      void this.router.navigate(['/login'], { queryParams: { reason: 'expired' } });
  }
}
