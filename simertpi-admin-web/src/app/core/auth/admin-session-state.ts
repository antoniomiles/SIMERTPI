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
  private lastServerSyncAt = 0;
  private refreshHandler: (() => void) | undefined;

  registerRefreshHandler(handler: () => void): () => void {
    this.refreshHandler = handler;
    return () => {
      if (this.refreshHandler === handler) this.refreshHandler = undefined;
    };
  }

  noteAuthenticatedRequest() {
    if (!this.authenticated() || Date.now() - this.lastServerSyncAt < 30_000) return;
    this.lastServerSyncAt = Date.now();
    this.refreshHandler?.();
  }

  accept(identity: AdminIdentity) {
    if (!identity.enabled || !identity.roles.includes('SIMERTPI_ADMIN'))
      throw new Error('Administrative identity required');
    this.value.set(identity);
    this.lastServerSyncAt = Date.now();
  }
  clear() {
    this.value.set(null);
    this.lastServerSyncAt = 0;
  }
}
export function safeReturnUrl(value: string | null): string {
  if (value === null || value.includes('\\') || value.includes('%') || value.includes('?') || value.includes('#'))
    return '/admin/dashboard';
  return /^\/admin\/(?:dashboard|map|personal|access|audit|zones|streets|spaces|parking-sessions|violations|payments|reports|tariffs(?:\/(?:billing-days|holidays|hours|history))?)$/.test(value)
    ? value
    : '/admin/dashboard';
}
