import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { Brand } from '../../shared/ui/brand';
import { ActivationApi, ActivationStatus, ActivationTokenCapture } from './activation-api';

@Component({
  selector: 'app-activate-account',
  imports: [ReactiveFormsModule, RouterLink, Brand],
  template: `
    <main class="activation-page">
      <app-brand />
      <section class="activation-card" aria-labelledby="activation-title">
        <span class="eyebrow">ACTIVACIÓN DE CUENTA</span>
        <h1 id="activation-title">{{ completed() ? 'Cuenta activada' : 'Establece tu contraseña' }}</h1>
        @if (completed()) {
          <p role="status">La cuenta se activó correctamente. Ya puedes iniciar sesión.</p>
          <a class="button primary" routerLink="/login">Ir al inicio de sesión</a>
        } @else if (status() !== 'VALID') {
          <p role="alert">{{ statusMessage() }}</p>
          <a routerLink="/login">Volver al inicio de sesión</a>
        } @else {
          <p>El enlace es personal y de un solo uso. La contraseña debe tener al menos 12 caracteres.</p>
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <label for="new-password">Nueva contraseña</label>
            <input id="new-password" type="password" formControlName="password" autocomplete="new-password" minlength="12" maxlength="72" />
            <label for="confirm-password">Confirmar contraseña</label>
            <input id="confirm-password" type="password" formControlName="confirmation" autocomplete="new-password" minlength="12" maxlength="72" />
            @if (form.touched && form.invalid) { <p class="field-error" role="alert">Use entre 12 y 72 caracteres para ambos campos.</p> }
            @if (form.controls.confirmation.value && form.controls.password.value !== form.controls.confirmation.value) { <p class="field-error" role="alert">Las contraseñas no coinciden.</p> }
            @if (error()) { <p class="field-error" role="alert">{{ error() }}</p> }
            <button class="button primary" type="submit" [disabled]="busy() || form.invalid || form.controls.password.value !== form.controls.confirmation.value">{{ busy() ? 'Activando…' : 'Activar cuenta' }}</button>
          </form>
        }
      </section>
    </main>
  `,
  styles: [`
    :host { display:block; min-height:100vh; background:#f4f7f4; color:#24382b; font-family:inherit }
    .activation-page { max-width:34rem; margin:0 auto; padding:clamp(1rem,5vw,3rem); }
    .activation-card { margin-top:2rem; padding:clamp(1rem,4vw,2rem); border:1px solid #d9e5dc; border-radius:.8rem; background:#fff; box-shadow:0 12px 32px #173b2412; }
    .eyebrow { color:#487455; font-size:.72rem; font-weight:800; letter-spacing:.08em; }
    h1 { margin:.5rem 0; font-size:1.55rem; }
    form { display:grid; gap:.65rem; margin-top:1.2rem; }
    label { font-size:.85rem; font-weight:700; }
    input { min-height:2.7rem; padding:.55rem .7rem; border:1px solid #becfc2; border-radius:.4rem; font:inherit; }
    .button { display:inline-flex; justify-content:center; align-items:center; min-height:2.5rem; padding:.55rem .9rem; border:0; border-radius:.4rem; font:inherit; font-weight:700; text-decoration:none; cursor:pointer; }
    .primary { margin-top:.5rem; background:#25723d; color:#fff; }
    button:disabled { opacity:.55; cursor:not-allowed; }
    .field-error { color:#9e3030; }
  `],
})
export class ActivateAccount implements OnInit {
  private readonly api = inject(ActivationApi);
  private readonly activationTokenCapture = inject(ActivationTokenCapture);
  readonly status = signal<ActivationStatus | 'CHECKING'>('CHECKING');
  readonly expiresAt = signal<string | null>(null);
  readonly completed = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  private activationToken: string | null = null;
  readonly form = inject(FormBuilder).nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(72)]],
    confirmation: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(72)]],
  });

  ngOnInit() {
    const token = this.activationTokenCapture.consume();
    if (!token) { this.status.set('INVALID'); return; }
    this.activationToken = token;
    this.api.validate(token).subscribe({
      next: result => { this.status.set(result.status); this.expiresAt.set(result.expiresAt); if (result.status !== 'VALID') this.activationToken = null; },
      error: error => { this.status.set('INVALID'); this.activationToken = null; this.error.set(normalizeError(error).message); },
    });
  }

  statusMessage() {
    switch (this.status()) {
      case 'EXPIRED': return 'El enlace de activación venció. Solicita una nueva invitación al administrador.';
      case 'RATE_LIMITED': return 'Se alcanzó el límite de intentos. Espera antes de volver a intentarlo.';
      case 'INVALID': return 'El enlace de activación no es válido o ya fue utilizado.';
      default: return this.status() === 'CHECKING' ? 'Validando el enlace de activación…' : 'No se pudo validar el enlace.';
    }
  }

  submit() {
    this.form.markAllAsTouched();
    if (this.busy() || this.form.invalid || this.status() !== 'VALID' || !this.activationToken) return;
    const token = this.activationToken;
    const password = this.form.controls.password.value;
    if (password !== this.form.controls.confirmation.value) { this.error.set('Las contraseñas no coinciden.'); return; }
    this.busy.set(true); this.error.set('');
    this.api.activate(token, password).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => { this.activationToken = null; this.form.reset(); this.completed.set(true); },
      error: error => { this.error.set(normalizeError(error).message || 'No se pudo activar la cuenta. Solicita una nueva invitación si el enlace venció.'); },
    });
  }
}
