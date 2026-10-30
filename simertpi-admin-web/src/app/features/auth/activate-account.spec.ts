import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ActivationApi } from './activation-api';
import { ActivateAccount } from './activate-account';

describe('Public account activation', () => {
  const token = 'A'.repeat(43);
  const api = {
    validate: vi.fn().mockReturnValue(of({ status: 'VALID', expiresAt: '2026-10-30T00:00:00Z' })),
    activate: vi.fn().mockReturnValue(of(void 0)),
  };

  beforeEach(async () => {
    api.validate.mockClear();
    api.activate.mockClear();
    window.history.replaceState(null, '', `/activate-account#token=${token}`);
    await TestBed.configureTestingModule({ imports: [ActivateAccount], providers: [provideRouter([]), { provide: ActivationApi, useValue: api }] }).compileComponents();
  });

  it('reads the one-use token from the fragment, removes it from browser history, and activates without persistent storage', () => {
    const fixture = TestBed.createComponent(ActivateAccount);
    fixture.detectChanges();
    expect(api.validate).toHaveBeenCalledWith(token);
    expect(window.location.hash).toBe('');
    expect(localStorage.getItem('activationToken')).toBeNull();
    const page = fixture.componentInstance;
    page.form.setValue({ password: 'Long-safe-password-2026', confirmation: 'Long-safe-password-2026' });
    page.submit();
    expect(api.activate).toHaveBeenCalledWith(token, 'Long-safe-password-2026');
    expect(page.completed()).toBe(true);
    expect(page.form.controls.password.value).toBe('');
  });

  it('never submits an expired token or permits mismatched passwords', () => {
    api.validate.mockReturnValueOnce(of({ status: 'EXPIRED', expiresAt: null }));
    const fixture = TestBed.createComponent(ActivateAccount);
    fixture.detectChanges();
    fixture.componentInstance.submit();
    expect(api.activate).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('venció');
  });
});
