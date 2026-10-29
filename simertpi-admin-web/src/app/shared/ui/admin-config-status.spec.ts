import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { AdminConfigStatus, AdminConfigStatusTone } from './admin-config-status';

describe('AdminConfigStatus', () => {
  it.each([
    ['active', 'Vigente'],
    ['scheduled', 'Programada'],
    ['cancelled', 'Cancelada'],
    ['ended', 'Finalizada'],
    ['inactive', 'Inactiva'],
    ['neutral', 'Estado'],
  ] as const)('renders the %s state accessibly', async (tone, label) => {
    await TestBed.configureTestingModule({ imports: [AdminConfigStatus] }).compileComponents();
    const fixture = TestBed.createComponent(AdminConfigStatus);
    fixture.componentRef.setInput('label', label);
    fixture.componentRef.setInput('tone', tone satisfies AdminConfigStatusTone);
    fixture.detectChanges();

    const badge: HTMLElement = fixture.nativeElement.querySelector('.admin-config-status');
    expect(badge).not.toBeNull();
    expect(badge.getAttribute('data-tone')).toBe(tone);
    expect(badge.classList).toContain(`admin-config-status--${tone}`);
    expect(badge.textContent?.trim()).toBe(label);
    expect(badge.getAttribute('aria-label')).toBe(`Estado: ${label}`);
    expect(badge.getAttribute('role')).toBe('status');
  });

  it('uses the neutral tone by default', async () => {
    await TestBed.configureTestingModule({ imports: [AdminConfigStatus] }).compileComponents();
    const fixture = TestBed.createComponent(AdminConfigStatus);
    fixture.componentRef.setInput('label', 'Sin estado');
    fixture.detectChanges();

    const badge: HTMLElement = fixture.nativeElement.querySelector('.admin-config-status');
    expect(badge.getAttribute('data-tone')).toBe('neutral');
    expect(badge.classList).toContain('admin-config-status--neutral');
  });
});
