import { TestBed } from '@angular/core/testing';
import {
  SidebarPreference,
  SIDEBAR_PREFERENCE_KEY,
  VISUAL_PREFERENCE_STORAGE,
} from './sidebar-preference';
describe('Non-sensitive sidebar preference', () => {
  function create(storage: Pick<Storage, 'getItem' | 'setItem'> | null) {
    TestBed.configureTestingModule({
      providers: [{ provide: VISUAL_PREFERENCE_STORAGE, useValue: storage }],
    });
    return TestBed.inject(SidebarPreference);
  }
  it('defaults to expanded when unavailable or unknown', () => {
    const preference = create(null);
    expect(preference.collapsed()).toBe(false);
    preference.toggle();
    expect(preference.collapsed()).toBe(true);
  });
  it('reads only its own visual enum and persists no identity data', () => {
    const getItem = vi.fn(() => 'collapsed');
    const setItem = vi.fn();
    const preference = create({ getItem, setItem });
    expect(preference.collapsed()).toBe(true);
    expect(getItem).toHaveBeenCalledExactlyOnceWith(SIDEBAR_PREFERENCE_KEY);
    preference.toggle();
    expect(setItem).toHaveBeenLastCalledWith(SIDEBAR_PREFERENCE_KEY, 'expanded');
    preference.toggle();
    expect(setItem).toHaveBeenLastCalledWith(SIDEBAR_PREFERENCE_KEY, 'collapsed');
  });
  it('ignores unrecognized stored values', () => {
    expect(create({ getItem: () => 'unexpected', setItem: () => {} }).collapsed()).toBe(false);
  });
  it('continues working if storage access is denied', () => {
    const preference = create({
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
    });
    expect(preference.collapsed()).toBe(false);
    expect(() => preference.toggle()).not.toThrow();
    expect(preference.collapsed()).toBe(true);
  });
});
