import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Login } from './login';
import { AdminSession } from '../../core/auth/admin-session';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
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
