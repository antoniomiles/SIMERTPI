import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors, HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiClient } from './api-client';
import { apiInterceptor } from './api.interceptor';
import { normalizeError, PortalError } from './portal-error';
import { PORTAL_CONFIG } from '../config/portal-config';
import { environment } from '../../../environments/environment';
import { firstValueFrom } from 'rxjs';
describe('Fail-closed administrative transport', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: false } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('blocks requests to unimplemented APIs before they reach the network', async () => {
    await expect(
      firstValueFrom(TestBed.inject(ApiClient).get('admin/users')),
    ).rejects.toBeInstanceOf(PortalError);
    TestBed.inject(HttpTestingController).expectNone(() => true);
  });
  it('rejects external or citizen paths', () => {
    const api = TestBed.inject(ApiClient);
    for (const path of ['https://example.invalid', '../users', 'users', 'admin/../users'])
      expect(() => api.get(path)).toThrow();
  });
  it('never displays server response bodies or URLs', () => {
    const error = new HttpErrorResponse({
      status: 500,
      error: { message: 'private-server-detail' },
      url: 'https://private.invalid',
    });
    expect(normalizeError(error).message).not.toContain('private');
  });
  it('classifies authentication, authorization, network and timeout failures', () => {
    expect(normalizeError(new HttpErrorResponse({ status: 401 })).kind).toBe('unauthorized');
    expect(normalizeError(new HttpErrorResponse({ status: 403 })).kind).toBe('forbidden');
    expect(normalizeError(new HttpErrorResponse({ status: 0 })).kind).toBe('network');
    expect(normalizeError(Object.assign(new Error(), { name: 'TimeoutError' })).kind).toBe(
      'timeout',
    );
  });
});
