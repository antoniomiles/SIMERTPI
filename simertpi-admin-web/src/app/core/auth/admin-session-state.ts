import { computed, Injectable, signal } from '@angular/core';
export interface AdminIdentity {
  readonly userId: string;
  readonly username: string;
  readonly roles: readonly string[];
  /** Effective permission codes supplied by the backend; absent only for legacy sessions. */
  readonly permissions?: readonly string[];
  readonly enabled: boolean;
  readonly sessionExpiresAt: string;
  readonly absoluteExpiresAt: string;
}
const ADMIN_ROLES = new Set(['SIMERTPI_ADMIN', 'IT_ADMIN']);
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
    const hasAdminRole = identity.roles.some((role) => ADMIN_ROLES.has(role));
    const hasAdministrativeCapability = (identity.permissions?.length ?? 0) > 0;
    if (!identity.enabled || (!hasAdminRole && !hasAdministrativeCapability))
      throw new Error('Administrative identity required');
    this.value.set(identity);
    this.lastServerSyncAt = Date.now();
  }
  hasPermission(permission: string): boolean {
    const identity = this.value();
    if (!identity?.enabled) return false;
    if (identity.permissions !== undefined) return identity.permissions.includes(permission);
    // Compatibility for sessions issued by V50 before the permission claim existed.
    return identity.roles.some((role) => ADMIN_ROLES.has(role));
  }
  clear() {
    this.value.set(null);
    this.lastServerSyncAt = 0;
  }
}
export function safeReturnUrl(value: string | null): string {
  if (
    value === null ||
    value.includes('\\') ||
    value.includes('%') ||
    value.includes('?') ||
    value.includes('#')
  )
    return '/admin/dashboard';
  return /^\/admin\/(?:dashboard|map|personal|access|audit|zones|streets|spaces|parking-sessions|inspections|violations|payments|reports|tariffs(?:\/(?:billing-days|holidays|hours|history))?)$/.test(
    value,
  )
    ? value
    : '/admin/dashboard';
}
