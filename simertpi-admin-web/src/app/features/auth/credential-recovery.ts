import { DOCUMENT, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { ApiClient } from '../../core/http/api-client';
import { Brand } from '../../shared/ui/brand';

@Component({
  imports: [Brand, ReactiveFormsModule, RouterLink],
  templateUrl: './credential-recovery.html',
})
export class CredentialRecovery {
  private readonly api = inject(ApiClient);
  private readonly document = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private readonly forms = inject(FormBuilder).nonNullable;
  readonly enabled = inject(PORTAL_CONFIG).administrativeApiEnabled;
  readonly mode: 'username' | 'password' | 'reset' = this.route.snapshot.data['recovery'];
  readonly title = {
    username: 'Recuperar usuario',
    password: 'Recuperar contraseña',
    reset: 'Establecer nueva contraseña',
  }[this.mode];
  readonly busy = signal(false);
  readonly confirmation = signal('');
  readonly error = signal('');
  readonly requestForm = this.forms.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
  });
  readonly passwordForm = this.forms.group({
    password: ['', Validators.required],
    confirm: ['', Validators.required],
  });
  private token = this.mode === 'reset' ? this.captureToken() : null;

  get canReset(): boolean {
    return this.token !== null;
  }

  requestRecovery() {
    if (!this.enabled || this.busy() || this.mode === 'reset') return;
    this.error.set('');
    this.requestForm.markAllAsTouched();
    if (this.requestForm.invalid) return;
    this.busy.set(true);
    this.api
      .post<void>(`admin/auth/recovery/${this.mode}`, this.requestForm.getRawValue())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () =>
          this.confirmation.set('Si el correo está asociado a una cuenta habilitada, recibirás un mensaje con las instrucciones.'),
        error: () => this.error.set('No se pudo procesar la solicitud. Inténtalo más tarde.'),
      });
  }

  resetPassword() {
    if (!this.enabled || this.busy() || !this.token) return;
    this.error.set('');
    const { password, confirm } = this.passwordForm.getRawValue();
    if (!password.trim() || Array.from(password).length < 12 || new TextEncoder().encode(password).length > 72) {
      this.error.set('Usa al menos 12 caracteres y como máximo 72 bytes.');
      return;
    }
    if (password !== confirm) {
      this.error.set('Las contraseñas deben coincidir.');
      return;
    }
    this.busy.set(true);
    this.api
      .post<void>('admin/auth/recovery/reset', { token: this.token, newPassword: password })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.token = null;
          this.passwordForm.reset();
          this.confirmation.set('Contraseña actualizada. Ya puedes iniciar sesión.');
        },
        error: () => this.error.set('No se pudo restablecer la contraseña. Intenta nuevamente o solicita otro enlace.'),
      });
  }

  private captureToken(): string | null {
    const view = this.document.defaultView;
    if (!view) return null;
    const candidate = new URLSearchParams(view.location.hash.slice(1)).get('token');
    if (view.location.hash) {
      view.history.replaceState(view.history.state, '', view.location.pathname + view.location.search);
    }
    return candidate && /^[A-Za-z0-9_-]{43}$/.test(candidate) ? candidate : null;
  }
}
