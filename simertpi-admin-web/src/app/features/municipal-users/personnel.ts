import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PageHeading } from '../../shared/ui/page-heading';
import { DataState, DataStateKind } from '../../shared/ui/data-state';
@Component({
  selector: 'app-personnel',
  imports: [ReactiveFormsModule, PageHeading, DataState],
  templateUrl: './personnel.html',
})
export class Personnel {
  readonly state = signal<DataStateKind>('empty');
  readonly showForm = signal(false);
  readonly validated = signal(false);
  private readonly builder = inject(FormBuilder);
  readonly filters = this.builder.nonNullable.group({ search: [''], status: ['all'] });
  readonly form = this.builder.nonNullable.group({
    username: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9][a-zA-Z0-9._-]{2,99}$/)]],
    firstName: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    lastName: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    phone: ['', [Validators.maxLength(30), Validators.pattern(/^[+0-9 ()-]*$/)]],
  });
  openForm() {
    this.showForm.set(true);
    this.validated.set(false);
  }
  closeForm() {
    this.showForm.set(false);
    this.form.reset();
    this.validated.set(false);
  }
  validate() {
    this.form.markAllAsTouched();
    this.validated.set(this.form.valid);
  }
  invalid(name: keyof typeof this.form.controls) {
    const control = this.form.controls[name];
    return control.touched && control.invalid;
  }
}
