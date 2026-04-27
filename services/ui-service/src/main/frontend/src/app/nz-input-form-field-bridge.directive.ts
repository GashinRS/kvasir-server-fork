import { Directive, effect, inject } from '@angular/core';
import { FormField } from '@angular/forms/signals';
import { NzInputDirective } from 'ng-zorro-antd/input';

@Directive({
  selector: 'input[nz-input][formField], textarea[nz-input][formField]',
  standalone: true,
})
export class NzInputFormFieldBridgeDirective {
  private formField = inject(FormField);
  private nzInput = inject(NzInputDirective);
  constructor() {
    effect(() => {
      const disabled = this.formField.state().disabled();
      // NzInputDirective.disabled is an InputSignal; we can't write to it directly,
      // but we can push via a WritableSignal. ng-zorro exposes controlDisabled:
      this.nzInput.controlDisabled.set(disabled);
    });
  }
}
