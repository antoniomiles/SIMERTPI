import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AdminSession } from './admin-session';
import { safeReturnUrl } from './admin-session-state';
import { localPreviewAllowed } from '../config/portal-config';
export const adminGuard: CanActivateFn = (_route, state) => {
  const session = inject(AdminSession),
    router = inject(Router);
  return session
    .restore()
    .pipe(
      map(
        (ok) =>
          ok ||
          router.createUrlTree(['/login'], {
            queryParams: { returnUrl: safeReturnUrl(state.url) },
          }),
      ),
    );
};
export const previewGuard: CanMatchFn = () =>
  localPreviewAllowed() || inject(Router).createUrlTree(['/login']);

/** Configuration SMTP is restricted to the protected system role, irrespective of claims. */
export const superadminGuard: CanActivateFn = () => {
  const identity = inject(AdminSession).identity();
  return identity?.enabled && identity.roles.includes('IT_ADMIN')
    ? true
    : inject(Router).createUrlTree(['/admin/dashboard']);
};

export const permissionGuard: CanActivateFn = (route) => {
  const session = inject(AdminSession);
  const router = inject(Router);
  const permission = route.data['permission'] as string | undefined;
  if (!permission) return true;
  if (route.pathFromRoot.some((item) => item.data['preview'] === true)) return true;
  const identity = session.identity();
  if (identity?.enabled && identity.roles.includes('IT_ADMIN')) return true;
  if (identity?.permissions !== undefined)
    return identity.permissions.includes(permission) || router.createUrlTree(['/admin/dashboard']);
  // Compatibility for V50 sessions without dynamic permission claims.
  return identity?.roles.some((role) => role === 'IT_ADMIN' || role === 'SIMERTPI_ADMIN')
    ? true
    : router.createUrlTree(['/admin/dashboard']);
};
