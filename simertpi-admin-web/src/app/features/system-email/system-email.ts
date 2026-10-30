import { DatePipe } from '@angular/common';
import { Component, HostListener, OnInit, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
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
  MicrosoftOAuthStatus,
  SystemEmailApi,
} from './system-email-api';
import { Observable, catchError, finalize, forkJoin, of } from 'rxjs';

type ViewState = 'loading' | 'error' | 'ready';
type EmailFormValue = {
  provider: EmailProvider;
  displayName: string;
  from: string;
  host: string;
  port: number;
  security: EmailSecurity;
  username: string;
  password: string;
  reason: string;
};

type Operation =
  | 'save'
  | 'connection'
  | 'test-email'
  | 'activate'
  | 'disable'
  | 'oauth-start'
  | 'oauth-disconnect'
  | null;

const NO_MICROSOFT_CONNECTION: MicrosoftOAuthStatus = {
  state: 'NOT_CONFIGURED',
  authorizedAccount: null,
  connectedAt: null,
  lastRefresh: null,
  expiresAt: null,
  errorCode: null,
  testPassed: false,
};

export function isSafeMicrosoftAuthorizationUrl(value: string): boolean {
  try {
    const url = new URL(value);
    const scopes = new Set((url.searchParams.get('scope') ?? '').split(/\s+/).filter(Boolean));
    return (
      url.protocol === 'https:' &&
      url.hostname === 'login.microsoftonline.com' &&
      /^\/(consumers|common)\/oauth2\/v2\.0\/authorize$/.test(url.pathname) &&
      url.searchParams.get('response_type') === 'code' &&
      url.searchParams.get('code_challenge_method') === 'S256' &&
      scopes.has('offline_access') &&
      scopes.has('https://outlook.office.com/SMTP.Send') &&
      Boolean(url.searchParams.get('state')) &&
      Boolean(url.searchParams.get('code_challenge'))
    );
  } catch {
    return false;
  }
}

@Component({
  selector: 'app-system-email',
  imports: [ReactiveFormsModule, DatePipe, PageHeading],
  templateUrl: './system-email.html',
  styleUrl: './system-email.scss',
})
export class SystemEmail implements OnInit {
  private readonly api = inject(SystemEmailApi);
  private readonly builder = inject(FormBuilder);
  private readonly document = inject(DOCUMENT);
  readonly identity = inject(AdminSession).identity;
  readonly isSuperAdmin = () => this.identity()?.roles.includes('IT_ADMIN') ?? false;
  readonly state = signal<ViewState>('loading');
  readonly data = signal<EmailConfigurationResponse | null>(null);
  readonly microsoftOAuth = signal<MicrosoftOAuthStatus | null>(null);
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
  private selectedProvider: EmailProvider = 'GMAIL';
  private baseline: EmailFormValue | null = null;

  ngOnInit() {
    if (!this.isSuperAdmin()) {
      this.state.set('ready');
      return;
    }
    this.consumeOAuthCallbackResult();
    this.reload();
  }

  reload() {
    if (!this.isSuperAdmin()) return;
    this.state.set('loading');
    this.error.set('');
    forkJoin({
      configuration: this.api.get(),
      microsoftOAuth: this.api.microsoftOAuthStatus().pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ configuration: result, microsoftOAuth }) => {
        this.data.set(result);
        this.microsoftOAuth.set(microsoftOAuth);
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
            reason: '',
          });
        } else {
          this.form.reset({
            provider: 'GMAIL',
            displayName: 'SIMERTPI \u2014 GAD Pi\u00f1as',
            from: '',
            host: 'smtp.gmail.com',
            port: 587,
            security: 'STARTTLS',
            username: '',
            password: '',
            reason: '',
          });
        }
        this.selectedProvider = this.form.controls.provider.value;
        this.form.markAsPristine();
        this.form.markAsUntouched();
        this.baseline = this.form.getRawValue();
        this.state.set('ready');
      },
      error: (cause: unknown) => {
        this.error.set(normalizeError(cause).message);
        this.state.set('error');
      },
    });
  }

  providerChanged(provider: EmailProvider) {
    const previous = this.selectedProvider;
    if (provider === previous) return;
    const dirtyBeforeSwitch = this.form.dirty && this.hasUnsavedChangesExceptProvider();
    if (
      this.form.dirty &&
      !globalThis.confirm(
        'Cambiar el proveedor ajustar\u00e1 los datos de conexi\u00f3n. \u00bfDesea conservar este cambio?',
      )
    ) {
      this.form.controls.provider.setValue(previous, { emitEvent: false });
      if (!dirtyBeforeSwitch) this.form.markAsPristine();
      return;
    }
    this.selectedProvider = provider;
    this.form.controls.password.setValue('');
    if (provider === 'GMAIL') {
      this.form.patchValue({ host: 'smtp.gmail.com', port: 587, security: 'STARTTLS' });
    } else if (provider === 'MICROSOFT_OUTLOOK') {
      this.form.patchValue({ host: 'smtp-mail.outlook.com', port: 587, security: 'STARTTLS' });
    } else {
      this.form.patchValue({ host: '', port: 587, security: 'STARTTLS' });
    }
  }

  hasMatchingCredential() {
    const source = this.data()?.draft ?? this.data()?.active;
    const value = this.form.getRawValue();
    return Boolean(
      source?.credentialConfigured &&
      source.provider === value.provider &&
      source.host.toLowerCase() === value.host.trim().toLowerCase() &&
      source.username.toLowerCase() === value.username.trim().toLowerCase(),
    );
  }

  private hasUnsavedChangesExceptProvider() {
    if (!this.baseline) return this.form.dirty;
    const current = this.form.getRawValue();
    return (Object.keys(current) as Array<keyof EmailFormValue>)
      .filter((key) => key !== 'provider')
      .some((key) => current[key] !== this.baseline![key]);
  }

  hasUnsavedChanges() {
    return this.form.dirty;
  }

  discardChanges(confirm = true) {
    if (!this.form.dirty) return true;
    if (
      confirm &&
      !globalThis.confirm('\u00bfDescartar los cambios de correo que a\u00fan no se han guardado?')
    )
      return false;
    if (this.baseline) this.form.reset({ ...this.baseline, password: '', reason: '' });
    this.selectedProvider = this.form.controls.provider.value;
    this.form.markAsPristine();
    this.form.markAsUntouched();
    this.form.controls.password.setErrors(null);
    this.notice.set(null);
    return true;
  }

  canDeactivate() {
    return !this.hasUnsavedChanges() || this.discardChanges(true);
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent) {
    if (!this.hasUnsavedChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  startMicrosoftAuthorization() {
    if (!this.ensureAuthorized() || this.busy()) return;
    if (this.form.dirty) {
      this.showNotice(
        'error',
        'Guarde o descarte los cambios antes de iniciar la autorizaci\u00f3n Microsoft.',
      );
      return;
    }
    const draft = this.data()?.draft;
    if (!draft || draft.provider !== 'MICROSOFT_OUTLOOK') {
      this.showNotice('error', 'Guarde primero un borrador de Outlook OAuth2.');
      return;
    }
    this.operation.set('oauth-start');
    this.notice.set(null);
    this.api
      .startMicrosoftOAuth()
      .pipe(finalize(() => this.operation.set(null)))
      .subscribe({
        next: ({ authorizationUrl }) => {
          if (!isSafeMicrosoftAuthorizationUrl(authorizationUrl)) {
            this.showNotice(
              'error',
              'El servidor devolvió una dirección de autorización no válida.',
            );
            return;
          }
          this.document.defaultView?.location.assign(authorizationUrl);
        },
        error: (cause: unknown) => this.showNotice('error', normalizeError(cause).message),
      });
  }

  disconnectMicrosoft() {
    if (!this.ensureAuthorized() || this.busy()) return;
    if (!globalThis.confirm('¿Desea desconectar la cuenta Microsoft institucional?')) return;
    this.run(
      'oauth-disconnect',
      () => this.api.disconnectMicrosoftOAuth('Desconexión autorizada'),
      () => {
        this.showNotice('success', 'La cuenta Microsoft fue desconectada.');
        this.reloadAfterMutation();
      },
    );
  }

  private consumeOAuthCallbackResult() {
    const windowRef = this.document.defaultView;
    if (!windowRef) return;
    const current = new URL(windowRef.location.href);
    const outcome = current.searchParams.get('oauth');
    if (!outcome) return;
    // Callback redirects carry only a fixed outcome enum. Strip the entire query immediately;
    // no authorization code, state, access token, or refresh token is accepted here.
    windowRef.history.replaceState({}, this.document.title, current.pathname);
    const messages: Record<string, string> = {
      connected: 'La cuenta Microsoft fue autorizada. Actualizando el estado de conexión.',
      reauth_required: 'Microsoft requiere una nueva autorización para continuar.',
      failed:
        'No se pudo completar la autorización Microsoft. Revise la configuración e intente nuevamente.',
    };
    if (messages[outcome])
      this.showNotice(outcome === 'failed' ? 'error' : 'success', messages[outcome]);
  }

  saveDraft() {
    if (!this.ensureAuthorized()) return;
    this.form.markAllAsTouched();
    const passwordControl = this.form.controls.password;
    const value = this.form.getRawValue();
    if (
      value.provider !== 'MICROSOFT_OUTLOOK' &&
      !value.password &&
      !this.hasMatchingCredential()
    ) {
      passwordControl.setErrors({ ...(passwordControl.errors ?? {}), required: true });
    }
    if (this.form.invalid || this.busy()) return;
    const current = this.form.getRawValue();
    const request: EmailDraftRequest = {
      provider: current.provider,
      displayName: current.displayName.trim(),
      from: current.from.trim(),
      host: current.host.trim(),
      port: Number(current.port),
      security: current.security,
      username: current.username.trim(),
      reason: current.reason.trim(),
      ...(current.provider !== 'MICROSOFT_OUTLOOK' && current.password
        ? { password: current.password }
        : {}),
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
    if (
      this.data()?.draft?.provider === 'MICROSOFT_OUTLOOK' &&
      !['CONNECTED', 'ACTIVE'].includes(this.microsoftOAuth()?.state ?? '')
    ) {
      this.showNotice('error', 'Autorice la cuenta Microsoft antes de probar la conexión.');
      return;
    }
    this.run(
      'connection',
      () =>
        this.data()?.draft?.provider === 'MICROSOFT_OUTLOOK'
          ? this.api.testMicrosoftConnection()
          : this.api.testConnection(version),
      (result) => {
        this.showNotice('success', `Conexión SMTP verificada para la versión ${result.version}.`);
        this.reloadAfterMutation();
      },
    );
  }

  canTestMicrosoft() {
    return ['CONNECTED', 'ACTIVE'].includes(this.microsoftOAuth()?.state ?? '');
  }

  canActivate() {
    const draft = this.data()?.draft;
    if (!draft) return false;
    return (
      draft.provider !== 'MICROSOFT_OUTLOOK' ||
      (this.canTestMicrosoft() && this.microsoftOAuth()?.testPassed === true)
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
      () =>
        this.data()?.draft?.provider === 'MICROSOFT_OUTLOOK'
          ? this.api.sendMicrosoftTestEmail()
          : this.api.sendTestEmail(),
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
    if (draft.provider === 'MICROSOFT_OUTLOOK' && !this.canActivate()) {
      this.showNotice('error', 'Autorice la cuenta Microsoft antes de activar esta configuración.');
      return;
    }
    if (!reason) {
      this.form.controls.reason.markAsTouched();
      this.showNotice('error', 'Ingrese el motivo obligatorio para activar la configuración.');
      return;
    }
    this.run(
      'activate',
      () =>
        draft.provider === 'MICROSOFT_OUTLOOK'
          ? this.api.activateMicrosoft(reason)
          : this.api.activate(draft.version, reason),
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
  providerLabel(provider: EmailProvider | null) {
    if (provider === 'GMAIL') return 'Gmail';
    if (provider === 'MICROSOFT_OUTLOOK') return 'Microsoft Outlook OAuth2';
    return 'SMTP personalizado';
  }
  oauthStateLabel(state: MicrosoftOAuthStatus['state']) {
    const labels: Record<MicrosoftOAuthStatus['state'], string> = {
      NOT_CONFIGURED: 'No configurado',
      PENDING_AUTHORIZATION: 'Pendiente de autorización',
      CONNECTED: 'Conectado',
      ACTIVE: 'Activo',
      REAUTH_REQUIRED: 'Requiere nueva autorización',
      ERROR: 'Error',
      DISABLED: 'Desactivado',
      DISCONNECTED: 'Desconectado',
    };
    return labels[state] ?? 'Estado desconocido';
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
