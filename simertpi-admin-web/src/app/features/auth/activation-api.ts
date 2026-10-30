import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../../core/config/portal-config';

export type ActivationStatus = 'VALID' | 'EXPIRED' | 'INVALID' | 'RATE_LIMITED';
export interface ActivationValidation { status: ActivationStatus; expiresAt: string | null; }

@Injectable({ providedIn: 'root' })
export class ActivationTokenCapture {
  private token: string | null = null;

  constructor() {
    if (typeof window === 'undefined') return;
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const candidate = fragment.get('token') ?? fragment.get('activationToken');
    if (candidate && /^[A-Za-z0-9_-]{43}$/.test(candidate)) this.token = candidate;
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`);
  }

  consume(): string | null {
    const token = this.token;
    this.token = null;
    return token;
  }
}

@Injectable({ providedIn: 'root' })
export class ActivationApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(PORTAL_CONFIG).apiBaseUrl.replace(/\/$/, '')}/municipal/auth`;

  validate(activationToken: string) {
    return this.http.post<ActivationValidation>(`${this.base}/activation/validate`, { activationToken });
  }

  activate(activationToken: string, newPassword: string) {
    return this.http.post<void>(`${this.base}/activate`, { activationToken, newPassword });
  }
}
