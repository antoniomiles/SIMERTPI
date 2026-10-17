import { DestroyRef, inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { catchError, map, Observable, of, switchMap, tap, throwError } from 'rxjs';
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
  readonly identity = this.state.identity;
  readonly authenticated = this.state.authenticated;
  constructor() {
    inject(DestroyRef).onDestroy(() => {
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
    return this.csrf().pipe(
      switchMap(() =>
        this.http.post<AdminIdentity>(`${this.config.apiBaseUrl}/admin/auth/login`, {
          username,
          password,
        }),
      ),
      tap((identity) => this.accept(identity)),
      catchError((error: unknown) => throwError(() => normalizeError(error))),
    );
  }
  restore(): Observable<boolean> {
    if (!this.config.administrativeApiEnabled) return of(false);
    return this.http.get<AdminIdentity>(`${this.config.apiBaseUrl}/admin/auth/me`).pipe(
      tap((identity) => this.accept(identity)),
      map(() => true),
      catchError(() => {
        this.state.clear();
        return of(false);
      }),
    );
  }
  signOut(): Observable<void> {
    return this.csrf().pipe(
      switchMap(() => this.http.post<void>(`${this.config.apiBaseUrl}/admin/auth/logout`, {})),
      tap(() => {
        if (this.expiry) clearTimeout(this.expiry);
        this.state.clear();
      }),
    );
  }
  private csrf() {
    return this.http.get<void>(`${this.config.apiBaseUrl}/admin/auth/csrf`);
  }
  private accept(identity: AdminIdentity) {
    const expires = Date.parse(identity.sessionExpiresAt);
    if (!Number.isFinite(expires) || expires <= Date.now())
      throw new PortalError('unauthorized', 'La sesión ha expirado.');
    this.state.accept(identity);
    if (this.expiry) clearTimeout(this.expiry);
    this.expiry = setTimeout(
      () => {
        this.state.clear();
        if (this.router.url.startsWith('/admin'))
          void this.router.navigate(['/login'], { queryParams: { reason: 'expired' } });
      },
      Math.min(expires - Date.now(), 2147483647),
    );
  }
}
