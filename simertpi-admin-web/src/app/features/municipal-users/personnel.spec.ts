import { TestBed } from '@angular/core/testing';
import { Personnel } from './personnel';
describe('Inspector presentation form', () => {
  it('rejects incomplete fields and invalid email, username and phone', () => {
    const page = TestBed.createComponent(Personnel).componentInstance;
    page.validate();
    expect(page.validated()).toBe(false);
    page.form.patchValue({
      username: '../admin',
      firstName: ' ',
      lastName: ' ',
      email: 'invalid',
      phone: '<script>',
    });
    page.validate();
    expect(page.form.invalid).toBe(true);
    expect(page.form.controls.username.invalid).toBe(true);
    expect(page.form.controls.email.invalid).toBe(true);
    expect(page.form.controls.phone.invalid).toBe(true);
  });
  it('validates format only and never creates or requests an account', () => {
    const page = TestBed.createComponent(Personnel).componentInstance;
    page.form.setValue({
      username: 'format.review',
      firstName: 'Revisión',
      lastName: 'Local',
      email: 'review@example.invalid',
      phone: '',
    });
    page.validate();
    expect(page.validated()).toBe(true);
    expect(Object.keys(page.form.controls)).not.toContain('password');
    page.closeForm();
    expect(page.form.controls.username.value).toBe('');
    expect(page.validated()).toBe(false);
  });
  it('marks data as disconnected rather than claiming no municipal accounts exist', () => {
    const fixture = TestBed.createComponent(Personnel);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No se consultaron cuentas');
    expect(fixture.nativeElement.querySelector('table caption')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('th[scope="col"]').length).toBe(5);
  });
});
