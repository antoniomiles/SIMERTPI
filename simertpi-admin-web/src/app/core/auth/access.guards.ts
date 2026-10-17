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
