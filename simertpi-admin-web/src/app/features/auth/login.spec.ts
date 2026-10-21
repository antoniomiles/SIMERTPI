import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { provideRouter } from '@angular/router';
import { HttpXsrfTokenExtractor, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Login } from './login';
import { AdminSession } from '../../core/auth/admin-session';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { apiInterceptor } from '../../core/http/api.interceptor';
@Component({ standalone: true, template: '' })
class LoginTestDestination {}
describe('Login without an authentication backend', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: PORTAL_CONFIG,
          useValue: { ...environment, administrativeApiEnabled: false, localPreview: false },
        },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('validates required fields and rejects whitespace usernames', () => {
    const login = TestBed.createComponent(Login).componentInstance;
    login.submit();
    expect(login.form.invalid).toBe(true);
    expect(login.form.controls.username.touched).toBe(true);
    login.form.setValue({ username: '   ', password: 'form-validation-only' });
    expect(login.form.invalid).toBe(true);
  });
  it('never authenticates or transmits credentials and clears the password', () => {
    const login = TestBed.createComponent(Login).componentInstance;
    login.form.setValue({ username: 'local-form-review', password: 'form-validation-only' });
    login.submit();
    expect(login.message()).toContain('No se enviaron las credenciales');
    expect(login.form.controls.password.value).toBe('');
    expect(login.submitting()).toBe(false);
    expect(TestBed.inject(AdminSession).authenticated()).toBe(false);
    TestBed.inject(HttpTestingController).expectNone(() => true);
  });
  it('provides labels, password masking and generic errors without a preview shortcut', () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('label[for="password"]')).not.toBeNull();
    expect(element.querySelector<HTMLInputElement>('#password')?.type).toBe('password');
    expect(element.querySelector('a[href*="preview"]')).toBeNull();
  });
});

describe('Login request lifecycle', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([{ path: 'admin/dashboard', component: LoginTestDestination }]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'test-csrf-token' } },
        { provide: PORTAL_CONFIG, useValue: { ...environment, administrativeApiEnabled: true } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('does not duplicate CSRF or login requests while a login is pending', () => {
    const login = TestBed.createComponent(Login).componentInstance;
    const http = TestBed.inject(HttpTestingController);
    login.form.setValue({ username: 'fixture.admin', password: 'unit-test-only' });
    login.submit();
    http.expectOne('/api/v1/admin/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    const post = http.expectOne('/api/v1/admin/auth/login');
    expect(post.request.method).toBe('POST');
    expect(post.request.withCredentials).toBe(true);
    login.submit();
    http.expectNone('/api/v1/admin/auth/csrf');
    http.expectNone('/api/v1/admin/auth/login');
    post.flush({
      userId: 'fixture-id',
      username: 'fixture.admin',
      roles: ['SIMERTPI_ADMIN'],
      enabled: true,
      sessionExpiresAt: new Date(Date.now() + 60000).toISOString(),
      absoluteExpiresAt: new Date(Date.now() + 120000).toISOString(),
    });
    expect(login.form.controls.password.value).toBe('');
    expect(login.submitting()).toBe(false);
  });
});
