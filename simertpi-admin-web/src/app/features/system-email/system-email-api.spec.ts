import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { SystemEmailApi } from './system-email-api';

describe('System email API', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: true } },
      ],
    }),
  );

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('loads active and draft summaries without a secret field', () => {
    TestBed.inject(SystemEmailApi)
      .get()
      .subscribe((result) => {
        expect(result.active?.credentialConfigured).toBe(true);
        expect(result.draft).toBeNull();
        expect(JSON.stringify(result)).not.toContain('password');
      });
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/system/email');
    expect(request.request.method).toBe('GET');
    request.flush({
      active: {
        id: 'cfg-1',
        version: 2,
        provider: 'GMAIL',
        displayName: 'SIMERTPI',
        from: 'mail@example.test',
        host: 'smtp.gmail.com',
        port: 587,
        security: 'STARTTLS',
        username: 'mail@example.test',
        credentialConfigured: true,
        state: 'ACTIVE',
        updatedAt: '2026-10-29T12:00:00Z',
      },
      draft: null,
      history: [],
      testRecipientConfigured: true,
    });
  });

  it('sends a replacement password only in a draft PUT and never exposes a test recipient parameter', () => {
    TestBed.inject(SystemEmailApi)
      .saveDraft({
        provider: 'GMAIL',
        displayName: 'SIMERTPI',
        from: 'mail@example.test',
        host: 'smtp.gmail.com',
        port: 587,
        security: 'STARTTLS',
        username: 'mail@example.test',
        password: 'one-time-app-secret',
        reason: 'Configuración inicial aprobada',
      })
      .subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/draft',
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.password).toBe('one-time-app-secret');
    request.flush({
      id: 'draft-3',
      version: 3,
      provider: 'GMAIL',
      displayName: 'SIMERTPI',
      from: 'mail@example.test',
      host: 'smtp.gmail.com',
      port: 587,
      security: 'STARTTLS',
      username: 'mail@example.test',
      credentialConfigured: true,
      state: 'DRAFT',
      createdAt: '2026-10-29T12:00:00Z',
      updatedAt: '2026-10-29T12:00:00Z',
      reason: 'Configuración inicial aprobada',
    });

    TestBed.inject(SystemEmailApi).testConnection(3).subscribe();
    const connection = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/test-connection',
    );
    expect(connection.request.method).toBe('POST');
    expect(connection.request.body).toEqual({ version: 3 });
    connection.flush({ status: 'SUCCESS', version: 3, updatedAt: '2026-10-29T12:00:01Z' });

    TestBed.inject(SystemEmailApi).sendTestEmail().subscribe();
    const test = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/test-email',
    );
    expect(test.request.method).toBe('POST');
    expect(test.request.body).toBeNull();
    expect(test.request.params.keys()).toEqual([]);
    test.flush({ status: 'SUCCESS', version: 3, updatedAt: '2026-10-29T12:00:02Z' });
  });

  it('requires a version and reason to activate and a reason to disable', () => {
    const api = TestBed.inject(SystemEmailApi);
    api.activate(4, 'Rotación aprobada').subscribe();
    const activate = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/activate',
    );
    expect(activate.request.body).toEqual({ version: 4, reason: 'Rotación aprobada' });
    activate.flush({ status: 'ACTIVATED', version: 4, updatedAt: '2026-10-29T12:00:00Z' });
    api.disable('Incidencia de proveedor').subscribe();
    const disable = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/disable',
    );
    expect(disable.request.body).toEqual({ reason: 'Incidencia de proveedor' });
    disable.flush({ status: 'DISABLED', version: 5, updatedAt: '2026-10-29T12:00:01Z' });
  });

  it('uses same-origin OAuth endpoints and exposes only public connection metadata', () => {
    const api = TestBed.inject(SystemEmailApi);
    api.microsoftOAuthStatus().subscribe((status) => {
      expect(status.authorizedAccount).toBe('simertpi@example.test');
      expect(JSON.stringify(status)).not.toMatch(
        /access.?token|refresh.?token|secret|code_verifier/i,
      );
    });
    const status = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/oauth/status',
    );
    expect(status.request.method).toBe('GET');
    status.flush({
      state: 'CONNECTED',
      authorizedAccount: 'simertpi@example.test',
      connectedAt: null,
      lastRefresh: null,
      expiresAt: null,
      errorCode: null,
      testPassed: false,
    });

    api.startMicrosoftOAuth().subscribe((response) => {
      expect(response.authorizationUrl).toContain('login.microsoftonline.com');
      expect(JSON.stringify(response)).not.toMatch(/access.?token|refresh.?token/i);
    });
    const start = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/oauth/start',
    );
    expect(start.request.method).toBe('POST');
    expect(start.request.body).toBeNull();
    start.flush({
      authorizationUrl:
        'https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?response_type=code&state=s&code_challenge=c&code_challenge_method=S256&scope=offline_access%20https%3A%2F%2Foutlook.office.com%2FSMTP.Send',
    });

    api.disconnectMicrosoftOAuth('rotación').subscribe();
    const disconnect = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/oauth/disconnect',
    );
    expect(disconnect.request.method).toBe('POST');
    expect(disconnect.request.body).toEqual({ reason: 'rotación' });
    disconnect.flush({ status: 'DISCONNECTED', version: 3, updatedAt: '2026-10-29T12:00:00Z' });
  });

  it('posts Microsoft test and activation operations without token fields', () => {
    const api = TestBed.inject(SystemEmailApi);
    api.testMicrosoftConnection().subscribe();
    const test = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/oauth/test-connection',
    );
    expect(test.request.method).toBe('POST');
    expect(test.request.body).toBeNull();
    test.flush({ status: 'SUCCESS', version: 1, updatedAt: '2026-10-29T12:00:00Z' });

    api.sendMicrosoftTestEmail().subscribe();
    const email = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/oauth/test-email',
    );
    expect(email.request.method).toBe('POST');
    expect(email.request.body).toBeNull();
    email.flush({ status: 'SUCCESS', version: 1, updatedAt: '2026-10-29T12:00:01Z' });

    api.activateMicrosoft('autorización verificada').subscribe();
    const activate = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/system/email/oauth/activate',
    );
    expect(activate.request.method).toBe('POST');
    expect(activate.request.body).toEqual({ reason: 'autorización verificada' });
    activate.flush({ status: 'ACTIVE', version: 2, updatedAt: '2026-10-29T12:00:02Z' });
  });
});
