import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AdminSession } from '../../core/auth/admin-session';
import { SystemEmailApi } from './system-email-api';
import { isSafeMicrosoftAuthorizationUrl, SystemEmail } from './system-email';

describe('System email administration', () => {
  let fixture: ComponentFixture<SystemEmail>;
  const api = {
    get: vi.fn(),
    saveDraft: vi.fn(),
    testConnection: vi.fn(),
    sendTestEmail: vi.fn(),
    activate: vi.fn(),
    disable: vi.fn(),
    microsoftOAuthStatus: vi.fn(),
    startMicrosoftOAuth: vi.fn(),
    testMicrosoftConnection: vi.fn(),
    sendMicrosoftTestEmail: vi.fn(),
    activateMicrosoft: vi.fn(),
    disconnectMicrosoftOAuth: vi.fn(),
  };
  const identity = { userId: 'admin-1', username: 'root', roles: ['IT_ADMIN'], enabled: true };

  beforeEach(async () => {
    vi.clearAllMocks();
    api.get.mockReturnValue(
      of({ active: null, draft: null, history: [], testRecipientConfigured: false }),
    );
    api.microsoftOAuthStatus.mockReturnValue(
      of({
        state: 'NOT_CONFIGURED',
        authorizedAccount: null,
        connectedAt: null,
        lastRefresh: null,
        errorCode: null,
        expiresAt: null,
        testPassed: false,
      }),
    );
    api.saveDraft.mockReturnValue(
      of({ status: 'DRAFT_SAVED', version: 1, updatedAt: '2026-10-29T12:00:00Z' }),
    );
    api.testConnection.mockReturnValue(
      of({ status: 'OK', version: 1, updatedAt: '2026-10-29T12:00:00Z' }),
    );
    api.sendTestEmail.mockReturnValue(
      of({ status: 'ACCEPTED_BY_PROVIDER', version: 1, updatedAt: '2026-10-29T12:00:00Z' }),
    );
    api.activate.mockReturnValue(
      of({ status: 'ACTIVATED', version: 1, updatedAt: '2026-10-29T12:00:00Z' }),
    );
    api.disable.mockReturnValue(
      of({ status: 'DISABLED', version: 2, updatedAt: '2026-10-29T12:00:00Z' }),
    );
    await TestBed.configureTestingModule({
      imports: [SystemEmail],
      providers: [
        { provide: SystemEmailApi, useValue: api },
        { provide: AdminSession, useValue: { identity: () => identity } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(SystemEmail);
    fixture.detectChanges();
  });

  it('loads a summary for IT_ADMIN and leaves the secret field blank', () => {
    expect(api.get).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.form.controls.password.value).toBe('');
    expect(fixture.nativeElement.textContent).toContain('Correo electrónico');
    expect(fixture.nativeElement.querySelector('[type="password"]').value).toBe('');
  });

  it('does not query the API and displays a denial for non-IT_ADMIN users', async () => {
    api.get.mockClear();
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [SystemEmail],
      providers: [
        { provide: SystemEmailApi, useValue: api },
        {
          provide: AdminSession,
          useValue: { identity: () => ({ ...identity, roles: ['SIMERTPI_ADMIN'] }) },
        },
      ],
    }).compileComponents();
    const denied = TestBed.createComponent(SystemEmail);
    denied.detectChanges();
    expect(api.get).not.toHaveBeenCalled();
    expect(denied.nativeElement.textContent).toContain('Acceso restringido');
  });

  it('saves draft, clears the password in memory, and confirms only the server response', () => {
    const page = fixture.componentInstance;
    page.form.patchValue({
      displayName: 'SIMERTPI',
      from: 'mail@example.test',
      host: 'smtp.gmail.com',
      port: 587,
      username: 'mail@example.test',
      password: 'app-password',
      reason: 'Configuración SMTP inicial',
    });
    api.saveDraft.mockReturnValueOnce(
      of({ status: 'DRAFT_SAVED', version: 7, updatedAt: '2026-10-29T12:00:00Z' }),
    );
    page.saveDraft();
    expect(api.saveDraft).toHaveBeenCalledWith(
      expect.objectContaining({ password: 'app-password' }),
    );
    expect(page.form.controls.password.value).toBe('');
    expect(page.notice()?.text).toContain('versión 7');
  });

  it('keeps the form and reports backend errors without claiming success', () => {
    const page = fixture.componentInstance;
    page.form.patchValue({
      displayName: 'SIMERTPI',
      from: 'mail@example.test',
      host: 'smtp.gmail.com',
      port: 587,
      username: 'mail@example.test',
      password: 'app-password',
      reason: 'Configuración SMTP inicial',
    });
    api.saveDraft.mockReturnValueOnce(throwError(() => new Error('backend unavailable')));
    page.saveDraft();
    expect(page.form.controls.displayName.value).toBe('SIMERTPI');
    expect(page.notice()?.kind).toBe('error');
    expect(page.notice()?.kind).not.toBe('success');
  });

  it('does not let the UI send a test email to an arbitrary recipient', () => {
    const page = fixture.componentInstance;
    page.sendTestEmail();
    fixture.detectChanges();
    expect(api.sendTestEmail).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('input[type="email"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain(
      'El servidor aún no tiene configurado un destinatario de prueba.',
    );
  });

  it('only accepts a Microsoft authorization URL with HTTPS, state, code and PKCE S256', () => {
    expect(
      isSafeMicrosoftAuthorizationUrl(
        'https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?response_type=code&state=s&code_challenge=c&code_challenge_method=S256&scope=offline_access%20https%3A%2F%2Foutlook.office.com%2FSMTP.Send',
      ),
    ).toBe(true);
    expect(
      isSafeMicrosoftAuthorizationUrl(
        'https://evil.example/authorize?response_type=code&state=s&code_challenge=c&code_challenge_method=S256&scope=offline_access%20https%3A%2F%2Foutlook.office.com%2FSMTP.Send',
      ),
    ).toBe(false);
    expect(
      isSafeMicrosoftAuthorizationUrl(
        'http://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?response_type=code&state=s&code_challenge=c&code_challenge_method=S256&scope=offline_access%20https%3A%2F%2Foutlook.office.com%2FSMTP.Send',
      ),
    ).toBe(false);
    expect(
      isSafeMicrosoftAuthorizationUrl(
        'https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?response_type=code&state=s&code_challenge=c&code_challenge_method=plain&scope=offline_access%20https%3A%2F%2Foutlook.office.com%2FSMTP.Send',
      ),
    ).toBe(false);
  });

  it('does not start Microsoft authorization before saving an Outlook draft', () => {
    const page = fixture.componentInstance;
    page.startMicrosoftAuthorization();
    expect(api.startMicrosoftOAuth).not.toHaveBeenCalled();
    expect(page.notice()?.kind).toBe('error');
  });
});
