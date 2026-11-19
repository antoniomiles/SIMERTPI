import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { ApiClient } from '../../core/http/api-client';
import { CredentialRecovery } from './credential-recovery';

describe('Administrative credential recovery', () => {
  const api = { post: vi.fn() };
  const token = 'A'.repeat(43);

  afterEach(() => window.history.replaceState(null, '', '/'));

  async function create(recovery: 'username' | 'password' | 'reset') {
    api.post.mockReset().mockReturnValue(of(undefined));
    await TestBed.configureTestingModule({
      imports: [CredentialRecovery],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { data: { recovery } } } },
        { provide: PORTAL_CONFIG, useValue: { administrativeApiEnabled: true } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(CredentialRecovery);
    fixture.detectChanges();
    return fixture;
  }

  it.each(['username', 'password'] as const)('submits the %s request with generic confirmation', async (mode) => {
    const fixture = await create(mode);
    const page = fixture.componentInstance;
    page.requestForm.setValue({ email: 'persona@example.invalid' });
    page.requestRecovery();
    expect(api.post).toHaveBeenCalledWith(`admin/auth/recovery/${mode}`, { email: 'persona@example.invalid' });
    expect(page.confirmation()).toContain('Si el correo está asociado');
    expect(page.confirmation()).not.toContain('persona@example.invalid');
  });

  it('removes the token from the address and sends it only with matching new passwords', async () => {
    window.history.replaceState(null, '', `/restablecer-contrasena#token=${token}`);
    const fixture = await create('reset');
    const page = fixture.componentInstance;
    expect(window.location.hash).toBe('');
    page.passwordForm.setValue({ password: 'Clave-de-prueba-2026', confirm: 'otra' });
    page.resetPassword();
    expect(api.post).not.toHaveBeenCalled();
    page.passwordForm.controls.confirm.setValue('Clave-de-prueba-2026');
    page.resetPassword();
    expect(api.post).toHaveBeenCalledWith('admin/auth/recovery/reset', { token, newPassword: 'Clave-de-prueba-2026' });
    expect(page.canReset).toBe(false);
    expect(page.passwordForm.controls.password.value).toBe('');
  });
});
