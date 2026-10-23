import { OperationToast } from './operation-toast';

describe('OperationToast', () => {
  it('keeps success or error visible for five seconds and can be dismissed manually', () => {
    vi.useFakeTimers();
    try {
      const toast = new OperationToast();
      toast.success('Guardado');
      expect(toast.current()).toMatchObject({ kind: 'success', message: 'Guardado' });
      vi.advanceTimersByTime(4999);
      expect(toast.current()?.message).toBe('Guardado');
      vi.advanceTimersByTime(1);
      expect(toast.current()).toBeNull();
      toast.error('Conflicto');
      toast.dismiss();
      expect(toast.current()).toBeNull();
    } finally { vi.useRealTimers(); }
  });

  it('does not let an older timeout dismiss a more recent notification', () => {
    vi.useFakeTimers();
    try {
      const toast = new OperationToast();
      toast.success('Primero');
      vi.advanceTimersByTime(4000);
      toast.error('Segundo');
      vi.advanceTimersByTime(1000);
      expect(toast.current()).toMatchObject({ kind: 'error', message: 'Segundo' });
      vi.advanceTimersByTime(4000);
      expect(toast.current()).toBeNull();
    } finally { vi.useRealTimers(); }
  });
});
