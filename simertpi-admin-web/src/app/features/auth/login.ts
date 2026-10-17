import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AdminSession } from '../../core/auth/admin-session';
import { safeReturnUrl } from '../../core/auth/admin-session-state';
import { PORTAL_CONFIG, localPreviewAllowed } from '../../core/config/portal-config';
import { normalizeError } from '../../core/http/portal-error';
import { Brand } from '../../shared/ui/brand';
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink, Brand],
  templateUrl: './login.html',
})
export class Login {
  private readonly session = inject(AdminSession);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly enabled = inject(PORTAL_CONFIG).administrativeApiEnabled;
  readonly canPreview = localPreviewAllowed();
  readonly submitting = signal(false);
  readonly message = signal(
    this.route.snapshot.queryParamMap.get('reason') === 'expired'
      ? 'La sesión ha expirado. Inicie sesión nuevamente.'
      : '',
  );
  readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    password: ['', [Validators.required, Validators.maxLength(256)]],
  });
  skipToForm() {
    document.getElementById('username')?.focus();
  }
  submit() {
    if (this.submitting()) return;
    this.message.set('');
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.submitting.set(true);
    const { username, password } = this.form.getRawValue();
    this.session
      .signIn(username, password)
      .pipe(
        finalize(() => {
          this.submitting.set(false);
          this.form.controls.password.reset();
        }),
      )
      .subscribe({
        next: () =>
          void this.router.navigateByUrl(
            safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')),
          ),
        error: (error: unknown) => {
          const normalized = normalizeError(error);
          this.message.set(
            normalized.kind === 'unauthorized'
              ? 'Usuario o contraseña no válidos, o cuenta sin acceso administrativo.'
              : normalized.message,
          );
        },
      });
  }
}
