import { CanDeactivateFn } from '@angular/router';
import type { SystemEmail } from './system-email';

export const systemEmailUnsavedGuard: CanDeactivateFn<SystemEmail> = (component) =>
  component.canDeactivate();
