import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminSession } from '../../core/auth/admin-session';
import { normalizeError } from '../../core/http/portal-error';
import { PageHeading } from '../../shared/ui/page-heading';
import {
  EmailConfigurationHistory,
  EmailConfigurationMutationResponse,
  EmailDraftResponse,
  EmailConfigurationResponse,
  EmailDraftRequest,
  EmailProvider,
  EmailSecurity,
  SystemEmailApi,
} from './system-email-api';
import { Observable, finalize } from 'rxjs';

type ViewState = 'loading' | 'error' | 'ready';
type Operation = 'save' | 'connection' | 'test-email' | 'activate' | 'disable' | null;

@Component({
  selector: 'app-system-email',
  imports: [ReactiveFormsModule, DatePipe, PageHeading],
  templateUrl: './system-email.html',
  styleUrl: './system-email.scss',
})
export class SystemEmail implements OnInit {
  private readonly api = inject(SystemEmailApi);
  private readonly builder = inject(FormBuilder);
  readonly identity = inject(AdminSession).identity;
  readonly isSuperAdmin = () => this.identity()?.roles.includes('IT_ADMIN') ?? false;
  readonly state = signal<ViewState>('loading');
  readonly data = signal<EmailConfigurationResponse | null>(null);
  readonly operation = signal<Operation>(null);
  readonly error = signal('');
  readonly notice = signal<{ kind: 'success' | 'error'; text: string } | null>(null);
  readonly form = this.builder.nonNullable.group({
    provider: ['GMAIL' as EmailProvider, Validators.required],
    displayName: ['SIMERTPI — GAD Piñas', [Validators.required, Validators.maxLength(120)]],
    from: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    host: ['smtp.gmail.com', [Validators.required, Validators.maxLength(253)]],
    port: [587, [Validators.required, Validators.min(1), Validators.max(65535)]],
    security: ['STARTTLS' as EmailSecurity, Validators.required],
    username: ['', [Validators.required, Validators.maxLength(254)]],
    password: ['', [Validators.maxLength(512)]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });
  private noticeTimer: ReturnType<typeof setTimeout> | undefined;

  ngOnInit() {
    if (!this.isSuperAdmin()) {
      this.state.set('ready');
      return;
    }
    this.reload();
  }

  reload() {
    if (!this.isSuperAdmin()) return;
    this.state.set('loading');
    this.error.set('');
    this.api.get().subscribe({
      next: (result) => {
        this.data.set(result);
        const config = result.draft ?? result.active;
        if (config) {
          this.form.patchValue({
            provider: config.provider,
            displayName: config.displayName,
            from: config.from,
            host: config.host,
            port: config.port,
            security: config.security,
            username: config.username,
            password: '',
          });
        }
        this.state.set('ready');
      },
      error: (cause: unknown) => {
        this.error.set(normalizeError(cause).message);
        this.state.set('error');
      },
    });
  }

  useGmailDefaults() {
    this.form.patchValue({
      provider: 'GMAIL',
      host: 'smtp.gmail.com',
      port: 587,
      security: 'STARTTLS',
    });
  }

  saveDraft() {
    if (!this.ensureAuthorized()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) return;
    const value = this.form.getRawValue();
    const request: EmailDraftRequest = {
      provider: value.provider,
      displayName: value.displayName.trim(),
      from: value.from.trim(),
      host: value.host.trim(),
      port: Number(value.port),
      security: value.security,
      username: value.username.trim(),
      reason: value.reason.trim(),
      ...(value.password ? { password: value.password } : {}),
    };
    this.run(
      'save',
      () => this.api.saveDraft(request),
      (result) => {
        this.form.controls.password.setValue('');
        this.showNotice(
          'success',
          `Borrador de correo guardado correctamente (versión ${result.version}).`,
        );
        this.reloadAfterMutation();
      },
    );
  }

  testConnection() {
    if (!this.ensureAuthorized() || this.busy()) return;
    const version = this.data()?.draft?.version;
    if (!version) {
      this.showNotice('error', 'Guarde un borrador antes de probar la conexión.');
      return;
    }
    this.run(
      'connection',
      () => this.api.testConnection(version),
      (result) => {
        this.showNotice('success', `Conexión SMTP verificada para la versión ${result.version}.`);
        this.reloadAfterMutation();
      },
    );
  }

  sendTestEmail() {
    if (!this.ensureAuthorized() || this.busy()) return;
    if (!this.data()?.testRecipientConfigured) {
      this.showNotice('error', 'No hay un destinatario de prueba configurado en el servidor.');
      return;
    }
    this.run(
      'test-email',
      () => this.api.sendTestEmail(),
      (result) => {
        this.showNotice(
          'success',
          'El proveedor aceptó el correo de prueba. Esto no confirma su entrega al buzón.',
        );
        this.reloadAfterMutation();
      },
    );
  }

  activate() {
    if (!this.ensureAuthorized() || this.busy()) return;
    const draft = this.data()?.draft;
    const reason = this.form.controls.reason.value.trim();
    if (!draft) {
      this.showNotice('error', 'Guarde un borrador antes de activar una configuración.');
      return;
    }
    if (!reason) {
      this.form.controls.reason.markAsTouched();
      this.showNotice('error', 'Ingrese el motivo obligatorio para activar la configuración.');
      return;
    }
    this.run(
      'activate',
      () => this.api.activate(draft.version, reason),
      (result) => {
        this.form.controls.reason.setValue('');
        this.showNotice('success', `Configuración de correo activada (versión ${result.version}).`);
        this.reloadAfterMutation();
      },
    );
  }

  disable() {
    if (!this.ensureAuthorized() || this.busy()) return;
    const reason = this.form.controls.reason.value.trim();
    if (!reason) {
      this.form.controls.reason.markAsTouched();
      this.showNotice('error', 'Ingrese el motivo obligatorio para desactivar el correo.');
      return;
    }
    if (!globalThis.confirm('¿Desea desactivar la configuración SMTP activa?')) return;
    this.run(
      'disable',
      () => this.api.disable(reason),
      (result) => {
        this.form.controls.reason.setValue('');
        this.showNotice(
          'success',
          `Configuración SMTP desactivada${result.version === null ? '' : ` (versión ${result.version})`}.`,
        );
        this.reloadAfterMutation();
      },
    );
  }

  busy() {
    return this.operation() !== null;
  }
  actionLabel(action: Exclude<Operation, null>) {
    return this.operation() === action ? (action === 'save' ? 'Guardando…' : 'Procesando…') : '';
  }
  trackHistory(_index: number, item: EmailConfigurationHistory) {
    return item.id;
  }
  historyAction(action: string) {
    const labels: Record<string, string> = {
      DRAFT_SAVED: 'Borrador guardado',
      SMTP_DRAFT_SAVED: 'Borrador guardado',
      CONNECTION_TESTED: 'Conexión probada',
      SMTP_CONNECTION_TESTED: 'Conexión probada',
      TEST_EMAIL_SENT: 'Correo de prueba solicitado',
      SMTP_TEST_EMAIL_SENT: 'Correo de prueba solicitado',
      ACTIVATED: 'Configuración activada',
      SMTP_CONFIGURATION_ACTIVATED: 'Configuración activada',
      DISABLED: 'Configuración desactivada',
      SMTP_CONFIGURATION_DISABLED: 'Configuración desactivada',
      CREDENTIAL_REPLACED: 'Credencial reemplazada',
    };
    return labels[action] ?? 'Cambio de configuración';
  }
  private ensureAuthorized() {
    if (this.isSuperAdmin()) return true;
    this.showNotice('error', 'Solo el Superadministrador puede administrar el correo del sistema.');
    return false;
  }

  private run(
    operation: Exclude<Operation, null>,
    request: () => Observable<EmailConfigurationMutationResponse | EmailDraftResponse>,
    success: (result: EmailConfigurationMutationResponse | EmailDraftResponse) => void,
  ) {
    this.operation.set(operation);
    this.notice.set(null);
    request()
      .pipe(finalize(() => this.operation.set(null)))
      .subscribe({
        next: success,
        error: (cause: unknown) => this.showNotice('error', normalizeError(cause).message),
      });
  }

  private reloadAfterMutation() {
    // The confirmation is independent from the settings fetch and remains visible if it fails.
    this.operation.set(null);
    this.reload();
  }

  private showNotice(kind: 'success' | 'error', text: string) {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.notice.set({ kind, text });
    this.noticeTimer = setTimeout(() => this.notice.set(null), 5000);
  }
}
