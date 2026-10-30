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
});
