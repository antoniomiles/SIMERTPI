import { TestBed } from '@angular/core/testing';
import { AdminIdentity, AdminSessionState } from './admin-session-state';

function identity(roles: string[], enabled = true): AdminIdentity {
  return {
    userId: 'fixture-user',
    username: 'fixture.user',
    roles,
    enabled,
    sessionExpiresAt: '2026-10-29T00:00:00Z',
    absoluteExpiresAt: '2026-10-29T08:00:00Z',
  };
}

describe('AdminSessionState access identities', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('accepts enabled SIMERTPI_ADMIN and IT_ADMIN identities', () => {
    const state = TestBed.inject(AdminSessionState);
    state.accept(identity(['SIMERTPI_ADMIN']));
    expect(state.authenticated()).toBe(true);
    state.accept(identity(['IT_ADMIN']));
    expect(state.identity()?.roles).toEqual(['IT_ADMIN']);
  });

  it('continues to reject non-administrative and disabled identities', () => {
    const state = TestBed.inject(AdminSessionState);
    expect(() => state.accept(identity(['CITIZEN']))).toThrow('Administrative identity required');
    expect(() => state.accept(identity(['IT_ADMIN'], false))).toThrow('Administrative identity required');
    expect(state.authenticated()).toBe(false);
  });
});
