import { Component, ElementRef, effect, inject, viewChild } from '@angular/core';
import { OperationToast } from './operation-toast';

@Component({
  selector: 'app-operation-toast-outlet',
  template: `
    <div #toastPopover popover="manual" class="operation-toast" [class.toast-success]="toast.current()?.kind === 'success'"
      [class.toast-error]="toast.current()?.kind === 'error'" [attr.role]="toast.current()?.kind === 'error' ? 'alert' : 'status'"
      [attr.aria-live]="toast.current()?.kind === 'error' ? 'assertive' : 'polite'" aria-atomic="true">
      @if (toast.current(); as message) {
        <div class="toast-content"><span class="toast-indicator" aria-hidden="true">{{ message.kind === 'success' ? '✓' : '!' }}</span>
          <p>{{ message.message }}</p><button type="button" class="toast-close" aria-label="Cerrar notificación" (click)="dismiss()">×</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .operation-toast { position: fixed; inset: 18px 18px auto auto; z-index: 2147483000; margin: 0; padding: 0; width: min(440px, calc(100vw - 36px)); max-width: none; border: 0; background: transparent; color: inherit; overflow: visible; }
    .toast-content { display: grid; grid-template-columns: 28px minmax(0, 1fr) 28px; align-items: center; gap: 10px; padding: 14px 15px; border: 1px solid #b9d8c6; border-left: 4px solid #368459; border-radius: 12px; background: #fff; color: #193326; box-shadow: 0 14px 40px #14271e30; font: 500 14px/1.45 system-ui, sans-serif; }
    .toast-error .toast-content { border-color: #e4b9b6; border-left-color: #a6423b; background: #fffafa; color: #4f2421; }
    .toast-indicator { display: grid; place-items: center; width: 25px; height: 25px; border-radius: 50%; background: #e8f4ec; color: #286644; font-weight: 800; }
    .toast-error .toast-indicator { background: #f9e9e7; color: #923a34; }
    p { margin: 0; }
    .toast-close { border: 0; background: transparent; color: inherit; font-size: 22px; line-height: 1; cursor: pointer; }
    @media (max-width: 600px) { .operation-toast { inset: 10px 10px auto; width: auto; } }
  `],
})
export class OperationToastOutlet {
  readonly toast = inject(OperationToast);
  private readonly popover = viewChild<ElementRef<HTMLElement>>('toastPopover');

  constructor() {
    effect(() => {
      const popover = this.popover()?.nativeElement;
      if (!popover || typeof popover.showPopover !== 'function') return;
      const isOpen = popover.matches(':popover-open');
      if (this.toast.current() && !isOpen) popover.showPopover();
      else if (!this.toast.current() && isOpen) popover.hidePopover();
    });
  }

  dismiss() { this.toast.dismiss(); }
}
