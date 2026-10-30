import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../../core/config/portal-config';

export type EmailProvider = 'GMAIL' | 'CUSTOM' | 'MICROSOFT_OUTLOOK';
export type EmailSecurity = 'STARTTLS' | 'SMTPS';

export type MicrosoftOAuthState =
  | 'NOT_CONFIGURED'
  | 'PENDING_AUTHORIZATION'
  | 'CONNECTED'
  | 'ACTIVE'
  | 'REAUTH_REQUIRED'
  | 'ERROR'
  | 'DISABLED'
  | 'DISCONNECTED';

/** Public connection metadata only. OAuth tokens must never cross the API boundary. */
export interface MicrosoftOAuthStatus {
  state: MicrosoftOAuthState;
  authorizedAccount: string | null;
  connectedAt: string | null;
  lastRefresh: string | null;
  expiresAt?: string | null;
  errorCode: string | null;
  testPassed?: boolean;
}

export interface MicrosoftOAuthStartResponse {
  authorizationUrl: string;
}

export interface EmailConfigurationSummary {
  id: string;
  version: number;
  provider: EmailProvider;
  displayName: string;
  from: string;
  host: string;
  port: number;
  security: EmailSecurity;
  username: string;
  credentialConfigured: boolean;
  state: string;
  createdAt?: string;
  createdBy?: string | null;
  updatedAt: string;
  updatedBy?: string | null;
  reason?: string | null;
  activatedAt?: string | null;
}

export interface EmailConfigurationHistory {
  id: string;
  version: number;
  action: string;
  actor: string | null;
  occurredAt: string;
  result: string;
  reason: string | null;
  provider: EmailProvider | null;
  host: string | null;
  from: string | null;
}

export interface EmailConfigurationResponse {
  active: EmailConfigurationSummary | null;
  draft: EmailConfigurationSummary | null;
  history: EmailConfigurationHistory[];
  testRecipientConfigured: boolean;
}

export interface EmailDraftRequest {
  provider: EmailProvider;
  displayName: string;
  from: string;
  host: string;
  port: number;
  security: EmailSecurity;
  username: string;
  /** Optional replacement only. It is never populated from a server response. */
  password?: string;
  reason: string;
}

/** PUT /draft returns the persisted configuration view, not an operation envelope. */
export type EmailDraftResponse = EmailConfigurationSummary;

export interface EmailConfigurationMutationResponse {
  status: string;
  version: number | null;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class SystemEmailApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(PORTAL_CONFIG);
  private readonly endpoint = `${this.config.apiBaseUrl.replace(/\/$/, '')}/admin/system/email`;

  get() {
    return this.http.get<EmailConfigurationResponse>(this.endpoint);
  }

  saveDraft(request: EmailDraftRequest) {
    return this.http.put<EmailDraftResponse>(`${this.endpoint}/draft`, request);
  }

  testConnection(version: number) {
    return this.http.post<EmailConfigurationMutationResponse>(`${this.endpoint}/test-connection`, {
      version,
    });
  }

  sendTestEmail() {
    return this.http.post<EmailConfigurationMutationResponse>(`${this.endpoint}/test-email`, null);
  }

  activate(version: number, reason: string) {
    return this.http.post<EmailConfigurationMutationResponse>(`${this.endpoint}/activate`, {
      version,
      reason,
    });
  }

  disable(reason: string) {
    return this.http.post<EmailConfigurationMutationResponse>(`${this.endpoint}/disable`, {
      reason,
    });
  }

  microsoftOAuthStatus() {
    return this.http.get<MicrosoftOAuthStatus>(`${this.endpoint}/oauth/status`);
  }

  startMicrosoftOAuth() {
    return this.http.post<MicrosoftOAuthStartResponse>(`${this.endpoint}/oauth/start`, null);
  }

  testMicrosoftConnection() {
    return this.http.post<EmailConfigurationMutationResponse>(
      `${this.endpoint}/oauth/test-connection`,
      null,
    );
  }

  sendMicrosoftTestEmail() {
    return this.http.post<EmailConfigurationMutationResponse>(
      `${this.endpoint}/oauth/test-email`,
      null,
    );
  }

  activateMicrosoft(reason: string) {
    return this.http.post<EmailConfigurationMutationResponse>(`${this.endpoint}/oauth/activate`, {
      reason,
    });
  }

  disconnectMicrosoftOAuth(reason: string) {
    return this.http.post<EmailConfigurationMutationResponse>(`${this.endpoint}/oauth/disconnect`, {
      reason,
    });
  }
}
