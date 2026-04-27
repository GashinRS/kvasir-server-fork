import { Component, computed, effect, input } from '@angular/core';
import { FieldState, ValidationError } from '@angular/forms/signals';

@Component({
  selector: 'app-form-errors',
  imports: [],
  template: `
    @if (showErrors()) {
      @for (error of field()().errors(); track error.kind) {
        <div
          class="error"
          [class.warning]="isWarning(error)"
          [class.text-danger]="!isWarning(error)"
        >
          {{ error.message }}
        </div>
      }
    }
  `,
  styles: `
    .error {
      font-size: 13px;
      font-weight: normal;
    }
    .warning {
      color: orange;
    }
  `,
})
export class FormErrorsComponent {
  /**
   * The same as your [formField] entry.
   */
  field = input.required<() => FieldState<string | string[], string>>();
  /**
   * Will check touched status to be true, before showing errors. True by default.
   */
  checkTouched = input<boolean>(true);

  protected showErrors = computed(() => {
    return this.checkTouched()
      ? this.field()().touched() && this.field()().invalid()
      : this.field()().invalid();
  });

  isWarning = (error: ValidationError) => error.kind?.startsWith('WARN');
}
