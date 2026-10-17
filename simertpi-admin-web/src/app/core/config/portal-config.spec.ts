import { permitsLocalPreview } from './portal-config';
describe('Explicit local preview policy', () => {
  it('allows loopback only with development and preview flags', () => {
    for (const host of ['localhost', '127.0.0.1', '[::1]', '::1'])
      expect(permitsLocalPreview(true, true, host)).toBe(true);
  });
  it('rejects public, LAN and misleading loopback names', () => {
    for (const host of [
      'simertpi-backend-dev.onrender.com',
      '192.168.1.10',
      'localhost.example.com',
      '127.0.0.1.example.com',
      '',
    ])
      expect(permitsLocalPreview(true, true, host)).toBe(false);
  });
  it('cannot be enabled in a production build or by hostname alone', () => {
    expect(permitsLocalPreview(true, false, 'localhost')).toBe(false);
    expect(permitsLocalPreview(false, true, 'localhost')).toBe(false);
  });
});
