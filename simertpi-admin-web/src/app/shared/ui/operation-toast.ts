import { Injectable, signal } from '@angular/core';

export interface OperationToastMessage { id: number; kind: 'success' | 'error'; message: string; }

@Injectable({ providedIn: 'root' })
export class OperationToast {
  readonly current = signal<OperationToastMessage | null>(null);
  private sequence = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;

  success(message: string) { this.show('success', message); }
  error(message: string) { this.show('error', message); }

  dismiss(id?: number) {
    if (id !== undefined && this.current()?.id !== id) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.current.set(null);
  }

  private show(kind: OperationToastMessage['kind'], message: string) {
    if (this.timer) clearTimeout(this.timer);
    const id = ++this.sequence;
    this.current.set({ id, kind, message });
    this.timer = setTimeout(() => this.dismiss(id), 5000);
  }
}
