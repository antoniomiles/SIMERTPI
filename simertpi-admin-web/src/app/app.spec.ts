import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { routes } from './app.routes';
import { AdminSession } from './core/auth/admin-session';
import { PORTAL_CONFIG } from './core/config/portal-config';
import { environment } from '../environments/environment';

describe('Administrative route boundary', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: PORTAL_CONFIG,
          useValue: { ...environment, administrativeApiEnabled: false, localPreview: false },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('redirects dashboard deep links to login without a session', async () => {
    const harness = await RouterTestingHarness.create('/admin/dashboard');
    expect(harness.routeNativeElement?.textContent).toContain('Bienvenido a SIMERTPI');
    expect(TestBed.inject(AdminSession).authenticated()).toBe(false);
  });
  it('redirects personnel deep links to login', async () => {
    const harness = await RouterTestingHarness.create('/admin/personal');
    expect(harness.routeNativeElement?.querySelector('#inspector-title')).toBeNull();
    expect(harness.routeNativeElement?.textContent).toContain('Bienvenido a SIMERTPI');
  });
  it('does not expose preview when not explicitly enabled', async () => {
    const harness = await RouterTestingHarness.create('/preview/personal');
    expect(harness.routeNativeElement?.textContent).toContain('Bienvenido a SIMERTPI');
    expect(harness.routeNativeElement?.textContent).not.toContain('Directorio municipal');
  });
  it('recovers unknown routes through login without a network request', async () => {
    const harness = await RouterTestingHarness.create('/nonexistent');
    expect(harness.routeNativeElement?.textContent).toContain('Bienvenido a SIMERTPI');
  });
});
