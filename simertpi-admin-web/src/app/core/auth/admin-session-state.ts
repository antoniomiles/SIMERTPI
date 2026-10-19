import { computed, Injectable, signal } from '@angular/core';
export interface AdminIdentity {
  readonly userId: string;
  readonly username: string;
  readonly roles: readonly string[];
  readonly enabled: boolean;
  readonly sessionExpiresAt: string;
  readonly absoluteExpiresAt: string;
}
@Injectable({ providedIn: 'root' })
export class AdminSessionState {
  private readonly value = signal<AdminIdentity | null>(null);
  readonly identity = this.value.asReadonly();
  readonly authenticated = computed(() => !!this.value());
  accept(identity: AdminIdentity) {
    if (!identity.enabled || !identity.roles.includes('SIMERTPI_ADMIN'))
      throw new Error('Administrative identity required');
    this.value.set(identity);
  }
  clear() {
    this.value.set(null);
  }
}
export function safeReturnUrl(value: string | null): string {
  return value === '/admin/personal' ||
    value === '/admin/dashboard' ||
    value === '/admin/map' ||
    value === '/admin/payments' ||
    value === '/admin/reports' ||
    value === '/admin/violations'
    ? value
    : '/admin/dashboard';
}
