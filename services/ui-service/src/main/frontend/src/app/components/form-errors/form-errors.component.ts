import { Component, input } from '@angular/core';
import { FieldState } from '@angular/forms/signals';

@Component({
  selector: 'app-form-errors',
  imports: [],
  template: `
    @if (field()()['touched']() && field()()['invalid']()) {
      @for (error of field()()['errors'](); track error) {
        <div class="text-danger">{{ error.message }}</div>
      }
    }
  `,
})
export class FormErrorsComponent {
  field = input.required<() => FieldState<string, string>>();
}
